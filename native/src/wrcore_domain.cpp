// SPDX-License-Identifier: MIT
//
// wrcore_domain.cpp — the C++ half of the split exchange server.
//
// The Rust core (native/rust/wrxr) owns the transport: region mapping,
// the control page, the build/results queues, the seqlocked frame slot, the
// poller thread and the wr_* C ABI. This file owns what the messages DO:
// the model/mesh registry, the wrobj OBJ builder calls, the recorder hooks
// and the reply payloads. Everything here is driven exclusively through
// the wrcore_* entry points in include/wrcore/wrcore.h, guarded by one
// mutex, so the reply buffer stays valid between a handler and the Rust
// publisher that drains it.
//
// Semantics are a byte-for-byte port of the former wrx.cpp handlers,
// including the per-model failure paths (which publish RES_ERROR, bump the
// result sequence and still ack the message) versus the poison paths (which
// publish nothing and break the stream).

#include "wrcore/wrcore.h"
#include "wrx/wrx.hpp"
#include "wrobj/wrobj.hpp"
#include "wrvk/wrvk.hpp"

#include <algorithm>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

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

// ---------------------------------------------------------------------------
// Core — the registry + handlers. A function-local static (thread-safe
// init) so library boot order can never construct it too early.
// ---------------------------------------------------------------------------

struct MeshEntry {
        std::shared_ptr<wrx::GpuMesh> mesh;
};

struct ModelEntry {
        uint32_t modelId;
        std::vector<wrx::MeshInfo> meshInfos;
        std::vector<std::shared_ptr<wrx::GpuMesh>> meshes;
};

struct Core {
        std::mutex mutex;

        std::unordered_map<uint32_t, std::unique_ptr<ModelEntry>> models;
        std::unordered_map<uint32_t, std::shared_ptr<wrx::GpuMesh>> meshIndex;
        uint32_t nextModelId{1};
        uint32_t nextMeshId{1};

        wrvk::WrvkRecorder* recorder{nullptr};

        WrcoreHostInit hostInitCallback{nullptr};
        void* hostInitUser{nullptr};
        wrx::HostHandles host_{};

        std::vector<uint8_t> reply; // reused per handler; see the contract in wrcore.h

        WrcoreReply finish(int32_t status, uint32_t errorCode) {
                WrcoreReply out{};
                out.status = status;
                out.errorCode = errorCode;
                out.resultType = 0;
                out.bumpResultSeq = 0;
                out.vulkanReady = 0;
                out.resultPtr = nullptr;
                out.resultLen = 0;
                return out;
        }

        WrcoreReply finishResult(int32_t status, uint32_t errorCode, uint32_t resultType, bool bump) {
                WrcoreReply out = finish(status, errorCode);
                out.resultType = resultType;
                out.bumpResultSeq = bump ? 1u : 0u;
                out.resultPtr = reply.empty() ? nullptr : reply.data();
                out.resultLen = reply.size();
                return out;
        }

        // The old publishError: requestId, code, u16 zero-length message.
        void buildErrorReply(uint32_t requestId, uint32_t code) {
                reply.clear();
                Writer w{reply};
                w.write(requestId);
                w.write(code);
                const uint16_t messageLength = 0;
                w.blob(&messageLength, sizeof(messageLength));
        }

