// SPDX-License-Identifier: MIT
//
// wrx_tests — exchange protocol round-trips driven by a hand-written "Java
// side" (byte writer mirroring org.mtr.nativeobj.WrxLayout), plus queue-wrap
// and leak assertions. No Vulkan recorder is attached, so these tests cover
// the transport and mesh-store only.

#include "wrx/wrx.hpp"

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool condition, const char* what) {
        g_checks++;
        if (!condition) {
                g_failures++;
                std::printf("  FAIL %s\n", what);
        }
}

// --- tiny region factory: Java's exact boot sequence -----------------------

constexpr uint64_t BUILD_CAPACITY = 1 << 20;
constexpr uint64_t RESULTS_CAPACITY = 1 << 18;
constexpr uint32_t FRAME_CAPACITY =
        8 + sizeof(wrx::FrameHeader) + sizeof(wrx::FrameTexture) * 8 + sizeof(wrx::FrameItem) * 64;

struct TestRegion {
        std::filesystem::path directory;
        wrx::Region region;
};

// Write a file filled with zeros at `size`, then memory-map it through Region.
bool createFile(const std::filesystem::path& path, uint64_t size) {
        std::ofstream stream(path, std::ios::binary | std::ios::trunc);
        const std::vector<char> zeros(1 << 16, 0);
        uint64_t left = size;
        while (left > 0) {
                const uint64_t chunk = left < zeros.size() ? left : zeros.size();
                stream.write(zeros.data(), static_cast<std::streamsize>(chunk));
                if (!stream.good()) {
                        return false;
                }
                left -= chunk;
        }
        return true;
}

std::unique_ptr<TestRegion> createRegion(const char* tag) {
        const auto directory = std::filesystem::temp_directory_path() /
                ("wrx-tests-" + std::to_string(static_cast<int>(::getpid())) + "-" + tag);
        std::filesystem::create_directories(directory);
        wrx::RegionPaths paths;
        paths.directory = directory.string();
        paths.controlPath = (directory / wrx::CONTROL_FILE).string();
        paths.buildPath = (directory / wrx::BUILD_FILE).string();
        paths.resultsPath = (directory / wrx::RESULTS_FILE).string();
        paths.framePath = (directory / wrx::FRAME_FILE).string();
        if (!createFile(paths.controlPath, wrx::CONTROL_SIZE) ||
                !createFile(paths.buildPath, BUILD_CAPACITY) ||
                !createFile(paths.resultsPath, RESULTS_CAPACITY) ||
                !createFile(paths.framePath, FRAME_CAPACITY)) {
                return nullptr;
        }
        // Java writes the magic and protocol version into the control file before
        // the native library maps it; mirror that order here.
        {
                std::ofstream stream(paths.controlPath, std::ios::binary | std::ios::in);
                uint32_t header[2] = {wrx::CONTROL_MAGIC, wrx::PROTOCOL_VERSION};
                stream.seekp(0);
                stream.write(reinterpret_cast<const char*>(header), sizeof(header));
        }
        auto* out = new TestRegion();
        out->directory = directory;
        if (!wrx::Region::open(paths, out->region)) {
                delete out;
                return nullptr;
        }
        auto* control = out->region.control();
        control->magic.store(wrx::CONTROL_MAGIC);
        control->protocolVersion.store(wrx::PROTOCOL_VERSION);
        auto* buildHeader = out->region.buildHeader();
        buildHeader->capacity.store(BUILD_CAPACITY - sizeof(wrx::BumpHeader));
        auto* resultsHeader = out->region.resultsHeader();
        resultsHeader->capacity.store(RESULTS_CAPACITY - sizeof(wrx::BumpHeader));
        return std::unique_ptr<TestRegion>(out);
}

// --- message writer mirroring Java's WrxLayout ------------------------------

struct MessageWriter {
        std::vector<uint8_t> bytes;

        void u32(uint32_t v) {
                bytes.insert(bytes.end(), reinterpret_cast<uint8_t*>(&v), reinterpret_cast<uint8_t*>(&v) + 4);
        }

