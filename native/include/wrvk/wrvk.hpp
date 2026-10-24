// SPDX-License-Identifier: MIT
//
// wrvk.hpp — the Vulkan draw path that replaces the 1.20.x OpenGL flat pass.
//
// The renderer is an *embedded guest*: VulkanMod owns the instance, the
// device, the queues, the frame and every texture. Java hands those handles
// over through the exchange (MSG_INIT_VULKAN / the per-frame header), and
// wrvk records its draws into VulkanMod's current primary command buffer from
// inside the world render phase. It never creates its own instance, never
// submits, and never presents — which is what keeps it compatible with any
// in-flight depth VulkanMod uses.
//
// All Vulkan entry goes through IVkDispatch so the command-recording tests can
// verify the exact stream we emit without a device or ICD present.

#pragma once

#include "wrx/wrx.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace wrvk {

// ---------------------------------------------------------------------------
// IVkDispatch — every Vulkan function wrvk calls, as an interface.
// ---------------------------------------------------------------------------

class IVkDispatch {
public:
        virtual ~IVkDispatch() = default;

        // memory / buffers
        virtual uint32_t getPhysicalDeviceMemoryProperties(uint64_t physicalDevice, void* outProps) = 0;
        virtual uint32_t createBuffer(uint64_t device, const void* createInfo, uint64_t* outBuffer) = 0;
        virtual void getBufferMemoryRequirements(uint64_t device, uint64_t buffer, void* outRequirements) = 0;
        virtual uint32_t allocateMemory(uint64_t device, const void* allocInfo, uint64_t* outMemory) = 0;
        virtual uint32_t mapMemory(uint64_t device, uint64_t memory, uint64_t offset, uint64_t size, void** outData) = 0;
        virtual void unmapMemory(uint64_t device, uint64_t memory) = 0;
        virtual uint32_t bindBufferMemory(uint64_t device, uint64_t buffer, uint64_t memory, uint64_t offset) = 0;
        virtual void destroyBuffer(uint64_t device, uint64_t buffer) = 0;
        virtual void freeMemory(uint64_t device, uint64_t memory) = 0;

        // images / samplers (the native texture pool, protocol v2)
        virtual uint32_t createImage(uint64_t device, const void* createInfo, uint64_t* outImage) = 0;
        virtual void destroyImage(uint64_t device, uint64_t image) = 0;
        virtual void getImageMemoryRequirements(uint64_t device, uint64_t image, void* outRequirements) = 0;
        virtual uint32_t bindImageMemory(uint64_t device, uint64_t image, uint64_t memory, uint64_t offset) = 0;
        virtual uint32_t createImageView(uint64_t device, const void* createInfo, uint64_t* outView) = 0;
        virtual void destroyImageView(uint64_t device, uint64_t view) = 0;
        virtual uint32_t createSampler(uint64_t device, const void* createInfo, uint64_t* outSampler) = 0;
        virtual void destroySampler(uint64_t device, uint64_t sampler) = 0;

