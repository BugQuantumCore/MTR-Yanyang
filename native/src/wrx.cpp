// SPDX-License-Identifier: MIT
//
// wrx.cpp — the mesh store and message processing of the exchange server.
//
// Every handler runs either on the shm poller thread or inside a wr_* FFM
// downcall; the stores are guarded by one mutex because builds arrive from
// Java worker threads while frames arrive from the render thread.
//
// Queue reset protocol (identical on both queues, mirrored):
//   * the writer may only move its cursor back to zero when it observes
//     readPos == writePos (fully drained), and it publishes the new writePos
//     with release AFTER the bytes are in place;
//   * the reader resets readPos to zero whenever it observes
//     readPos > writePos (a wrap just happened), and re-evaluates.
// Together these make the wrap lossless: the writer only reuses space the
// reader has fully consumed, and the reader never misses a message published
// across a wrap because the transient state is always detectable.

#include "wrx/wrx.hpp"
#include "wrobj/wrobj.hpp"
#include "wrvk/wrvk.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <unordered_map>

namespace wrx {

namespace {

struct Reader {
        const uint8_t* data;
        uint32_t size;
        uint32_t pos;
        bool overflow{false};

        template <typename T>
        T read() {
                if (pos + sizeof(T) > size) {
                        overflow = true;
                        return T{};
                }
                T value;
                std::memcpy(&value, data + pos, sizeof(T));
                pos += static_cast<uint32_t>(sizeof(T));
                return value;
        }

        bool bytes(uint32_t count, const uint8_t*& out, uint32_t& outCount) {
                if (pos + count > size) {
                        overflow = true;
                        out = nullptr;
                        outCount = 0;
                        return false;
                }
                out = data + pos;
                outCount = count;
                pos += count;
                return true;
        }
};

struct Writer {
        std::vector<uint8_t>& out;

        template <typename T>
        void write(const T& value) {
                const auto* bytes = reinterpret_cast<const uint8_t*>(&value);
                out.insert(out.end(), bytes, bytes + sizeof(T));
        }

        void blob(const void* data, uint32_t size) {
                const auto* bytes = static_cast<const uint8_t*>(data);
                out.insert(out.end(), bytes, bytes + size);
        }
};

uint32_t roundUp8(uint32_t v) {
        return (v + 7) & ~7u;
}

} // namespace

struct Server::Impl {
        std::mutex mutex;
        Region region;
        bool booted{false};
        std::atomic<bool> shutdownRequested{false};

        struct ModelEntry {
                uint32_t modelId;
                std::vector<MeshInfo> meshInfos;
                std::vector<std::shared_ptr<GpuMesh>> meshes;
        };

        std::unordered_map<uint32_t, std::unique_ptr<ModelEntry>> models;
        std::unordered_map<uint32_t, std::shared_ptr<GpuMesh>> meshIndex;
        uint32_t nextModelId{1};
        uint32_t nextMeshId{1};

        std::unordered_map<uint32_t, std::vector<uint8_t>> shaders;

        wrvk::WrvkRecorder* recorder{nullptr};

        void setError(uint32_t code) {
                region.control()->errorCode.store(code, std::memory_order_release);
                region.control()->flags.fetch_or(FLAG_ERROR, std::memory_order_release);
        }