        void f32(float v) {
                u32(0);
                std::memcpy(bytes.data() + bytes.size() - 4, &v, 4);
        }

        void blob(const void* data, uint32_t size) {
                const auto* source = static_cast<const uint8_t*>(data);
                bytes.insert(bytes.end(), source, source + size);
        }

        void str(const std::string& text) {
                u32(static_cast<uint32_t>(text.size()));
                blob(text.data(), static_cast<uint32_t>(text.size()));
        }
};

uint32_t roundUp8(uint32_t v) {
        return (v + 7) & ~7u;
}

// Java-side publish: reserve space (waiting for the reader if needed), write
// the message, publish buildSeq. Mirrors SharedMemoryTransport exactly.
bool publishBuild(wrx::Region& region, uint32_t type, const std::vector<uint8_t>& payload) {
        auto* header = region.buildHeader();
        uint64_t writePos = header->writePos.load();
        uint32_t total = wrx::MESSAGE_HEADER_SIZE + roundUp8(static_cast<uint32_t>(payload.size()));
        if (writePos + total > header->capacity.load()) {
                if (header->readPos.load() != writePos) {
                        return false; // test fixtures keep buffers big enough
                }
                writePos = 0;
                header->writePos.store(0);
        }
        uint8_t* area = region.buildPayload();
        std::memcpy(area + writePos, &type, 4);
        uint32_t size = static_cast<uint32_t>(payload.size());
        std::memcpy(area + writePos + 4, &size, 4);
        std::memcpy(area + writePos + 8, payload.data(), size);
        if (total != 8 + size) {
                std::memset(area + writePos + 8 + size, 0, total - 8 - size);
        }
        header->writePos.store(writePos + total);
        region.control()->buildSeq.fetch_add(1);
        return true;
}

// --- results reader (Java side) ----------------------------------------------

struct ResultReader {
        const uint8_t* data;
        uint32_t size;
        uint32_t pos{0};
        bool overflow{false};

        uint32_t u32() {
                if (pos + 4 > size) {
                        overflow = true;
                        return 0;
                }
                uint32_t v;
                std::memcpy(&v, data + pos, 4);
                pos += 4;
                return v;
        }

        float f32() {
                uint32_t raw = u32();
                float v;
                std::memcpy(&v, &raw, 4);
                return v;
        }

        std::string str() {
                const uint32_t len = u32();
                if (pos + len > size) {
                        overflow = true;
                        return {};
                }
                std::string out(reinterpret_cast<const char*>(data) + pos, len);
                pos += len;
                return out;
        }
};

bool consumeResults(wrx::Region& region, std::vector<ResultReader>& out) {
        auto* header = region.resultsHeader();
        uint64_t readPos = header->readPos.load();
        const uint64_t writePos = header->writePos.load();
        if (readPos > writePos) {
                readPos = 0;
                header->readPos.store(0);
        }
        while (readPos + wrx::MESSAGE_HEADER_SIZE <= writePos) {
                uint8_t* area = region.resultsPayload();
                uint32_t type = 0, size = 0;
                std::memcpy(&type, area + readPos, 4);
                std::memcpy(&size, area + readPos + 4, 4);
                readPos += wrx::MESSAGE_HEADER_SIZE + roundUp8(size);
                header->readPos.store(readPos);
                region.control()->buildResultAck.fetch_add(1);
                if (type == wrx::RES_ERROR) {
                        return false;
                }
                out.push_back(ResultReader{area + readPos - roundUp8(size), size, 0});
        }
        return true;
}

const char* BASIC_OBJ = "# a small document\n"
        "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n"
        "vt 0 0\nvt 1 0\nvt 0 1\n"
        "vn 0 0 1\nvn 0 1 0\n"
        "usemtl body\n"
        "f 1/1/1 2/2/1 3/3/1\n"
        "f 1/1/2 4/1/2 2/2/2\n";

} // namespace

