// SPDX-License-Identifier: MIT
//
// wrvk_tests — command-stream and leak assertions against RecordingDispatch:
// the exact draw stream wrvk would hand a real driver, verified without a
// device. Also exercises the wrmain C-ABI surface and the loader's negative
// paths (no libvulkan present in the sandbox is itself the test).

#include "wrx/wrx.hpp"
#include "wrvk/wrvk.hpp"

#include <cstdio>
#include <cstring>
#include <vector>

// wrmain C ABI (defined in src/wrmain.cpp)
extern "C" {
int32_t wr_abi_version();
int64_t wr_status();
int32_t wr_submit_builds();
int32_t wr_submit_frame();
int32_t wr_shutdown();
const char* wr_version_string();
}

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

// 16 bytes is the minimum SPIR-V header; anything works for the stub.
std::vector<uint8_t> fakeShader(uint32_t tag) {
        std::vector<uint8_t> out(64);
        std::memcpy(out.data(), &tag, 4);
        return out;
}

std::vector<uint8_t> shaderBlob(const std::vector<uint8_t>& vert, const std::vector<uint8_t>& frag) {
        std::vector<uint8_t> out;
        const uint32_t vertSize = static_cast<uint32_t>(vert.size());
        out.insert(out.end(), reinterpret_cast<const uint8_t*>(&vertSize), reinterpret_cast<const uint8_t*>(&vertSize) + 4);
        out.insert(out.end(), vert.begin(), vert.end());
        out.insert(out.end(), frag.begin(), frag.end());
        return out;
}

wrx::HostHandles fakeHost() {
        wrx::HostHandles host{};
        host.instance = 0x1111;
        host.physicalDevice = 0x2222;
        host.device = 0x3333;
        host.queue = 0x4444;
        host.queueFamily = 0;
        host.renderPass = 0x5555;
        host.colorFormat = 37; // B8G8R8A8_UNORM
        host.depthFormat = 130; // D24_UNORM_S8_UINT-ish
        host.sampleCount = 1;
        host.maxPushConstants = 128;
        host.valid = true;
        return host;
}

wrx::FrameTexture fakeTexture(uint64_t id) {
        wrx::FrameTexture texture{};
        texture.image = id;
        texture.view = id * 10;
        texture.sampler = id * 100;
        texture.format = 43; // R8G8B8A8_UNORM
        texture.mipLevels = 4;
        texture.layout = 5;  // SHADER_READ_ONLY_OPTIMAL
        return texture;
}

} // namespace