        // Publish one results message (C++ is the writer of results.bin).
        bool publishResult(uint32_t type, const std::vector<uint8_t>& payload) {
                BumpHeader* header = region.resultsHeader();
                const uint64_t capacity = header->capacity.load(std::memory_order_relaxed);
                uint64_t writePos = header->writePos.load(std::memory_order_relaxed);
                const uint32_t total = MESSAGE_HEADER_SIZE + roundUp8(static_cast<uint32_t>(payload.size()));

                if (writePos + total > capacity) {
                        // Wait for a full drain, then wrap. Bounded: a stalled Java
                        // consumer is an anomaly; surface it via the error flag.
                        bool drained = false;
                        for (int i = 0; i < 4000; ++i) {
                                if (header->readPos.load(std::memory_order_acquire) == writePos) {
                                        drained = true;
                                        break;
                                }
                                std::this_thread::sleep_for(std::chrono::microseconds(500));
                        }
                        if (!drained) {
                                setError(ERR_TIMEOUT);
                                return false;
                        }
                        writePos = 0;
                }

                uint8_t* payloadArea = region.resultsPayload();
                std::memcpy(payloadArea + writePos, &type, sizeof(uint32_t));
                const uint32_t payloadSize = static_cast<uint32_t>(payload.size());
                std::memcpy(payloadArea + writePos + 4, &payloadSize, sizeof(uint32_t));
                if (!payload.empty()) {
                        std::memcpy(payloadArea + writePos + MESSAGE_HEADER_SIZE, payload.data(), payloadSize);
                        if (total != MESSAGE_HEADER_SIZE + payloadSize) {
                                std::memset(payloadArea + writePos + MESSAGE_HEADER_SIZE + payloadSize, 0, total - MESSAGE_HEADER_SIZE - payloadSize);
                        }
                }
                header->writePos.store(writePos + total, std::memory_order_release);
                return true;
        }

        void publishError(uint32_t requestId, uint32_t code) {
                std::vector<uint8_t> payload;
                Writer w{payload};
                w.write(requestId);
                w.write(code);
                const uint16_t messageLength = 0;
                w.blob(&messageLength, sizeof(messageLength));
                publishResult(RES_ERROR, payload);
        }