        // descriptor bookkeeping
        virtual uint32_t createDescriptorSetLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) = 0;
        virtual uint32_t createPipelineLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) = 0;
        virtual uint32_t createDescriptorPool(uint64_t device, const void* createInfo, uint64_t* outPool) = 0;
        virtual uint32_t allocateDescriptorSets(uint64_t device, const void* allocInfo, uint64_t* outSets) = 0;
        virtual void updateDescriptorSets(uint64_t device, uint32_t writeCount, const void* writes, uint32_t copyCount, const void* copies) = 0;
        virtual void resetDescriptorPool(uint64_t device, uint64_t pool) = 0;
        virtual void destroyDescriptorPool(uint64_t device, uint64_t pool) = 0;
        virtual void destroyPipelineLayout(uint64_t device, uint64_t layout) = 0;
        virtual void destroyDescriptorSetLayout(uint64_t device, uint64_t layout) = 0;

        // pipelines / shaders
        virtual uint32_t createShaderModule(uint64_t device, const void* createInfo, uint64_t* outModule) = 0;
        virtual void destroyShaderModule(uint64_t device, uint64_t module) = 0;
        virtual uint32_t createGraphicsPipelines(uint64_t device, uint64_t pipelineCache, uint32_t count, const void* createInfos, uint64_t* outPipelines) = 0;
        virtual void destroyPipeline(uint64_t device, uint64_t pipeline) = 0;

        // sync
        virtual uint32_t deviceWaitIdle(uint64_t device) = 0;

        // recording (into the host-provided command buffer)
        virtual void cmdSetViewport(uint64_t cmdBuffer, float x, float y, float width, float height) = 0;
        virtual void cmdSetScissor(uint64_t cmdBuffer, int32_t x, int32_t y, uint32_t width, uint32_t height) = 0;
        virtual void cmdBindPipeline(uint64_t cmdBuffer, uint32_t pipelineBindPoint, uint64_t pipeline) = 0;
        virtual void cmdBindVertexBuffers(uint64_t cmdBuffer, uint32_t first, uint32_t count, const uint64_t* buffers, const uint64_t* offsets) = 0;
        virtual void cmdBindIndexBuffer(uint64_t cmdBuffer, uint64_t buffer, uint64_t offset, uint32_t indexType) = 0;
        virtual void cmdBindDescriptorSets(uint64_t cmdBuffer, uint32_t bindPoint, uint64_t layout, uint32_t first, uint32_t count, const uint64_t* sets, uint32_t dynamicCount, const uint32_t* offsets) = 0;
        virtual void cmdPushConstants(uint64_t cmdBuffer, uint64_t layout, uint32_t stages, uint32_t offset, uint32_t size, const void* data) = 0;
        virtual void cmdDrawIndexed(uint64_t cmdBuffer, uint32_t indexCount, uint32_t instanceCount, uint32_t firstIndex, int32_t vertexOffset, uint32_t firstInstance) = 0;
        virtual void cmdPipelineBarrier(uint64_t cmdBuffer, uint32_t srcStageMask, uint32_t dstStageMask, uint32_t dependencyFlags, uint32_t memoryBarrierCount, const void* memoryBarriers, uint32_t bufferMemoryBarrierCount, const void* bufferMemoryBarriers, uint32_t imageMemoryBarrierCount, const void* imageMemoryBarriers) = 0;
        virtual void cmdCopyBufferToImage(uint64_t cmdBuffer, uint64_t srcBuffer, uint64_t dstImage, uint32_t dstImageLayout, uint32_t regionCount, const void* regions) = 0;
};

// ---------------------------------------------------------------------------
// Native rendering math (protocol v2) — the transforms Java used to compute,
// reproduced JOML-exactly so the recorded stream is bit-for-bit the same
// pipeline input. Free functions so the test suite can unit-check them
// against hand-computed matrices.
// ---------------------------------------------------------------------------

// out = a × b in column-major storage (JOML Matrix4f m; m.set(a); m.mul(b)
// followed by get(float[16])): out[c*4+r] = Σ_k a[k*4+r] * b[c*4+k].
void mat4Mul(const float* a, const float* b, float* out);

// N = transpose(inverse(upper-left 3×3 of mv)) — JOML
// new Matrix3f().set(mv).invert().transpose() then get(float[9]) — packed
// into 12 floats as three vec4 columns for the push-constant area:
// out[0..2] = column 0, out[4..6] = column 1, out[8..10] = column 2,
// out[3] = out[7] = out[11] = 0 (std140 mat3 padding). A singular 3×3 (which
// JOML would turn into NaNs) yields the identity instead.
void normalMatrixFromMv(const float* mv, float* out12);

// RenderStage brightness: stages LIGHT / ALWAYS_ON_LIGHT / INTERIOR /
// INTERIOR_TRANSLUCENT are full-bright; EXTERIOR (and anything else) scales
// the RAW packed lightmap int by 1/15 — Java's `(float) light / 0xF`, ratio
// unclamped (clamping happens later, per colour channel).
float brightnessForStage(uint32_t stage, uint32_t light);