int main() {
        std::printf("wrx: config parsing\n");
        {
                const auto directory = std::filesystem::temp_directory_path() / "wrx-tests-conf";
                std::filesystem::create_directories(directory);
                std::ofstream(directory / wrx::CONFIG_FILE) << "protocol=1\ndirectory=" << directory.string() << "\n";
                bool ok = false;
                const auto paths = wrx::parseConfig((directory / wrx::CONFIG_FILE).string(), ok);
                check(ok, "config parses");
                check(paths.directory == directory.string(), "directory round trips");
                bool ok2 = false;
                wrx::parseConfig((directory / "missing.conf").string(), ok2);
                check(!ok2, "missing config rejected");
        }

        std::printf("wrx: boot, LOAD_OBJ round trip, instance, free\n");
        {
                auto testRegion = createRegion("protocol");
                check(testRegion != nullptr, "region opens");
                if (testRegion != nullptr) {
                        std::ofstream(testRegion->directory / wrx::CONFIG_FILE) << "protocol=1\ndirectory=" << testRegion->directory.string() << "\n";
                        wrx::Server server2;
                        check(server2.boot((testRegion->directory / wrx::CONFIG_FILE).string()), "server boots after config written");

                        // LOAD_OBJ with one MTL
                        MessageWriter w;
                        const uint32_t requestId = 42;
                        w.u32(requestId);
                        w.u32(1 << 0); // splitModel, no flipV
                        w.str(BASIC_OBJ);
                        w.u32(1); // one mtl
                        w.str("body");
                        w.str("newmtl body\nKd 0.5 0.25 1.0\nd 0.75\nmap_Kd textures/body.png\n");
                        check(publishBuild(testRegion->region, wrx::MSG_LOAD_OBJ, w.bytes), "LOAD_OBJ published");

                        const int64_t processed = server2.processBuilds();
                        check(processed == 1, "LOAD_OBJ processed");
                        check(server2.liveModelCount() == 1, "one model stored");

                        std::vector<ResultReader> results;
                        check(consumeResults(testRegion->region, results), "results consumed");
                        check(results.size() == 1, "one result batch");
                        if (!results.empty()) {
                                const uint32_t echoedRequestId = results[0].u32();
                                check(echoedRequestId == requestId, "requestId echoed");
                                const uint32_t groupCount = results[0].u32();
                                check(groupCount >= 1, "group count >= 1");
                                uint32_t modelId = 0;
                                uint32_t meshCount = 0;
                                if (groupCount >= 1) {
                                        const std::string groupName = results[0].str();
                                        check(groupName == "default", "implicit group named default");
                                        modelId = results[0].u32();
                                        meshCount = results[0].u32();
                                }
                                check(meshCount >= 1, "mesh count >= 1");
                                uint32_t meshId = 0;
                                float aabb[6] = {};
                                uint8_t kd[4] = {};
                                std::string name;
                                if (meshCount >= 1) {
                                        meshId = results[0].u32();
                                        results[0].u32(); // vertexCount
                                        results[0].u32(); // indexCount
                                        for (float& v : aabb) {
                                                v = results[0].f32();
                                        }
                                        for (uint8_t& v : kd) {
                                                v = static_cast<uint8_t>(results[0].u32());
                                        }
                                        name = results[0].str();
                                }
                                check(meshId != 0, "mesh id allocated");
                                check(name == "body", "material name round trips");
                                check(aabb[0] <= 0.0F && aabb[3] >= 1.0F, "aabb spans the document");
                                check(kd[0] == 128 || kd[0] == 127, "Kd red quantised"); // 0.5 * 255 ≈ 127/128

                                // INSTANCE with a flip: winding swap + mirrored x/z
                                MessageWriter inst;
                                inst.u32(requestId + 1);
                                inst.u32(modelId);
                                inst.u32(1); // flip
                                inst.f32(1.0F);
                                inst.f32(2.0F);
                                inst.f32(3.0F);
                                check(publishBuild(testRegion->region, wrx::MSG_INSTANCE, inst.bytes), "INSTANCE published");
                                check(server2.processBuilds() == 1, "INSTANCE processed");

                                std::vector<ResultReader> instanceResults;
                                check(consumeResults(testRegion->region, instanceResults), "instance result consumed");
                                check(instanceResults.size() == 1, "instance batch present");
                                uint32_t instanceModelId = 0;
                                float instanceAabb[6] = {};
                                if (!instanceResults.empty()) {
                                        instanceResults[0].u32(); // requestId echo
                                        instanceModelId = instanceResults[0].u32();
                                        const uint32_t instanceMeshCount = instanceResults[0].u32();
                                        check(instanceMeshCount == meshCount, "instance mesh count matches");
                                        if (instanceMeshCount >= 1) {
                                                instanceResults[0].u32(); // meshId
                                                for (float& v : instanceAabb) {
                                                        v = instanceResults[0].f32();
                                                }
                                        }
                                }
                                check(instanceModelId != modelId && instanceModelId != 0, "instance model allocated");
                                check(instanceAabb[0] >= 0.0F && instanceAabb[3] <= 1.0F + 1e-2F, "flipped aabb mirrored around dx");

                                // FREE both models
                                MessageWriter freeMsg;
                                freeMsg.u32(requestId + 2);
                                freeMsg.u32(2);
                                freeMsg.u32(modelId);
                                freeMsg.u32(instanceModelId);
                                check(publishBuild(testRegion->region, wrx::MSG_FREE_MODELS, freeMsg.bytes), "FREE published");
                                check(server2.processBuilds() == 1, "FREE processed");
                                check(server2.liveModelCount() == 0, "models freed");
                                check(server2.liveMeshCount() == 0, "mesh index drained (no leak)");

                                std::vector<ResultReader> freeResults;
                                consumeResults(testRegion->region, freeResults);
                                check(freeResults.size() == 1, "free ack present");
                        }
                        server2.shutdown();
                        check(server2.liveMeshCount() == 0, "shutdown clean");
                }
        }

        std::printf("wrx: frame slot seqlock and ack accounting\n");
        {
                auto testRegion = createRegion("frame");
                check(testRegion != nullptr, "frame region opens");
                if (testRegion != nullptr) {
                        std::ofstream(testRegion->directory / wrx::CONFIG_FILE) << "protocol=1\ndirectory=" << testRegion->directory.string() << "\n";
                        wrx::Server server;
                        check(server.boot((testRegion->directory / wrx::CONFIG_FILE).string()), "frame server boots");

                        // frame with zero items: processFrame must ack even without a recorder
                        uint8_t* frame = testRegion->region.framePayload();
                        uint64_t seq = 0;
                        std::memcpy(frame, &seq, 8);
                        auto* header = reinterpret_cast<wrx::FrameHeader*>(frame + 8);
                        header->magic = wrx::FRAME_MAGIC;
                        header->itemCount = 0;
                        header->textureCount = 0;
                        seq |= 1;
                        std::memcpy(frame, &seq, 8);
                        seq &= ~1ULL;
                        std::memcpy(frame, &seq, 8);
                        testRegion->region.control()->frameSeq.fetch_add(1);
                        server.processFrame();
                        check(testRegion->region.control()->frameAck.load() == 1, "empty frame acked");

                        // no recorder: an item-bearing frame counts an error but never wedges
                        header->itemCount = 1;
                        seq |= 1;
                        std::memcpy(frame, &seq, 8);
                        seq &= ~1ULL;
                        std::memcpy(frame, &seq, 8);
                        testRegion->region.control()->frameSeq.fetch_add(1);
                        server.processFrame();
                        check(testRegion->region.control()->frameAck.load() == 2, "item frame acked");
                        check(testRegion->region.control()->frameErrorCount.load() >= 1, "error counted without recorder");

                        // corrupt magic: also acked, not wedged
                        header->magic = 0xDEADBEEF;
                        testRegion->region.control()->frameSeq.fetch_add(1);
                        server.processFrame();
                        check(testRegion->region.control()->frameAck.load() == 3, "corrupt frame acked");
                        server.shutdown();
                }
        }

        std::printf("\n%d checks, %d failure(s)\n", g_checks, g_failures);
        return g_failures == 0 ? 0 : 1;
}