        bool handleLoadObj(Reader& reader) {
                const uint32_t requestId = reader.read<uint32_t>();
                const uint32_t flags = reader.read<uint32_t>();
                const uint32_t objByteCount = reader.read<uint32_t>();
                const uint8_t* objBytes = nullptr;
                uint32_t objLen = 0;
                if (!reader.bytes(objByteCount, objBytes, objLen)) {
                        setError(ERR_INCONSISTENT);
                        return false;
                }
                const uint32_t mtlCount = reader.read<uint32_t>();

                std::string mtlCombined;
                for (uint32_t i = 0; i < mtlCount && !reader.overflow; i++) {
                        const uint32_t nameByteCount = reader.read<uint32_t>();
                        const uint8_t* nameBytes = nullptr;
                        uint32_t nameLen = 0;
                        if (!reader.bytes(nameByteCount, nameBytes, nameLen)) {
                                break;
                        }
                        const uint32_t contentByteCount = reader.read<uint32_t>();
                        const uint8_t* contentBytes = nullptr;
                        uint32_t contentLen = 0;
                        if (!reader.bytes(contentByteCount, contentBytes, contentLen)) {
                                break;
                        }
                        // javagl's ObjReader puts every mtllib into one material namespace
                        // keyed by material name, so concatenation reproduces it exactly.
                        mtlCombined.append(reinterpret_cast<const char*>(contentBytes), contentLen);
                        mtlCombined.append("\n\n", 2);
                }
                if (reader.overflow) {
                        setError(ERR_INCONSISTENT);
                        return false;
                }

                const bool splitModel = (flags & 1) != 0;
                const bool flipTextureV = (flags & 2) != 0;
                const bool hasMtl = mtlCount > 0;

                const char* objText = reinterpret_cast<const char*>(objBytes);
                const int32_t objTextLen = static_cast<int32_t>(objLen);

                // Stage 1: key discovery (texture three-way rule + material colours).
                WrObjKeyList* keys = wr_obj_keys_load(objText, objTextLen,
                        hasMtl ? mtlCombined.c_str() : nullptr,
                        static_cast<int32_t>(mtlCombined.size()), flipTextureV ? 1 : 0);
                if (keys == nullptr) {
                        publishError(requestId, ERR_INCONSISTENT);
                        region.control()->buildResultSeq.fetch_add(1, std::memory_order_release);
                        return true; // per-model failure, not a protocol failure
                }

                std::vector<int32_t> keyTextureHandles(static_cast<size_t>(keys->count));
                std::vector<int32_t> keyColorSet(static_cast<size_t>(keys->count));
                for (int32_t i = 0; i < keys->count; i++) {
                        keyTextureHandles[static_cast<size_t>(i)] = i; // opaque: the key index
                        keyColorSet[static_cast<size_t>(i)] = keys->entries[i].hasColor;
                }

                // Per (geometry-group) result: model ids must be per group, because the
                // Java side addresses model parts by group name (ModelPropertiesPart
                // writeCache). One model entry per group, one name in the reply.
                struct GroupBuild {
                        std::string name;
                        std::vector<MeshInfo> meshInfos;
                        std::vector<std::shared_ptr<GpuMesh>> meshes;
                        uint32_t modelId{0};
                };
                std::vector<GroupBuild> groupBuilds;
                std::vector<MeshInfo> groupMeshesScratch;

                const auto buildOne = [&](WrObjBuilder* builder, const std::string& groupName) {
                        if (builder == nullptr) {
                                return;
                        }
                        // The reference pipeline places source meshes explicitly; a bare
                        // builder has no placements and produces no geometry. One identity
                        // placement exposes the source mesh set.
                        wr_obj_builder_add_transform(builder, 0, 0.0, 0.0, 0.0, 0);
                        WrObjResult* result = wr_obj_builder_build(builder);
                        wr_obj_builder_free(builder);
                        if (result == nullptr) {
                                return;
                        }
                        const auto* draws = static_cast<const WrObjResultDraw*>(result->drawData);
                        for (int32_t d = 0; d < result->drawCount; d++) {
                                const WrObjResultDraw& draw = draws[d];
                                auto mesh = std::make_shared<GpuMesh>();
                                const auto* source = static_cast<const uint8_t*>(result->vertexData);
                                mesh->vertices.assign(source + draw.vertexOffset * VERTEX_BYTES,
                                        source + (draw.vertexOffset + draw.vertexCount) * VERTEX_BYTES);
                                const auto* sourceIndices = static_cast<const uint32_t*>(result->indexData);
                                mesh->indices.assign(sourceIndices + draw.indexOffset,
                                        sourceIndices + draw.indexOffset + draw.indexCount);

                                MeshInfo info{};
                                info.meshId = nextMeshId++;
                                info.vertexCount = static_cast<uint32_t>(draw.vertexCount);
                                info.indexCount = static_cast<uint32_t>(draw.indexCount);
                                info.kd[0] = static_cast<uint8_t>((draw.color >> 24) & 0xFF);
                                info.kd[1] = static_cast<uint8_t>((draw.color >> 16) & 0xFF);
                                info.kd[2] = static_cast<uint8_t>((draw.color >> 8) & 0xFF);
                                info.kd[3] = static_cast<uint8_t>(draw.color & 0xFF);
                                info.name = keys->entries[draw.keyIndex].key;

                                float bounds[6] = {1e30F, 1e30F, 1e30F, -1e30F, -1e30F, -1e30F};
                                const auto* vertexBytes = mesh->vertices.data();
                                for (int32_t v = 0; v < draw.vertexCount; v++) {
                                        float xyz[3];
                                        std::memcpy(xyz, vertexBytes + static_cast<size_t>(v) * VERTEX_BYTES, sizeof(xyz));
                                        bounds[0] = std::min(bounds[0], xyz[0]);
                                        bounds[1] = std::min(bounds[1], xyz[1]);
                                        bounds[2] = std::min(bounds[2], xyz[2]);
                                        bounds[3] = std::max(bounds[3], xyz[0]);
                                        bounds[4] = std::max(bounds[4], xyz[1]);
                                        bounds[5] = std::max(bounds[5], xyz[2]);
                                }
                                if (draw.vertexCount == 0) {
                                        std::memset(bounds, 0, sizeof(bounds));
                                }
                                std::memcpy(info.aabb, bounds, sizeof(bounds));

                                meshIndex[info.meshId] = mesh;
                                if (recorder != nullptr) {
                                        recorder->uploadMesh(info.meshId, *mesh);
                                }
                                groupMeshesScratch.push_back(std::move(info));
                        }
                        wr_obj_result_free(result);
                };

                const char* mtlText = hasMtl ? mtlCombined.c_str() : nullptr;
                const int32_t mtlTextLen = static_cast<int32_t>(mtlCombined.size());
                if (splitModel) {
                        WrObjGroupList* groups = wr_obj_groups_load(objText, objTextLen);
                        if (groups != nullptr) {
                                for (int32_t g = 0; g < groups->count; g++) {
                                        GroupBuild groupBuild;
                                        groupBuild.name = groups->entries[g].name;
                                        groupMeshesScratch.clear();
                                        buildOne(wr_obj_builder_create_group(objText, objTextLen, mtlText, mtlTextLen,
                                                keyTextureHandles.data(), keyColorSet.data(), static_cast<int32_t>(keyTextureHandles.size()),
                                                hasMtl ? 1 : 0, flipTextureV ? 1 : 0,
                                                groups->entries[g].name, -1), groupBuild.name);
                                        if (!groupMeshesScratch.empty()) {
                                                groupBuild.modelId = nextModelId++;
                                                groupBuild.meshInfos = std::move(groupMeshesScratch);
                                                for (const MeshInfo& info : groupBuild.meshInfos) {
                                                        groupBuild.meshes.push_back(meshIndex[info.meshId]);
                                                }
                                                groupBuilds.push_back(std::move(groupBuild));
                                        }
                                }
                                wr_obj_groups_free(groups);
                        }
                } else {
                        GroupBuild groupBuild;
                        groupBuild.name = ""; // javagl parity: addModel("", ...) for the non-split build
                        groupMeshesScratch.clear();
                        buildOne(wr_obj_builder_create(objText, objTextLen, mtlText, mtlTextLen,
                                keyTextureHandles.data(), keyColorSet.data(), static_cast<int32_t>(keyTextureHandles.size()),
                                hasMtl ? 1 : 0, flipTextureV ? 1 : 0), groupBuild.name);
                        if (!groupMeshesScratch.empty()) {
                                groupBuild.modelId = nextModelId++;
                                groupBuild.meshInfos = std::move(groupMeshesScratch);
                                for (const MeshInfo& info : groupBuild.meshInfos) {
                                        groupBuild.meshes.push_back(meshIndex[info.meshId]);
                                }
                                groupBuilds.push_back(std::move(groupBuild));
                        }
                }
                wr_obj_keys_free(keys);

                // reply: requestId, groupCount, per group {name, modelId, meshCount, meshes...}
                std::vector<uint8_t> payload;
                Writer w{payload};
                w.write(requestId);
                const uint32_t groupCount = static_cast<uint32_t>(groupBuilds.size());
                w.write(groupCount);
                for (uint32_t g = 0; g < groupCount; g++) {
                        const GroupBuild& groupBuild = groupBuilds[g];
                        const uint32_t groupNameLength = static_cast<uint32_t>(groupBuild.name.size());
                        w.write(groupNameLength);
                        w.blob(groupBuild.name.data(), groupNameLength);
                        w.write(groupBuild.modelId);
                        const uint32_t meshCount = static_cast<uint32_t>(groupBuild.meshInfos.size());
                        w.write(meshCount);
                        for (uint32_t i = 0; i < meshCount; i++) {
                                const MeshInfo& info = groupBuild.meshInfos[i];
                                w.write(info.meshId);
                                w.write(info.vertexCount);
                                w.write(info.indexCount);
                                w.blob(info.aabb, sizeof(info.aabb));
                                w.write<uint32_t>(info.kd[0]);
                                w.write<uint32_t>(info.kd[1]);
                                w.write<uint32_t>(info.kd[2]);
                                w.write<uint32_t>(info.kd[3]);
                                const uint32_t nameByteCount = static_cast<uint32_t>(info.name.size());
                                w.write(nameByteCount);
                                w.blob(info.name.data(), nameByteCount);
                        }
                        auto model = std::make_unique<ModelEntry>();
                        model->modelId = groupBuild.modelId;
                        model->meshInfos = groupBuild.meshInfos;
                        model->meshes = groupBuild.meshes;
                        models[groupBuild.modelId] = std::move(model);
                }
                publishResult(RES_MODELS, payload);
                region.control()->buildResultSeq.fetch_add(1, std::memory_order_release);
                return true;
        }