// Create the real dispatch: dlopen/LoadLibrary libvulkan, resolve through
// vkGetInstanceProcAddr with the host instance handle. Returns null when the
// loader or any required symbol is missing (feature-detected disable).
std::unique_ptr<IVkDispatch> createRealDispatch(const std::string& explicitLibraryPath, uint64_t instance);

// The recording stub used by the tests: forwards every call into a flat log.
class RecordingDispatch : public IVkDispatch {
public:
        enum class Op {
                GetMemProps,
                CreateBuffer,
                AllocateMemory,
                MapMemory,
                UnmapMemory,
                BindBufferMemory,
                DestroyBuffer,
                FreeMemory,
                CreateDescriptorSetLayout,
                CreatePipelineLayout,
                CreateDescriptorPool,
                AllocateDescriptorSets,
                UpdateDescriptorSets,
                ResetDescriptorPool,
                DestroyDescriptorPool,
                DestroyPipelineLayout,
                DestroyDescriptorSetLayout,
                CreateShaderModule,
                DestroyShaderModule,
                CreateGraphicsPipelines,
                DestroyPipeline,
                CreateImage,
                DestroyImage,
                GetImageMemReq,
                BindImageMemory,
                CreateImageView,
                DestroyImageView,
                CreateSampler,
                DestroySampler,
                DeviceWaitIdle,
                CmdSetViewport,
                CmdSetScissor,
                CmdBindPipeline,
                CmdBindVertexBuffers,
                CmdBindIndexBuffer,
                CmdBindDescriptorSets,
                CmdPushConstants,
                CmdDrawIndexed,
                CmdPipelineBarrier,
                CmdCopyBufferToImage,
        };
        struct Entry {
                Op op;
                uint64_t a0{0}, a1{0}, a2{0}, a3{0};
        };
        std::vector<Entry> log;
        // failure injection for negative tests
        bool failAllocation{false};

        // live object tables so the tests can assert clean teardown
        struct BufferRecord {
                uint64_t size{0};
        };
        std::unordered_map<uint64_t, BufferRecord> buffers;
        std::unordered_map<uint64_t, uint64_t> memorySizes;
        std::unordered_map<uint64_t, std::vector<uint8_t>> mapped; // memory -> bytes
        std::unordered_map<uint64_t, uint64_t> bufferMemory;
        std::unordered_map<uint64_t, uint64_t> bufferOffset;
        std::unordered_map<uint64_t, std::vector<uint64_t>> poolSets;
        std::unordered_map<uint64_t, uint64_t> createdPipelineRenderPass;
        std::unordered_map<uint64_t, uint64_t> createdPipelineLayout;

        // live image-object tables (the v2 texture pool) so leak tests can
        // assert clean teardown of VkImage / VkImageView / VkSampler
        struct ImageRecord {
                uint32_t width{0};
                uint32_t height{0};
                uint32_t format{0};
                uint32_t tiling{0};
                uint32_t usage{0};
        };
        std::unordered_map<uint64_t, ImageRecord> images;
        std::unordered_map<uint64_t, uint64_t> imageViewImages; // view -> image
        struct SamplerRecord {
                uint32_t magFilter{0};
                uint32_t minFilter{0};
                uint32_t addressModeU{0};
                uint32_t addressModeV{0};
                uint32_t addressModeW{0};
        };
        std::unordered_map<uint64_t, SamplerRecord> samplers;

        // structured records of the frame upload flush and descriptor writes
        // (the flat log keeps one entry per call; these keep the payload)
        struct ImageBarrierRecord {
                uint64_t image{0};
                uint32_t oldLayout{0};
                uint32_t newLayout{0};
        };
        std::vector<ImageBarrierRecord> imageBarriers;
        struct BufferImageCopyRecord {
                uint64_t srcBuffer{0};
                uint64_t dstImage{0};
                uint32_t width{0};
                uint32_t height{0};
        };
        std::vector<BufferImageCopyRecord> bufferImageCopies;
        struct DescriptorImageRecord {
                uint64_t view{0};
                uint64_t sampler{0};
        };
        std::vector<DescriptorImageRecord> descriptorImageInfos;
        std::vector<uint64_t> mmapScratchUnused;
        std::array<uint8_t, 1 << 16> mmapScratch{};
        float lastViewport[4]{};
        int32_t lastScissor[4]{};
        uint64_t lastBoundPipeline{0};
        uint64_t lastVertexBuffer{0};
        uint64_t lastIndexBuffer{0};
        std::vector<uint64_t> lastBoundSets;
        uint32_t drawCalls{0};