        // --- LOAD_OBJ -----------------------------------------------------------
        // Port of the old Impl::handleLoadObj. All registry and wrobj work is
        // identical; publishing the reply is now the caller's (Rust's) job.
        bool handleLoadObj(Reader& reader, WrcoreReply& out) {
                const uint32_t requestId = reader.read<uint32_t>();
                const uint32_t flags = reader.read<uint32_t>();
                const uint32_t objByteCount = reader.read<uint32_t>();
                const uint8_t* objBytes = nullptr;
                uint32_t objLen = 0;
                if (!reader.bytes(objByteCount, objBytes, objLen)) {
                        out = finish(WRCORE_POISON, wrx::ERR_INCONSISTENT);
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
                        out = finish(WRCORE_POISON, wrx::ERR_INCONSISTENT);
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
                        buildErrorReply(requestId, wrx::ERR_INCONSISTENT);
                        out = finishResult(WRCORE_OK, 0, wrx::RES_ERROR, true);
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
                        std::vector<wrx::MeshInfo> meshInfos;
                        std::vector<std::shared_ptr<wrx::GpuMesh>> meshes;
                        uint32_t modelId{0};
                };
                std::vector<GroupBuild> groupBuilds;
                std::vector<wrx::MeshInfo> groupMeshesScratch;

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
                                auto mesh = std::make_shared<wrx::GpuMesh>();
                                const auto* source = static_cast<const uint8_t*>(result->vertexData);
                                mesh->vertices.assign(source + draw.vertexOffset * wrx::VERTEX_BYTES,
                                        source + (draw.vertexOffset + draw.vertexCount) * wrx::VERTEX_BYTES);
                                const auto* sourceIndices = static_cast<const uint32_t*>(result->indexData);
                                mesh->indices.assign(sourceIndices + draw.indexOffset,
                                        sourceIndices + draw.indexOffset + draw.indexCount);

                                wrx::MeshInfo info{};
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
                                        std::memcpy(xyz, vertexBytes + static_cast<size_t>(v) * wrx::VERTEX_BYTES, sizeof(xyz));
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
                                                for (const wrx::MeshInfo& info : groupBuild.meshInfos) {
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
                                for (const wrx::MeshInfo& info : groupBuild.meshInfos) {
                                        groupBuild.meshes.push_back(meshIndex[info.meshId]);
                                }
                                groupBuilds.push_back(std::move(groupBuild));
                        }
                }
                wr_obj_keys_free(keys);

                // reply: requestId, groupCount, per group {name, modelId, meshCount, meshes...}
                reply.clear();
                Writer w{reply};
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
                                const wrx::MeshInfo& info = groupBuild.meshInfos[i];
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
                out = finishResult(WRCORE_OK, 0, wrx::RES_MODELS, true);
                return true;
        }

        // --- INSTANCE -----------------------------------------------------------
        // NewOptimizedModelGroup.merge semantics: translate + optional flip. The
        // flip mirrors x/z of positions and normals and swaps winding, matching
        // the Java path's per-vertex modify() plus the culling behaviour it
        // produces. Identity transforms alias the source GPU data — no copy.
        bool handleInstance(Reader& reader, WrcoreReply& out) {
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
                        buildErrorReply(requestId, wrx::ERR_INCONSISTENT);
                        out = finishResult(reader.overflow ? WRCORE_POISON : WRCORE_OK, 0, wrx::RES_ERROR, true);
                        return !reader.overflow;
                }

                const ModelEntry& source = *it->second;
                auto model = std::make_unique<ModelEntry>();
                model->modelId = nextModelId++;
                model->meshInfos = source.meshInfos;
                model->meshes.reserve(source.meshes.size());

                for (size_t i = 0; i < source.meshes.size(); i++) {
                        const wrx::GpuMesh& src = *source.meshes[i];
                        auto mesh = identity ? source.meshes[i] : std::make_shared<wrx::GpuMesh>();
                        if (!identity) {
                                mesh->vertices = src.vertices;
                                mesh->indices = src.indices;
                                const size_t vertexCount = src.vertices.size() / wrx::VERTEX_BYTES;
                                for (size_t v = 0; v < vertexCount; v++) {
                                        uint8_t* vertex = mesh->vertices.data() + v * wrx::VERTEX_BYTES;
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

                reply.clear();
                Writer w{reply};
                w.write(requestId);
                w.write(model->modelId);
                const uint32_t meshCount = static_cast<uint32_t>(model->meshInfos.size());
                w.write(meshCount);
                for (uint32_t i = 0; i < meshCount; i++) {
                        w.write(model->meshInfos[i].meshId);
                        w.blob(model->meshInfos[i].aabb, sizeof(model->meshInfos[i].aabb));
                }
                out = finishResult(WRCORE_OK, 0, wrx::RES_INSTANCE, true);
                models[model->modelId] = std::move(model);
                return true;
        }

        // --- FREE_MODELS --------------------------------------------------------
        bool handleFreeModels(Reader& reader, WrcoreReply& out) {
                const uint32_t requestId = reader.read<uint32_t>();
                (void)requestId;
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
                // behind the publisher's 2-second drain wait). The generic
                // buildAck that the Rust processBuilds writes per message is the
                // handshake.
                if (reader.overflow) {
                        out = finish(WRCORE_POISON, wrx::ERR_INCONSISTENT);
                        return false;
                }
                out = finish(WRCORE_OK, 0);
                return true;
        }
};

Core& core() {
        static Core instance;
        return instance;
}

} // namespace