        // NewOptimizedModelGroup.merge semantics: translate + optional flip. The
        // flip mirrors x/z of positions and normals and swaps winding, matching
        // the Java path's per-vertex modify() plus the culling behaviour it
        // produces. Identity transforms alias the source GPU data — no copy.
        bool handleInstance(Reader& reader) {
                const uint32_t requestId = reader.read<uint32_t>();
                const uint32_t sourceModelId = reader.read<uint32_t>();
                const uint32_t flipFlag = reader.read<uint32_t>();
                const float dx = reader.read<float>();
                const float dy = reader.read<float>();
                const float dz = reader.read<float>();
                const bool flip = flipFlag != 0;
                const bool identity = !flip && dx == 0.0F && dy == 0.0F && dz == 0.0F;

                const auto it = models.find(sourceModelId);
                if (reader.overflow || it == models.end()) {
                        publishError(requestId, ERR_INCONSISTENT);
                        region.control()->buildResultSeq.fetch_add(1, std::memory_order_release);
                        return !reader.overflow;
                }

                const ModelEntry& source = *it->second;
                auto model = std::make_unique<ModelEntry>();
                model->modelId = nextModelId++;
                model->meshInfos = source.meshInfos;
                model->meshes.reserve(source.meshes.size());

                for (size_t i = 0; i < source.meshes.size(); i++) {
                        const GpuMesh& src = *source.meshes[i];
                        auto mesh = identity ? source.meshes[i] : std::make_shared<GpuMesh>();
                        if (!identity) {
                                mesh->vertices = src.vertices;
                                mesh->indices = src.indices;
                                const size_t vertexCount = src.vertices.size() / VERTEX_BYTES;
                                for (size_t v = 0; v < vertexCount; v++) {
                                        uint8_t* vertex = mesh->vertices.data() + v * VERTEX_BYTES;
                                        float xyz[3];
                                        std::memcpy(xyz, vertex, sizeof(xyz));
                                        xyz[0] = (flip ? -xyz[0] : xyz[0]) + dx;
                                        xyz[1] = xyz[1] + dy;
                                        xyz[2] = (flip ? -xyz[2] : xyz[2]) + dz;
                                        std::memcpy(vertex, xyz, sizeof(xyz));
                                        uint8_t* normal = vertex + 20;
                                        if (flip) {
                                                normal[0] = static_cast<uint8_t>(-static_cast<int8_t>(normal[0]));
                                                normal[2] = static_cast<uint8_t>(-static_cast<int8_t>(normal[2]));
                                        }
                                }
                                if (flip) {
                                        for (size_t t = 0; t + 2 < mesh->indices.size(); t += 3) {
                                                std::swap(mesh->indices[t + 1], mesh->indices[t + 2]);
                                        }
                                }

                                float aabb[6];
                                std::memcpy(aabb, model->meshInfos[i].aabb, sizeof(aabb));
                                if (flip) {
                                        const float minX = -aabb[3] + dx;
                                        const float maxX = -aabb[0] + dx;
                                        const float minZ = -aabb[5] + dz;
                                        const float maxZ = -aabb[2] + dz;
                                        aabb[0] = minX;
                                        aabb[3] = maxX;
                                        aabb[2] = minZ;
                                        aabb[5] = maxZ;
                                } else {
                                        aabb[0] += dx;
                                        aabb[3] += dx;
                                        aabb[2] += dz;
                                        aabb[5] += dz;
                                }
                                aabb[1] += dy;
                                aabb[4] += dy;
                                std::memcpy(model->meshInfos[i].aabb, aabb, sizeof(aabb));
                        }
                        const uint32_t meshId = nextMeshId++;
                        model->meshInfos[i].meshId = meshId;
                        if (recorder != nullptr) {
                                recorder->uploadMesh(meshId, *mesh);
                        }
                        meshIndex[meshId] = std::move(mesh);
                }

                std::vector<uint8_t> payload;
                Writer w{payload};
                w.write(requestId);
                w.write(model->modelId);
                const uint32_t meshCount = static_cast<uint32_t>(model->meshInfos.size());
                w.write(meshCount);
                for (uint32_t i = 0; i < meshCount; i++) {
                        w.write(model->meshInfos[i].meshId);
                        w.blob(model->meshInfos[i].aabb, sizeof(model->meshInfos[i].aabb));
                }
                publishResult(RES_INSTANCE, payload);
                models[model->modelId] = std::move(model);
                region.control()->buildResultSeq.fetch_add(1, std::memory_order_release);
                return true;
        }