        uint64_t nextHandle{0x1000};
        uint64_t newHandle() { return nextHandle += 16; }

        // leak counters
        size_t liveBuffers{0};
        size_t liveMemory{0};
        size_t livePipelines{0};
        size_t liveDescriptorPools{0};
        size_t liveShaderModules{0};
        size_t liveDescriptorSetLayouts{0};
        size_t livePipelineLayouts{0};
        size_t liveImages{0};
        size_t liveImageViews{0};
        size_t liveSamplers{0};

        void mapBuffer(uint64_t buffer, uint64_t size, void** outData);
        // access the recorded bytes of a mapped buffer for assertions
        uint8_t* mappedData(uint64_t buffer);
        uint64_t mappedSize(uint64_t buffer);
        void eraseAllObjects();
        std::vector<std::array<float, 32>> pushConstants; // every cmdPushConstants payload (128 B)

        // IVkDispatch
        uint32_t getPhysicalDeviceMemoryProperties(uint64_t physicalDevice, void* outProps) override;
        uint32_t createBuffer(uint64_t device, const void* createInfo, uint64_t* outBuffer) override;
        void getBufferMemoryRequirements(uint64_t device, uint64_t buffer, void* outRequirements) override;
        uint32_t allocateMemory(uint64_t device, const void* allocInfo, uint64_t* outMemory) override;
        uint32_t mapMemory(uint64_t device, uint64_t memory, uint64_t offset, uint64_t size, void** outData) override;
        void unmapMemory(uint64_t device, uint64_t memory) override;
        uint32_t bindBufferMemory(uint64_t device, uint64_t buffer, uint64_t memory, uint64_t offset) override;
        void destroyBuffer(uint64_t device, uint64_t buffer) override;
        void freeMemory(uint64_t device, uint64_t memory) override;
        uint32_t createImage(uint64_t device, const void* createInfo, uint64_t* outImage) override;
        void destroyImage(uint64_t device, uint64_t image) override;
        void getImageMemoryRequirements(uint64_t device, uint64_t image, void* outRequirements) override;
        uint32_t bindImageMemory(uint64_t device, uint64_t image, uint64_t memory, uint64_t offset) override;
        uint32_t createImageView(uint64_t device, const void* createInfo, uint64_t* outView) override;
        void destroyImageView(uint64_t device, uint64_t view) override;
        uint32_t createSampler(uint64_t device, const void* createInfo, uint64_t* outSampler) override;
        void destroySampler(uint64_t device, uint64_t sampler) override;
        uint32_t createDescriptorSetLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) override;
        uint32_t createPipelineLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) override;
        uint32_t createDescriptorPool(uint64_t device, const void* createInfo, uint64_t* outPool) override;
        uint32_t allocateDescriptorSets(uint64_t device, const void* allocInfo, uint64_t* outSets) override;
        void updateDescriptorSets(uint64_t device, uint32_t writeCount, const void* writes, uint32_t copyCount, const void* copies) override;
        void resetDescriptorPool(uint64_t device, uint64_t pool) override;
        void destroyDescriptorPool(uint64_t device, uint64_t pool) override;
        void destroyPipelineLayout(uint64_t device, uint64_t layout) override;
        void destroyDescriptorSetLayout(uint64_t device, uint64_t layout) override;
        uint32_t createShaderModule(uint64_t device, const void* createInfo, uint64_t* outModule) override;
        void destroyShaderModule(uint64_t device, uint64_t module) override;
        uint32_t createGraphicsPipelines(uint64_t device, uint64_t pipelineCache, uint32_t count, const void* createInfos, uint64_t* outPipelines) override;
        void destroyPipeline(uint64_t device, uint64_t pipeline) override;
        uint32_t deviceWaitIdle(uint64_t device) override;
        void cmdSetViewport(uint64_t cmdBuffer, float x, float y, float width, float height) override;
        void cmdSetScissor(uint64_t cmdBuffer, int32_t x, int32_t y, uint32_t width, uint32_t height) override;
        void cmdBindPipeline(uint64_t cmdBuffer, uint32_t bindPoint, uint64_t pipeline) override;
        void cmdBindVertexBuffers(uint64_t cmdBuffer, uint32_t first, uint32_t count, const uint64_t* buffers, const uint64_t* offsets) override;
        void cmdBindIndexBuffer(uint64_t cmdBuffer, uint64_t buffer, uint64_t offset, uint32_t indexType) override;
        void cmdBindDescriptorSets(uint64_t cmdBuffer, uint32_t bindPoint, uint64_t layout, uint32_t first, uint32_t count, const uint64_t* sets, uint32_t dynamicCount, const uint32_t* offsets) override;
        void cmdPushConstants(uint64_t cmdBuffer, uint64_t layout, uint32_t stages, uint32_t offset, uint32_t size, const void* data) override;
        void cmdDrawIndexed(uint64_t cmdBuffer, uint32_t indexCount, uint32_t instanceCount, uint32_t firstIndex, int32_t vertexOffset, uint32_t firstInstance) override;
        void cmdPipelineBarrier(uint64_t cmdBuffer, uint32_t srcStageMask, uint32_t dstStageMask, uint32_t dependencyFlags, uint32_t memoryBarrierCount, const void* memoryBarriers, uint32_t bufferMemoryBarrierCount, const void* bufferMemoryBarriers, uint32_t imageMemoryBarrierCount, const void* imageMemoryBarriers) override;
        void cmdCopyBufferToImage(uint64_t cmdBuffer, uint64_t srcBuffer, uint64_t dstImage, uint32_t dstImageLayout, uint32_t regionCount, const void* regions) override;
};