int main() {
        std::printf("wrvk: recorder lifecycle and pipeline creation\n");
        {
                wrvk::RecordingDispatch dispatch;
                wrvk::WrvkRecorder recorder(dispatch);
                check(!recorder.hasHost(), "no host before attach");
                check(!recorder.recordFrame(wrx::FrameHeader{}, nullptr, nullptr), "record rejected before attach");
                recorder.attachHost(fakeHost());
                check(recorder.hasHost(), "host attached");
                check(dispatch.liveBuffers == 1, "UBO buffer created"); // one buffer for the UBO ring
                check(dispatch.liveDescriptorPools >= 1 + 6, "ubo pool + frame pools created");
                check(dispatch.liveDescriptorSetLayouts == 2, "two set layouts");
                check(dispatch.livePipelineLayouts == 1, "one pipeline layout");

                // shaders must all be present before pipelines build
                recorder.uploadShader(0, shaderBlob(fakeShader(0xA0), fakeShader(0xA1)).data(),
                        static_cast<uint32_t>(shaderBlob(fakeShader(0xA0), fakeShader(0xA1)).size()));
                recorder.uploadShader(1, shaderBlob(fakeShader(0xB0), fakeShader(0xB1)).data(),
                        static_cast<uint32_t>(shaderBlob(fakeShader(0xB0), fakeShader(0xB1)).size()));
                recorder.uploadShader(2, shaderBlob(fakeShader(0xC0), fakeShader(0xC1)).data(),
                        static_cast<uint32_t>(shaderBlob(fakeShader(0xC0), fakeShader(0xC1)).size()));

                wrx::FrameHeader header{};
                header.magic = wrx::FRAME_MAGIC;
                header.itemCount = 2;
                header.textureCount = 1;
                header.viewportWidth = 1920;
                header.viewportHeight = 1080;
                header.cmdBuffer = 0x9001;
                header.renderPass = 0x5555;
                for (float& v : header.projection) {
                        v = 1.0F;
                }
                header.fogStart = 100.0F;
                header.fogEnd = 200.0F;

                // one mesh, two items sharing it through two textures
                wrx::Server::GpuMesh mesh;
                mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                mesh.indices = {0, 1, 2};
                recorder.uploadMesh(7, mesh);
                check(recorder.liveGpuMeshCount() == 1, "mesh uploaded");
                check(dispatch.liveBuffers == 2, "mesh buffer allocated");

                std::vector<wrx::FrameTexture> textures{fakeTexture(1)};
                wrx::FrameItem items[2]{};
                items[0].meshId = 7;
                items[0].textureIndex = 0;
                items[0].stage = 2; // INTERIOR -> cutout
                items[0].colorPacked = 0xFFFFFFFF;
                items[1].meshId = 7;
                items[1].textureIndex = 0;
                items[1].stage = 3; // INTERIOR_TRANSLUCENT
                items[1].colorPacked = 0x80808080;
                for (int i = 0; i < 2; i++) {
                        items[i].modelView[0] = 1.0F; // identifiable MV sentinel
                        items[i].normalMatrix[0] = 1.0F;
                }

                const bool recorded = recorder.recordFrame(header,
                        reinterpret_cast<const uint8_t*>(textures.data()),
                        reinterpret_cast<const uint8_t*>(items));
                check(recorded, "frame recorded");
                check(dispatch.livePipelines == 5, "five pipelines for the five stages");
                check(dispatch.drawCalls == 2, "two draws recorded");
                check(dispatch.pushConstants.size() == 2, "two push constant writes");
                if (dispatch.pushConstants.size() == 2) {
                        check(dispatch.pushConstants[0][0] == 1.0F, "white colour converted");
                        check(dispatch.pushConstants[1][0] > 0.5F && dispatch.pushConstants[1][0] < 0.51F, "grey colour converted");
                        check(dispatch.pushConstants[0][4] == 1.0F, "mv matrix starts after colour");
                        check(dispatch.pushConstants[0][20] == 1.0F, "normal matrix follows mv");
                }
                check(dispatch.lastViewport[2] == 1920.0F, "viewport width");
                check(dispatch.lastViewport[3] == -1080.0F, "viewport y flipped (GL convention)");
                check(dispatch.lastScissor[2] == 1920, "scissor width");

                // two stages -> two distinct pipeline binds
                size_t pipelineBinds = 0;
                for (const auto& entry : dispatch.log) {
                        if (entry.op == wrvk::RecordingDispatch::Op::CmdBindPipeline) {
                                pipelineBinds++;
                        }
                }
                check(pipelineBinds == 2, "pipeline rebind per stage");

                // render-pass change retires and rebuilds all pipelines
                header.renderPass = 0x6666;
                const bool reRecorded = recorder.recordFrame(header,
                        reinterpret_cast<const uint8_t*>(textures.data()),
                        reinterpret_cast<const uint8_t*>(items));
                check(reRecorded, "frame recorded after pass change");
                check(dispatch.livePipelines == 5, "pipelines rebuilt, none leaked");

                // free the mesh: buffers and memory return to zero
                recorder.freeMesh(7);
                check(recorder.liveGpuMeshCount() == 0, "mesh freed");
                check(dispatch.liveBuffers == 1, "only the UBO buffer remains");
                check(dispatch.liveMemory == 1, "only the UBO memory remains");

                // identity alias: two mesh ids share one GPU allocation
                recorder.uploadMesh(10, mesh);
                recorder.uploadMesh(11, mesh);
                check(dispatch.liveBuffers == 2, "alias shares one buffer");
                recorder.freeMesh(10);
                check(dispatch.liveBuffers == 2, "alias survives one free");
                recorder.freeMesh(11);
                check(dispatch.liveBuffers == 1, "alias freed at last reference");
        }

        std::printf("wrvk: allocation failure paths never corrupt state\n");
        {
                wrvk::RecordingDispatch dispatch;
                wrvk::WrvkRecorder recorder(dispatch);
                recorder.attachHost(fakeHost());
                dispatch.failAllocation = true;
                wrx::Server::GpuMesh mesh;
                mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                mesh.indices = {0, 1, 2};
                check(!recorder.uploadMesh(1, mesh), "upload fails cleanly");
                check(recorder.liveGpuMeshCount() == 0, "no mesh tracked on failure");
                check(dispatch.liveBuffers == 1, "no buffer leaked on failure");
        }

        std::printf("wrvk: teardown leaks nothing\n");
        {
                size_t buffersBefore = 0;
                {
                        wrvk::RecordingDispatch dispatch;
                        wrvk::WrvkRecorder recorder(dispatch);
                        recorder.attachHost(fakeHost());
                        buffersBefore = dispatch.liveBuffers;
                }
                check(buffersBefore > 0, "objects existed before teardown");
                // the dispatch outlives the recorder in this scope, so its counters
                // after the recorder destructor prove every object was destroyed
        }

        std::printf("wrvk: real dispatch negative path\n");
        {
                // A null instance resolves only global functions, so the dispatch
                // comes back null; a bogus non-null handle would legitimately be
                // dereferenced by the loader, so it must never be passed.
                auto dispatch = wrvk::createRealDispatch("", 0);
                check(dispatch == nullptr, "null instance yields no dispatch");
        }

        std::printf("wrvk: wrmain C ABI smoke\n");
        {
                check(wr_abi_version() == static_cast<int32_t>(wrx::PROTOCOL_VERSION), "abi version");
                check(std::strcmp(wr_version_string(), "wrvk 1.0.0 (native Vulkan OBJ pipeline)") == 0, "version string");
                check(wr_submit_builds() == -1, "builds without boot rejected");
                check(wr_submit_frame() == -1, "frame without boot rejected");
                check(wr_status() == -1, "status without boot is -1");
                check(wr_shutdown() == 0, "shutdown without boot is a no-op");
        }

        std::printf("\n%d checks, %d failure(s)\n", g_checks, g_failures);
        return g_failures == 0 ? 0 : 1;
}