        bool handleFreeModels(Reader& reader) {
                const uint32_t requestId = reader.read<uint32_t>();
                const uint32_t count = reader.read<uint32_t>();
                for (uint32_t i = 0; i < count && !reader.overflow; i++) {
                        const uint32_t modelId = reader.read<uint32_t>();
                        const auto it = models.find(modelId);
                        if (it == models.end()) {
                                continue;
                        }
                        if (recorder != nullptr) {
                                for (const auto& info : it->second->meshInfos) {
                                        recorder->freeMesh(info.meshId);
                                }
                        }
                        for (const auto& info : it->second->meshInfos) {
                                meshIndex.erase(info.meshId);
                        }
                        models.erase(it);
                }
                // No results-queue echo: Java's request ids are monotonic, so a
                // flow-control reply would only fill results.bin with messages
                // nobody consumes (a full queue then wedges every later reply
                // behind publishResult's 2-second drain wait). The generic
                // buildAck that processBuilds writes per message is the handshake.
                return !reader.overflow;
        }
};

// ---------------------------------------------------------------------------
// Server public surface
// ---------------------------------------------------------------------------

Server::Server() : impl_(new Impl) {
}

Server::~Server() {
        delete impl_;
}

bool Server::boot(const std::string& configPath) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (impl_->booted) {
                return true;
        }
        bool configOk = false;
        const RegionPaths paths = parseConfig(configPath, configOk);
        if (!configOk) {
                return false;
        }
        if (!Region::open(paths, impl_->region)) {
                return false;
        }
        impl_->booted = true;
        return true;
}