// ---------------------------------------------------------------------------
// WrvkRecorder — implements the wrx::Server recorder interface.
//
// Protocol v2: wrvk owns the whole rendering pipeline state — the native
// texture pool (VkImage/VkImageView/VkSampler + staging buffers uploaded
// through the frame command buffer), the per-item model-view, the
// inverse-transpose normal matrix and the Kd×brightness colour, all computed
// at record time from the raw poses Java copies out of the PoseStack.
// ---------------------------------------------------------------------------

class WrvkRecorder final {
public:
        explicit WrvkRecorder(IVkDispatch& dispatch);
        ~WrvkRecorder();

        // Server hooks
        void attachHost(const wrx::HostHandles& host);
        void uploadShader(uint32_t key, const uint8_t* code, uint32_t size); // vert+frag concatenated, u32 vertSize prefix
        bool recordFrame(const wrx::FrameHeader& header, const uint8_t* externals, const uint8_t* items); // v2 semantics

        // Mesh upload/retire hooks; the server calls these at build time.
        // kd is the material diffuse colour (r, g, b, a; each 0..255) the item
        // colour is derived from at record time.
        bool uploadMesh(uint32_t meshId, const wrx::GpuMesh& mesh, const uint8_t kd[4]);
        void freeMesh(uint32_t meshId); // deferred until the device is idle

        // Native texture pool (v2). Pixels are RGBA8 rows, top-left origin;
        // the copy itself is recorded into the next frame's command buffer.
        // A repeated key re-uploads; changed dimensions recreate the image.
        bool uploadTexture(uint32_t key, uint32_t width, uint32_t height, const uint8_t* rgbaPixels, uint32_t byteCount);
        void freeTexture(uint32_t key); // deferred until the device is idle

        // Test introspection
        size_t liveGpuMeshCount() const;
        size_t liveTextureCount() const;
        uint64_t pipelineFor(uint32_t stage) const;
        bool hasHost() const { return host_.valid; }

private:
        struct Impl;
        std::unique_ptr<Impl> impl_;
        IVkDispatch& dispatch_;
        wrx::HostHandles host_{};
};

} // namespace wrvk