extern "C" {

WrcoreReply wrcore_init_vulkan(const uint8_t* data, uint32_t size) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        Reader reader{data, size, 0};
        wrx::HostHandles& host = c.host_;
        host.instance = reader.read<uint64_t>();
        host.physicalDevice = reader.read<uint64_t>();
        host.device = reader.read<uint64_t>();
        host.queue = reader.read<uint64_t>();
        host.renderPass = reader.read<uint64_t>();
        host.queueFamily = reader.read<uint32_t>();
        host.colorFormat = reader.read<uint32_t>();
        host.depthFormat = reader.read<uint32_t>();
        host.sampleCount = reader.read<uint32_t>();
        host.maxPushConstants = reader.read<uint32_t>();
        host.valid = !reader.overflow;
        if (!host.valid) {
                return c.finish(WRCORE_POISON, wrx::ERR_INCONSISTENT);
        }
        if (c.hostInitCallback != nullptr) {
                c.hostInitCallback(&host, c.hostInitUser);
        }
        if (c.recorder != nullptr) {
                c.recorder->attachHost(host);
        }
        WrcoreReply out = c.finish(WRCORE_OK, 0);
        out.vulkanReady = c.recorder != nullptr ? 1u : 0u;
        return out;
}

WrcoreReply wrcore_load_shaders(const uint8_t* data, uint32_t size) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        Reader reader{data, size, 0};
        const uint32_t count = reader.read<uint32_t>();
        if (reader.overflow || count > 16) {
                return c.finish(WRCORE_POISON, reader.overflow ? wrx::ERR_INCONSISTENT : wrx::ERR_VULKAN);
        }
        for (uint32_t i = 0; i < count && !reader.overflow; i++) {
                const uint32_t key = reader.read<uint32_t>();
                const uint32_t byteCount = reader.read<uint32_t>();
                const uint8_t* bytes = nullptr;
                uint32_t byteLen = 0;
                if (!reader.bytes(byteCount, bytes, byteLen) || byteLen < 16 || (byteLen & 3) != 0) {
                        return c.finish(WRCORE_POISON, reader.overflow ? wrx::ERR_INCONSISTENT : wrx::ERR_VULKAN);
                }
                if (c.recorder != nullptr) {
                        c.recorder->uploadShader(key, bytes, byteLen);
                }
        }
        if (reader.overflow) {
                return c.finish(WRCORE_POISON, wrx::ERR_INCONSISTENT);
        }
        return c.finish(WRCORE_OK, 0);
}

WrcoreReply wrcore_load_obj(const uint8_t* data, uint32_t size) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        Reader reader{data, size, 0};
        WrcoreReply out{};
        c.handleLoadObj(reader, out);
        return out;
}

WrcoreReply wrcore_make_instance(const uint8_t* data, uint32_t size) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        Reader reader{data, size, 0};
        WrcoreReply out{};
        c.handleInstance(reader, out);
        return out;
}

WrcoreReply wrcore_free_models(const uint8_t* data, uint32_t size) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        Reader reader{data, size, 0};
        WrcoreReply out{};
        c.handleFreeModels(reader, out);
        return out;
}

int32_t wrcore_record_frame(const void* header, const uint8_t* textures, const uint8_t* items) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        if (c.recorder == nullptr) {
                return 0;
        }
        const auto* frameHeader = static_cast<const wrx::FrameHeader*>(header);
        return c.recorder->recordFrame(*frameHeader, textures, items) ? 1 : 0;
}

void wrcore_set_host_init_callback(WrcoreHostInit callback, void* user) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        c.hostInitCallback = callback;
        c.hostInitUser = user;
}

void wrcore_shutdown_domain(void) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        if (c.recorder != nullptr) {
                for (const auto& entry : c.models) {
                        for (const auto& info : entry.second->meshInfos) {
                                c.recorder->freeMesh(info.meshId);
                        }
                }
        }
        c.models.clear();
        c.meshIndex.clear();
}

uint32_t wrcore_live_models(void) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        return static_cast<uint32_t>(c.models.size());
}

uint32_t wrcore_live_meshes(void) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        return static_cast<uint32_t>(c.meshIndex.size());
}

} // extern "C"

// wrvk attaches its recorder here once Vulkan is initialised (called from
// wrmain.cpp's host-init trampoline after it creates the dispatch and the
// recorder). Not part of the Rust ABI — C++-only wiring.
namespace wrcore {
void attachRecorder(wrvk::WrvkRecorder* recorder) {
        Core& c = core();
        std::lock_guard<std::mutex> lock(c.mutex);
        c.recorder = recorder;
}
} // namespace wrcore
