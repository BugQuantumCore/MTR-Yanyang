// SPDX-License-Identifier: MIT
//
// wrvk_tests — command-stream and leak assertions against RecordingDispatch:
// the exact draw stream wrvk would hand a real driver, verified without a
// device. Protocol v2: the native texture pool (image/view/sampler
// lifecycles + the frame-time upload flush), the native rendering math
// (model-view, inverse-transpose normal matrix, Kd×brightness colour) and
// the per-frame external texture overrides. Also exercises the wrmain C-ABI
// surface and the loader's negative paths (no libvulkan present in the
// sandbox is itself the test).

#include "wrx/wrx.hpp"
#include "wrvk/wrvk.hpp"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

#include "vulkan/vulkan.h"

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

bool nearF(float a, float b) {
        return std::fabs(a - b) < 1e-6F;
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

void identity16(float* m) {
        std::memset(m, 0, sizeof(float) * 16);
        m[0] = m[5] = m[10] = m[15] = 1.0F;
}

wrx::FrameHeader basicHeader() {
        wrx::FrameHeader header{};
        header.magic = wrx::FRAME_MAGIC;
        header.itemCount = 0;
        header.externalTextureCount = 0;
        identity16(header.view);
        identity16(header.projection);
        header.viewportWidth = 1920;
        header.viewportHeight = 1080;
        header.cmdBuffer = 0x9001;
        header.renderPass = 0x5555;
        header.fogStart = 100.0F;
        header.fogEnd = 200.0F;
        return header;
}

wrx::FrameItem makeItem(uint32_t meshId, uint32_t textureKey, uint32_t stage, uint32_t light) {
        wrx::FrameItem item{};
        item.meshId = meshId;
        item.textureKey = textureKey;
        item.stage = stage;
        item.light = light;
        identity16(item.pose);
        return item;
}

// dispatch + recorder + the three shader pairs, the common test setup
struct TestEnv {
        wrvk::RecordingDispatch dispatch;
        std::unique_ptr<wrvk::WrvkRecorder> recorder;

        TestEnv() {
                recorder.reset(new wrvk::WrvkRecorder(dispatch));
                recorder->attachHost(fakeHost());
                for (uint32_t key = 0; key < 3; key++) {
                        std::vector<uint8_t> blob = shaderBlob(fakeShader(0xA0 + key), fakeShader(0xB0 + key));
                        recorder->uploadShader(key, blob.data(), static_cast<uint32_t>(blob.size()));
                }
        }
};

size_t countOps(const wrvk::RecordingDispatch& dispatch, wrvk::RecordingDispatch::Op op) {
        size_t count = 0;
        for (const auto& entry : dispatch.log) {
                if (entry.op == op) {
                        count++;
                }
        }
        return count;
}

size_t firstOpIndex(const wrvk::RecordingDispatch& dispatch, wrvk::RecordingDispatch::Op op) {
        for (size_t i = 0; i < dispatch.log.size(); i++) {
                if (dispatch.log[i].op == op) {
                        return i;
                }
        }
        return dispatch.log.size();
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

                wrx::FrameHeader header = basicHeader();
                header.itemCount = 2;
                for (float& v : header.projection) {
                        v = 1.0F;
                }

                // two meshes (white + grey Kd — distinct objects, so two GPU
                // allocations), one pool texture shared by both items
                wrx::GpuMesh meshA;
                meshA.vertices.resize(3 * wrx::VERTEX_BYTES);
                meshA.indices = {0, 1, 2};
                wrx::GpuMesh meshB = meshA;
                const uint8_t kdWhite[4] = {255, 255, 255, 255};
                const uint8_t kdGrey[4] = {128, 128, 128, 255};
                recorder.uploadMesh(7, meshA, kdWhite);
                recorder.uploadMesh(8, meshB, kdGrey);
                check(recorder.liveGpuMeshCount() == 2, "meshes uploaded");
                check(dispatch.liveBuffers == 3, "mesh buffers allocated"); // UBO + one per mesh

                uint8_t pixels[64];
                for (uint32_t i = 0; i < 64; i++) {
                        pixels[i] = static_cast<uint8_t>(i);
                }
                check(recorder.uploadTexture(1, 4, 4, pixels, sizeof(pixels)), "texture uploaded");
                check(recorder.liveTextureCount() == 1, "one pool texture live");

                wrx::FrameItem items[2]{};
                items[0] = makeItem(7, 1, 2, 0); // INTERIOR -> cutout, full-bright
                items[1] = makeItem(8, 1, 3, 0); // INTERIOR_TRANSLUCENT, full-bright

                const bool recorded = recorder.recordFrame(header,
                        nullptr,
                        reinterpret_cast<const uint8_t*>(items));
                check(recorded, "frame recorded");
                check(dispatch.livePipelines == 5, "five pipelines for the five stages");
                check(dispatch.drawCalls == 2, "two draws recorded");
                check(dispatch.pushConstants.size() == 2, "two push constant writes");
                if (dispatch.pushConstants.size() == 2) {
                        const auto& push0 = dispatch.pushConstants[0];
                        const auto& push1 = dispatch.pushConstants[1];
                        check(push0[0] == 1.0F && push0[1] == 1.0F && push0[2] == 1.0F && push0[3] == 1.0F,
                                "white Kd full-bright converted");
                        check(push1[0] > 0.5F && push1[0] < 0.51F, "grey Kd converted");
                        bool mvIdentity = true;
                        for (uint32_t i = 0; i < 16; i++) {
                                mvIdentity = mvIdentity && nearF(push0[4 + i], i % 5 == 0 ? 1.0F : 0.0F);
                        }
                        check(mvIdentity, "mv = view × pose (identity)");
                        const float nExpected[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
                        bool nIdentity = true;
                        for (uint32_t i = 0; i < 12; i++) {
                                nIdentity = nIdentity && nearF(push0[20 + i], nExpected[i]);
                        }
                        check(nIdentity, "normal matrix = inverse-transpose (identity)");
                }

                // the pending texture upload flushed on this frame: barrier pair + copy
                check(dispatch.bufferImageCopies.size() == 1, "pool upload flushed on the frame");
                check(dispatch.imageBarriers.size() == 2, "barrier pair around the copy");
                check(dispatch.descriptorImageInfos.size() == 2, "one descriptor write per item");
                if (dispatch.descriptorImageInfos.size() == 2) {
                        check(dispatch.descriptorImageInfos[0].view != 0 && dispatch.descriptorImageInfos[0].sampler != 0,
                                "descriptor uses the pool view + sampler");
                        check(dispatch.descriptorImageInfos[0].view == dispatch.descriptorImageInfos[1].view,
                                "shared pool texture binds the same view");
                }
                check(firstOpIndex(dispatch, wrvk::RecordingDispatch::Op::CmdCopyBufferToImage)
                                < firstOpIndex(dispatch, wrvk::RecordingDispatch::Op::CmdSetViewport),
                        "upload copy recorded before the draws");

                check(dispatch.lastViewport[2] == 1920.0F, "viewport width");
                check(dispatch.lastViewport[3] == -1080.0F, "viewport y flipped (GL convention)");
                check(dispatch.lastScissor[2] == 1920, "scissor width");

                // two stages -> two distinct pipeline binds
                size_t pipelineBinds = countOps(dispatch, wrvk::RecordingDispatch::Op::CmdBindPipeline);
                check(pipelineBinds == 2, "pipeline rebind per stage");

                // render-pass change retires and rebuilds all pipelines
                header.renderPass = 0x6666;
                const bool reRecorded = recorder.recordFrame(header,
                        nullptr,
                        reinterpret_cast<const uint8_t*>(items));
                check(reRecorded, "frame recorded after pass change");
                check(dispatch.livePipelines == 5, "pipelines rebuilt, none leaked");
                check(dispatch.bufferImageCopies.size() == 1, "no second upload without a new pending");

                // free everything: buffers, memories and image objects return to zero
                recorder.freeMesh(7);
                recorder.freeMesh(8);
                recorder.freeTexture(1);
                check(recorder.liveGpuMeshCount() == 0, "meshes freed");
                check(recorder.liveTextureCount() == 0, "texture freed");
                check(dispatch.liveBuffers == 1, "only the UBO buffer remains");
                check(dispatch.liveMemory == 1, "only the UBO memory remains");
                check(dispatch.liveImages == 0, "no images remain");
                check(dispatch.liveImageViews == 0, "no image views remain");
                check(dispatch.liveSamplers == 0, "no samplers remain");

                // identity alias: two mesh ids share one GPU allocation
                recorder.uploadMesh(10, meshA, kdWhite);
                recorder.uploadMesh(11, meshA, kdWhite);
                check(dispatch.liveBuffers == 2, "alias shares one buffer");
                recorder.freeMesh(10);
                check(dispatch.liveBuffers == 2, "alias survives one free");
                recorder.freeMesh(11);
                check(dispatch.liveBuffers == 1, "alias freed at last reference");
        }

        std::printf("wrvk: native rendering math (JOML parity)\n");
        {
                float id[16];
                identity16(id);
                float out[16];

                wrvk::mat4Mul(id, id, out);
                bool identityProduct = true;
                for (uint32_t i = 0; i < 16; i++) {
                        identityProduct = identityProduct && nearF(out[i], i % 5 == 0 ? 1.0F : 0.0F);
                }
                check(identityProduct, "mat4Mul identity");

                // view = Rx(90°), pose = T(2,4,6): hand-composed view × pose.
                // Rx(90) maps y→z, z→−y, so the translation rotates to (2,−6,4).
                const float view[16] = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1};
                const float pose[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 4, 6, 1};
                const float expected[16] = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 2, -6, 4, 1};
                wrvk::mat4Mul(view, pose, out);
                bool composed = true;
                for (uint32_t i = 0; i < 16; i++) {
                        composed = composed && nearF(out[i], expected[i]);
                }
                check(composed, "mat4Mul rotation × translation (hand-computed)");
                wrvk::mat4Mul(pose, view, out);
                check(nearF(out[13], 4.0F), "mat4Mul is order-sensitive (pose × view differs)");

                float n[12];
                const float nIdentity[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
                wrvk::normalMatrixFromMv(id, n);
                bool nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nIdentity[i]);
                }
                check(nOk, "normal matrix of the identity");

                // rotation: N of Rx(90) is Rx(90) again
                const float nRx[12] = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0};
                wrvk::normalMatrixFromMv(expected, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nRx[i]);
                }
                check(nOk, "normal matrix of a rotation (inverse-transpose = same rotation)");

                // mirror (negative determinant): the mirror survives in N
                const float mirror[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
                const float nMirror[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0};
                wrvk::normalMatrixFromMv(mirror, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nMirror[i]);
                }
                check(nOk, "normal matrix of a mirror keeps the flipped axis");

                // non-uniform scale: N = diag(1/2, 1/3, 1/4) for diag(2, 3, 4)
                const float scale[16] = {2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 0, 0, 0, 1};
                const float nScale[12] = {0.5F, 0, 0, 0, 0, 1.0F / 3.0F, 0, 0, 0, 0, 0.25F, 0};
                wrvk::normalMatrixFromMv(scale, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nScale[i]);
                }
                check(nOk, "normal matrix of a non-uniform scale (JOML inverse-transpose)");

                // non-uniform scale ∘ rotation: upper 3×3 = [[0,-1,0],[2,0,0],[0,0,1]]
                // (S=diag(1,2,1) applied after Rz(90)); cofactors hand-computed:
                //   N = [[0,-1,0],[1/2,0,0],[0,0,1]] -> columns (0,1/2,0)(-1,0,0)(0,0,1)
                const float sr[16] = {0, 2, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                const float nSr[12] = {0, 0.5F, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0};
                wrvk::normalMatrixFromMv(sr, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nSr[i]);
                }
                check(nOk, "normal matrix of scale ∘ rotation (hand-computed cofactors)");

                // singular 3×3 (zero scale) degrades to identity instead of NaNs
                const float singular[16] = {0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                wrvk::normalMatrixFromMv(singular, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], nIdentity[i]);
                }
                check(nOk, "singular model-view yields the identity normal matrix");

                // fully general composition (rotation × translation × scale ×
                // rotation) — expected values verified against real JOML 1.10.5
                // (new Matrix4f().set(view).mul(pose) / Matrix3f().set(mv)
                // .invert().transpose().get()) in a differential run
                const float gView[16] = {1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 2, -6, 4, 1}; // Rx(90)·T(2,4,6)
                const float gPose[16] = {0, 2, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};   // S(1,2,1)·Rz(90)
                const float gMv[16] = {0, 0, 2, 0, -1, 0, 0, 0, 0, -1, 0, 0, 2, -6, 4, 1};
                const float gN[12] = {0, 0, 0.5F, 0, -1, 0, 0, 0, 0, -1, 0, 0};
                float gOutMv[16];
                wrvk::mat4Mul(gView, gPose, gOutMv);
                bool gOk = true;
                for (uint32_t i = 0; i < 16; i++) {
                        gOk = gOk && nearF(gOutMv[i], gMv[i]);
                }
                check(gOk, "general view × pose matches JOML");
                wrvk::normalMatrixFromMv(gOutMv, n);
                nOk = true;
                for (uint32_t i = 0; i < 12; i++) {
                        nOk = nOk && nearF(n[i], gN[i]);
                }
                check(nOk, "general normal matrix matches JOML");
        }

        std::printf("wrvk: brightness and Kd colour semantics\n");
        {
                check(nearF(wrvk::brightnessForStage(0, 0), 1.0F), "LIGHT is full-bright");
                check(nearF(wrvk::brightnessForStage(1, 200), 1.0F), "ALWAYS_ON_LIGHT is full-bright");
                check(nearF(wrvk::brightnessForStage(2, 0), 1.0F), "INTERIOR is full-bright");
                check(nearF(wrvk::brightnessForStage(3, 0xF0), 1.0F), "INTERIOR_TRANSLUCENT is full-bright");
                check(nearF(wrvk::brightnessForStage(4, 240), 16.0F), "EXTERIOR divides the raw packed light (240/15)");
                check(nearF(wrvk::brightnessForStage(4, 0), 0.0F), "EXTERIOR with zero light");
                check(nearF(wrvk::brightnessForStage(4, 15), 1.0F), "EXTERIOR with full block light");

                TestEnv env;
                wrx::GpuMesh mesh;
                mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                mesh.indices = {0, 1, 2};
                const uint8_t kd[4] = {200, 128, 100, 255};
                check(env.recorder->uploadMesh(20, mesh, kd), "mesh uploaded with Kd");
                uint8_t pixels[64];
                std::memset(pixels, 0xAB, sizeof(pixels));
                check(env.recorder->uploadTexture(30, 4, 4, pixels, sizeof(pixels)), "texture uploaded");

                auto recordOne = [&](uint32_t stage, uint32_t light) -> const std::array<float, 32>& {
                        env.dispatch.pushConstants.clear();
                        wrx::FrameHeader header = basicHeader();
                        header.itemCount = 1;
                        const wrx::FrameItem item = makeItem(20, 30, stage, light);
                        check(env.recorder->recordFrame(header, nullptr, reinterpret_cast<const uint8_t*>(&item)), "frame recorded");
                        return env.dispatch.pushConstants.back();
                };

                // EXTERIOR, light = 240 (0x00F0) -> brightness 16.0 -> channels clamp to 255
                {
                        const auto& push = recordOne(4, 240);
                        check(nearF(push[0], 1.0F) && nearF(push[1], 1.0F) && nearF(push[2], 1.0F) && nearF(push[3], 1.0F),
                                "brightness 16 clamps every channel to full");
                        // single-item end-to-end: exactly one draw, and the push
                        // constants carry the natively computed identity MV
                        check(env.dispatch.drawCalls == 1, "one item records exactly one draw");
                        bool mvIdentity = true;
                        for (uint32_t i = 0; i < 16; i++) {
                                mvIdentity = mvIdentity && nearF(push[4 + i], i % 5 == 0 ? 1.0F : 0.0F);
                        }
                        check(mvIdentity, "single-item push constants carry the identity MV");
                }
                // EXTERIOR, light = 0 -> every channel (incl. alpha) is zero
                {
                        const auto& push = recordOne(4, 0);
                        check(push[0] == 0.0F && push[1] == 0.0F && push[2] == 0.0F && push[3] == 0.0F,
                                "zero light zeroes the colour");
                }
                // EXTERIOR, light = 8 -> brightness 8/15, Java Math.round per channel
                {
                        const auto& push = recordOne(4, 8);
                        check(nearF(push[0], 107.0F / 255.0F), "red channel rounds like Math.round");
                        check(nearF(push[1], 68.0F / 255.0F), "green channel rounds like Math.round");
                        check(nearF(push[2], 53.0F / 255.0F), "blue channel rounds like Math.round");
                        check(nearF(push[3], 136.0F / 255.0F), "alpha follows the same rounding");
                }
                // full-bright stages ignore the raw light entirely
                {
                        const auto& push = recordOne(0, 0);
                        check(nearF(push[0], 200.0F / 255.0F) && nearF(push[1], 128.0F / 255.0F)
                                        && nearF(push[2], 100.0F / 255.0F) && nearF(push[3], 1.0F),
                                "full-bright stage ignores light");
                }
        }

        std::printf("wrvk: native texture pool upload, dedup and re-upload\n");
        {
                TestEnv env;
                uint8_t pixels4[64];
                uint8_t pixels4b[64];
                uint8_t pixels8[256];
                for (uint32_t i = 0; i < 64; i++) {
                        pixels4[i] = static_cast<uint8_t>(i);
                        pixels4b[i] = static_cast<uint8_t>(0xFF - i);
                }
                for (uint32_t i = 0; i < 256; i++) {
                        pixels8[i] = static_cast<uint8_t>(i * 3);
                }

                check(env.recorder->uploadTexture(42, 4, 4, pixels4, sizeof(pixels4)), "texture accepted");
                check(env.recorder->liveTextureCount() == 1, "one live texture");
                check(env.dispatch.liveImages == 1, "one image");
                check(env.dispatch.liveImageViews == 1, "one image view");
                check(env.dispatch.liveSamplers == 1, "one sampler");
                check(env.dispatch.liveBuffers == 2, "UBO + staging buffers");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::CreateImage) == 1, "CreateImage logged");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::CreateImageView) == 1, "CreateImageView logged");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::CreateSampler) == 1, "CreateSampler logged");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::MapMemory) == 2, "UBO + staging mapped");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::BindImageMemory) == 1, "image memory bound");

                // the image is an RGBA8 optimal-tiling transfer target
                if (!env.dispatch.images.empty()) {
                        const auto& image = env.dispatch.images.begin()->second;
                        check(image.width == 4 && image.height == 4, "image extent follows the upload");
                        check(image.format == VK_FORMAT_R8G8B8A8_UNORM, "image is RGBA8_UNORM");
                        check(image.tiling == VK_IMAGE_TILING_OPTIMAL, "image tiling optimal");
                        check(image.usage == (VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT), "image usage transfer-dst + sampled");
                }
                // the sampler tiles (MTR textures repeat) with linear filtering
                if (!env.dispatch.samplers.empty()) {
                        const auto& sampler = env.dispatch.samplers.begin()->second;
                        check(sampler.addressModeU == VK_SAMPLER_ADDRESS_MODE_REPEAT
                                        && sampler.addressModeV == VK_SAMPLER_ADDRESS_MODE_REPEAT
                                        && sampler.addressModeW == VK_SAMPLER_ADDRESS_MODE_REPEAT,
                                "sampler address mode repeats");
                        check(sampler.magFilter == VK_FILTER_LINEAR && sampler.minFilter == VK_FILTER_LINEAR, "sampler filters linear");
                }
                // the pixels landed in the mapped staging buffer
                uint64_t staging = 0;
                for (const auto& entry : env.dispatch.buffers) {
                        if (entry.second.size == 64) {
                                staging = entry.first;
                        }
                }
                check(staging != 0, "staging buffer found");
                if (staging != 0) {
                        check(env.dispatch.mappedData(staging) != nullptr
                                        && std::memcmp(env.dispatch.mappedData(staging), pixels4, 64) == 0,
                                "pixels copied into the staging buffer");
                }

                // second upload of the same key before any frame: dedup keeps one copy
                check(env.recorder->uploadTexture(42, 4, 4, pixels4b, sizeof(pixels4b)), "re-upload accepted");
                check(env.dispatch.liveImages == 1, "same dimensions reuse the image");
                check(countOps(env.dispatch, wrvk::RecordingDispatch::Op::CreateImage) == 1, "no second image");

                // zero-item frame: the flush still runs (frame ack happens)
                wrx::FrameHeader header = basicHeader();
                const bool flushed = env.recorder->recordFrame(header, nullptr, nullptr);
                check(flushed, "zero-item frame acked");
                check(env.dispatch.bufferImageCopies.size() == 1, "dedup: one copy for two uploads");
                check(env.dispatch.imageBarriers.size() == 2, "barrier pair around the copy");
                if (env.dispatch.imageBarriers.size() == 2) {
                        check(env.dispatch.imageBarriers[0].oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                                        && env.dispatch.imageBarriers[0].newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                "first upload: UNDEFINED -> TRANSFER_DST");
                        check(env.dispatch.imageBarriers[1].oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                        && env.dispatch.imageBarriers[1].newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                "after copy: TRANSFER_DST -> SHADER_READ_ONLY");
                }
                if (env.dispatch.bufferImageCopies.size() == 1) {
                        check(env.dispatch.bufferImageCopies[0].width == 4
                                        && env.dispatch.bufferImageCopies[0].height == 4,
                                "copy covers the whole image");
                        check(staging == 0 || env.dispatch.bufferImageCopies[0].srcBuffer == staging,
                                "copy source is the staging buffer");
                }
                if (staging != 0 && env.dispatch.mappedData(staging) != nullptr) {
                        check(std::memcmp(env.dispatch.mappedData(staging), pixels4b, 64) == 0,
                                "staging holds the latest upload");
                }

                // nothing pending -> the next frame records no further copies
                check(env.recorder->recordFrame(header, nullptr, nullptr), "frame acked again");
                check(env.dispatch.bufferImageCopies.size() == 1, "no copy without a pending upload");

                // re-upload after the flush: the barrier starts from SHADER_READ_ONLY
                check(env.recorder->uploadTexture(42, 4, 4, pixels4, sizeof(pixels4)), "re-upload after flush");
                check(env.recorder->recordFrame(header, nullptr, nullptr), "frame acked (re-upload)");
                check(env.dispatch.bufferImageCopies.size() == 2, "second copy recorded");
                check(env.dispatch.imageBarriers.size() == 4, "second barrier pair");
                if (env.dispatch.imageBarriers.size() == 4) {
                        check(env.dispatch.imageBarriers[2].oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                        && env.dispatch.imageBarriers[2].newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                "re-upload: SHADER_READ_ONLY -> TRANSFER_DST");
                }

                // dimension change: the image is recreated (fresh image starts UNDEFINED)
                check(env.recorder->uploadTexture(42, 8, 8, pixels8, sizeof(pixels8)), "dimension change accepted");
                check(env.dispatch.liveImages == 1, "old image destroyed, one live");
                check(env.dispatch.liveBuffers == 2, "old staging replaced");
                check(env.recorder->recordFrame(header, nullptr, nullptr), "frame acked (dimension change)");
                check(env.dispatch.bufferImageCopies.size() == 3, "third copy recorded");
                if (env.dispatch.imageBarriers.size() == 6) {
                        check(env.dispatch.imageBarriers[4].oldLayout == VK_IMAGE_LAYOUT_UNDEFINED,
                                "fresh image starts UNDEFINED");
                        check(env.dispatch.bufferImageCopies[2].width == 8
                                        && env.dispatch.bufferImageCopies[2].height == 8,
                                "copy extent follows the new dimensions");
                }

                // validation rejections
                check(!env.recorder->uploadTexture(42, 4, 4, pixels4, 63), "byte count mismatch rejected");
                check(!env.recorder->uploadTexture(42, 0, 4, pixels4, 0), "zero width rejected");
                check(!env.recorder->uploadTexture(42, 4, 0, pixels4, 0), "zero height rejected");
                check(!env.recorder->uploadTexture(42, 16385, 1, pixels4, 4), "oversize dimension rejected");
                check(!env.recorder->uploadTexture(43, 4, 4, nullptr, 64), "null pixels rejected");
                check(env.recorder->liveTextureCount() == 1, "rejections leave the pool intact");

                // free: everything the texture owns returns to zero
                env.recorder->freeTexture(42);
                check(env.recorder->liveTextureCount() == 0, "texture freed");
                check(env.dispatch.liveImages == 0, "image destroyed");
                check(env.dispatch.liveImageViews == 0, "view destroyed");
                check(env.dispatch.liveSamplers == 0, "sampler destroyed");
                check(env.dispatch.liveBuffers == 1, "staging buffer destroyed");
                check(env.dispatch.liveMemory == 1, "staging + image memories destroyed");
                // freeing an unknown key is a no-op
                env.recorder->freeTexture(999);
                check(env.dispatch.liveBuffers == 1, "unknown free is a no-op");
        }

        std::printf("wrvk: texture pool budget cap\n");
        {
                TestEnv env;
                const uint8_t px[4] = {1, 2, 3, 4};
                for (uint32_t key = 1; key <= 4096; key++) {
                        env.recorder->uploadTexture(key, 1, 1, px, sizeof(px));
                }
                check(env.recorder->liveTextureCount() == 4096, "pool filled to the budget");
                check(!env.recorder->uploadTexture(4097, 1, 1, px, sizeof(px)), "budget cap rejects new keys");
                check(env.recorder->liveTextureCount() == 4096, "budget rejection keeps the pool");
                check(env.recorder->uploadTexture(1, 1, 1, px, sizeof(px)), "existing key re-uploads at the cap");
                check(env.recorder->liveTextureCount() == 4096, "re-upload at the cap adds nothing");
        }

        std::printf("wrvk: external texture overrides\n");
        {
                TestEnv env;
                wrx::GpuMesh mesh;
                mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                mesh.indices = {0, 1, 2};
                const uint8_t kd[4] = {255, 255, 255, 255};
                check(env.recorder->uploadMesh(50, mesh, kd), "mesh uploaded");
                uint8_t pixels[64];
                std::memset(pixels, 0x11, sizeof(pixels));
                check(env.recorder->uploadTexture(7, 4, 4, pixels, sizeof(pixels)), "pool texture uploaded");
                check(env.recorder->uploadTexture(9, 4, 4, pixels, sizeof(pixels)), "second pool texture uploaded");

                wrx::FrameHeader header = basicHeader();
                header.externalTextureCount = 2;
                wrx::FrameExternal externals[2]{};
                externals[0].key = 7; // shadows the pool entry with the same key
                externals[0].image = 0xDEAD0000;
                externals[0].view = 0xDEAD1000;
                externals[0].sampler = 0xDEAD2000;
                externals[1].key = 8; // no pool entry: only the external resolves
                externals[1].image = 0xBEEF0000;
                externals[1].view = 0; // empty handle -> items referencing it skip
                externals[1].sampler = 0xBEEF2000;

                wrx::FrameItem items[3]{};
                items[0] = makeItem(50, 7, 4, 15); // external 7 wins over the pool
                items[1] = makeItem(50, 8, 4, 15); // external 8 has view 0 -> skipped
                items[2] = makeItem(50, 9, 4, 15); // not in the externals -> pool entry
                header.itemCount = 3;

                check(env.recorder->recordFrame(header,
                        reinterpret_cast<const uint8_t*>(externals),
                        reinterpret_cast<const uint8_t*>(items)), "frame recorded with externals");
                check(env.dispatch.drawCalls == 2, "empty external handle skips the item");
                check(env.dispatch.descriptorImageInfos.size() == 2, "two descriptor writes");
                if (env.dispatch.descriptorImageInfos.size() == 2) {
                        check(env.dispatch.descriptorImageInfos[0].view == 0xDEAD1000
                                        && env.dispatch.descriptorImageInfos[0].sampler == 0xDEAD2000,
                                "external override supplies the view + sampler");
                        check(env.dispatch.descriptorImageInfos[1].view != 0xDEAD1000
                                        && env.dispatch.descriptorImageInfos[1].view != 0,
                                "unmatched key falls back to the pool view");
                }
                // the pool texture with key 7 still exists; only the binding changed
                check(env.recorder->liveTextureCount() == 2, "pool untouched by the override");
        }

        std::printf("wrvk: allocation failure paths never corrupt state\n");
        {
                wrvk::RecordingDispatch dispatch;
                wrvk::WrvkRecorder recorder(dispatch);
                recorder.attachHost(fakeHost());
                dispatch.failAllocation = true;
                wrx::GpuMesh mesh;
                mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                mesh.indices = {0, 1, 2};
                const uint8_t kd[4] = {255, 255, 255, 255};
                check(!recorder.uploadMesh(1, mesh, kd), "mesh upload fails cleanly");
                check(recorder.liveGpuMeshCount() == 0, "no mesh tracked on failure");
                check(dispatch.liveBuffers == 1, "no buffer leaked on failure");
                uint8_t pixels[64];
                std::memset(pixels, 0, sizeof(pixels));
                check(!recorder.uploadTexture(50, 4, 4, pixels, sizeof(pixels)), "texture upload fails cleanly");
                check(recorder.liveTextureCount() == 0, "no texture tracked on failure");
                check(dispatch.liveImages == 0 && dispatch.liveImageViews == 0 && dispatch.liveSamplers == 0,
                        "no image objects leaked on failure");
                check(dispatch.liveBuffers == 1, "no staging buffer leaked on failure");
                check(dispatch.liveMemory == 1, "no memory leaked on failure");
        }

        std::printf("wrvk: teardown leaks nothing\n");
        {
                wrvk::RecordingDispatch dispatch;
                {
                        wrvk::WrvkRecorder recorder(dispatch);
                        recorder.attachHost(fakeHost());
                        for (uint32_t key = 0; key < 3; key++) {
                                std::vector<uint8_t> blob = shaderBlob(fakeShader(0xA0 + key), fakeShader(0xB0 + key));
                                recorder.uploadShader(key, blob.data(), static_cast<uint32_t>(blob.size()));
                        }
                        wrx::GpuMesh mesh;
                        mesh.vertices.resize(3 * wrx::VERTEX_BYTES);
                        mesh.indices = {0, 1, 2};
                        const uint8_t kd[4] = {255, 255, 255, 255};
                        recorder.uploadMesh(1, mesh, kd);
                        uint8_t pixels[64];
                        std::memset(pixels, 0x5A, sizeof(pixels));
                        recorder.uploadTexture(3, 4, 4, pixels, sizeof(pixels));
                        wrx::FrameHeader header = basicHeader();
                        check(recorder.recordFrame(header, nullptr, nullptr), "frame recorded before teardown");
                }
                // the dispatch outlives the recorder, so its counters after the
                // recorder destructor prove every object was destroyed
                check(dispatch.liveBuffers == 0, "no buffers leak at teardown");
                check(dispatch.liveMemory == 0, "no device memory leaks at teardown");
                check(dispatch.livePipelines == 0, "no pipelines leak at teardown");
                check(dispatch.liveDescriptorPools == 0, "no descriptor pools leak at teardown");
                check(dispatch.liveDescriptorSetLayouts == 0, "no set layouts leak at teardown");
                check(dispatch.livePipelineLayouts == 0, "no pipeline layouts leak at teardown");
                check(dispatch.liveShaderModules == 0, "no shader modules leak at teardown");
                check(dispatch.liveImages == 0, "no images leak at teardown");
                check(dispatch.liveImageViews == 0, "no image views leak at teardown");
                check(dispatch.liveSamplers == 0, "no samplers leak at teardown");
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