void Server::shutdown() {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return;
        }
        impl_->region.control()->flags.fetch_or(FLAG_SHUTDOWN, std::memory_order_release);
        if (impl_->recorder != nullptr) {
                for (const auto& entry : impl_->models) {
                        for (const auto& info : entry.second->meshInfos) {
                                impl_->recorder->freeMesh(info.meshId);
                        }
                }
        }
        impl_->models.clear();
        impl_->meshIndex.clear();
        impl_->shaders.clear();
        impl_->region = Region{};
        impl_->shutdownRequested.store(false, std::memory_order_release);
        impl_->booted = false;
}

void Server::setRecorder(wrvk::WrvkRecorder* recorder) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->recorder = recorder;
        recorder_ = recorder;
}

bool Server::booted() const {
        return impl_->booted;
}

int64_t Server::processBuilds() {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return -1;
        }
        Region& region = impl_->region;
        BumpHeader* header = region.buildHeader();
        uint64_t readPos = header->readPos.load(std::memory_order_relaxed);
        uint64_t writePos = header->writePos.load(std::memory_order_acquire);
        if (readPos > writePos) {
                // the writer wrapped; restart from the new generation
                readPos = 0;
                header->readPos.store(0, std::memory_order_release);
        }
        int64_t processed = 0;
        const uint64_t capacity = header->capacity.load(std::memory_order_relaxed);
        uint8_t* payloadArea = region.buildPayload();
        while (readPos + MESSAGE_HEADER_SIZE <= writePos) {
                uint32_t type = 0;
                uint32_t size = 0;
                std::memcpy(&type, payloadArea + readPos, sizeof(uint32_t));
                std::memcpy(&size, payloadArea + readPos + 4, sizeof(uint32_t));
                const uint32_t total = MESSAGE_HEADER_SIZE + roundUp8(size);
                if (size > capacity || readPos + total > writePos) {
                        // A malformed record would otherwise be re-read on every pass and
                        // wedge the queue (and every Java publish waiting for its ack)
                        // permanently: skip to the write position, count it as an error,
                        // and let the stream drain.
                        impl_->setError(ERR_INCONSISTENT);
                        header->readPos.store(writePos, std::memory_order_release);
                        return -1;
                }
                Reader reader{payloadArea + readPos + MESSAGE_HEADER_SIZE, size, 0};
                bool ok;
                switch (type) {
                        case MSG_INIT_VULKAN:
                                ok = initVulkan(reader.data, reader.size);
                                break;
                        case MSG_LOAD_SHADERS:
                                ok = loadShaders(reader.data, reader.size);
                                break;
                        case MSG_LOAD_OBJ:
                                ok = impl_->handleLoadObj(reader);
                                break;
                        case MSG_INSTANCE:
                                ok = impl_->handleInstance(reader);
                                break;
                        case MSG_FREE_MODELS:
                                ok = impl_->handleFreeModels(reader);
                                break;
                        case MSG_SHUTDOWN:
                                // Flag only: tearing the server down takes the region mutex,
                                // which processBuilds already holds, so the poller performs the
                                // actual teardown after this pass, outside every lock. The
                                // per-message ack below is Java's signal that the request landed.
                                impl_->shutdownRequested.store(true, std::memory_order_release);
                                ok = true;
                                break;
                        default:
                                ok = false;
                                break;
                }
                if (!ok) {
                        // Advance past the poisoned message for the same reason as the
                        // malformed-header guard above; the error flag still reports it.
                        impl_->setError(reader.overflow ? ERR_INCONSISTENT : ERR_VULKAN);
                        header->readPos.store(readPos + total, std::memory_order_release);
                        region.control()->buildAck.fetch_add(1, std::memory_order_release);
                        return -1;
                }
                readPos += total;
                processed++;
                header->readPos.store(readPos, std::memory_order_release);
                region.control()->buildAck.fetch_add(1, std::memory_order_release);
        }
        return processed;
}

bool Server::shutdownRequested() const {
        return impl_->shutdownRequested.load(std::memory_order_acquire);
}

int64_t Server::processFrame() {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return -1;
        }
        ControlBlock* control = impl_->region.control();
        const uint64_t frameSeq = control->frameSeq.load(std::memory_order_acquire);
        if (frameSeq == control->frameAck.load(std::memory_order_relaxed)) {
                return 0;
        }

        uint8_t* frame = impl_->region.framePayload();
        FrameHeader header;
        bool stable = false;
        for (int attempt = 0; attempt < 200 && !stable; attempt++) {
                uint64_t seq = 0;
                std::memcpy(&seq, frame, sizeof(uint64_t));
                if ((seq & 1) != 0) {
                        std::this_thread::sleep_for(std::chrono::microseconds(25));
                        continue;
                }
                std::memcpy(&header, frame + 8, sizeof(FrameHeader));
                if (header.magic != FRAME_MAGIC || header.itemCount > FRAME_MAX_ITEMS || header.textureCount > FRAME_MAX_TEXTURES) {
                        break;
                }
                const uint64_t needed = 8 + FRAME_ITEMS_OFF_BASE +
                        static_cast<uint64_t>(header.itemCount) * sizeof(FrameItem);
                if (needed > impl_->region.frameSize()) {
                        break;
                }
                std::vector<uint8_t> rest(static_cast<size_t>(needed) - 8 - sizeof(FrameHeader));
                if (!rest.empty()) {
                        std::memcpy(rest.data(), frame + 8 + sizeof(FrameHeader), rest.size());
                }
                uint64_t recheck = 0;
                std::memcpy(&recheck, frame, sizeof(uint64_t));
                if (recheck == seq) {
                        const bool recorded = recorder_ != nullptr && recorder_->recordFrame(header,
                                frame + 8 + FRAME_TEXTURE_COUNT_OFF,
                                frame + 8 + FRAME_ITEMS_OFF_BASE);
                        if (recorded) {
                                control->frameAck.store(frameSeq, std::memory_order_release);
                                return 1;
                        }
                        stable = true; // stop retrying; fall through to the error path
                }
        }
        control->frameErrorCount.fetch_add(1, std::memory_order_relaxed);
        control->frameAck.store(frameSeq, std::memory_order_release);
        return 0;
}

void Server::pulse() {
        if (impl_->booted) {
                impl_->region.control()->nativeHeartbeat.fetch_add(1, std::memory_order_relaxed);
        }
}

void Server::setHostInitCallback(void (*callback)(const HostHandles&, void*), void* user) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        hostInitCallback_ = callback;
        hostInitUser_ = user;
}

bool Server::initVulkan(const uint8_t* data, uint32_t size) {
        Reader reader{data, size, 0};
        host_.instance = reader.read<uint64_t>();
        host_.physicalDevice = reader.read<uint64_t>();
        host_.device = reader.read<uint64_t>();
        host_.queue = reader.read<uint64_t>();
        host_.renderPass = reader.read<uint64_t>();
        host_.queueFamily = reader.read<uint32_t>();
        host_.colorFormat = reader.read<uint32_t>();
        host_.depthFormat = reader.read<uint32_t>();
        host_.sampleCount = reader.read<uint32_t>();
        host_.maxPushConstants = reader.read<uint32_t>();
        host_.valid = !reader.overflow;
        if (host_.valid) {
                if (hostInitCallback_ != nullptr) {
                        hostInitCallback_(host_, hostInitUser_);
                }
                if (recorder_ != nullptr) {
                        recorder_->attachHost(host_);
                        impl_->region.control()->flags.fetch_or(FLAG_VULKAN_READY, std::memory_order_release);
                }
        }
        return host_.valid;
}

bool Server::loadShaders(const uint8_t* data, uint32_t size) {
        Reader reader{data, size, 0};
        const uint32_t count = reader.read<uint32_t>();
        if (reader.overflow || count > 16) {
                return false;
        }
        for (uint32_t i = 0; i < count && !reader.overflow; i++) {
                const uint32_t key = reader.read<uint32_t>();
                const uint32_t byteCount = reader.read<uint32_t>();
                const uint8_t* bytes = nullptr;
                uint32_t byteLen = 0;
                if (!reader.bytes(byteCount, bytes, byteLen) || byteLen < 16 || (byteLen & 3) != 0) {
                        return false;
                }
                if (recorder_ != nullptr) {
                        recorder_->uploadShader(key, bytes, byteLen);
                }
                impl_->shaders[key] = std::vector<uint8_t>(bytes, bytes + byteLen);
        }
        return !reader.overflow;
}

bool Server::loadObj(const uint8_t* data, uint32_t size) {
        Reader reader{data, size, 0};
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return false;
        }
        return impl_->handleLoadObj(reader);
}

bool Server::makeInstance(const uint8_t* data, uint32_t size) {
        Reader reader{data, size, 0};
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return false;
        }
        return impl_->handleInstance(reader);
}

bool Server::freeModels(const uint8_t* data, uint32_t size) {
        Reader reader{data, size, 0};
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->booted) {
                return false;
        }
        return impl_->handleFreeModels(reader);
}

bool Server::hasShader(uint32_t key) const {
        return impl_->shaders.count(key) != 0;
}

uint32_t Server::shaderSize(uint32_t key) const {
        const auto it = impl_->shaders.find(key);
        return it == impl_->shaders.end() ? 0 : static_cast<uint32_t>(it->second.size());
}

const uint8_t* Server::shaderCode(uint32_t key) const {
        const auto it = impl_->shaders.find(key);
        return it == impl_->shaders.end() ? nullptr : it->second.data();
}

size_t Server::liveMeshCount() const {
        return impl_->meshIndex.size();
}

size_t Server::liveModelCount() const {
        return impl_->models.size();
}

const Server::GpuMesh* Server::mesh(uint32_t meshId) const {
        const auto it = impl_->meshIndex.find(meshId);
        return it == impl_->meshIndex.end() ? nullptr : it->second.get();
}

} // namespace wrx
