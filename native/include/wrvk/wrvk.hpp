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
};

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
                DeviceWaitIdle,
                CmdSetViewport,
                CmdSetScissor,
                CmdBindPipeline,
                CmdBindVertexBuffers,
                CmdBindIndexBuffer,
                CmdBindDescriptorSets,
                CmdPushConstants,
                CmdDrawIndexed,
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
};

// ---------------------------------------------------------------------------
// WrvkRecorder — implements the wrx::Server recorder interface.
// ---------------------------------------------------------------------------

class WrvkRecorder final {
public:
        explicit WrvkRecorder(IVkDispatch& dispatch);
        ~WrvkRecorder();

        // Server hooks
        void attachHost(const wrx::HostHandles& host);
        void uploadShader(uint32_t key, const uint8_t* code, uint32_t size); // vert+frag concatenated, u32 vertSize prefix
        bool recordFrame(const wrx::FrameHeader& header, const uint8_t* textures, const uint8_t* items);

        // Mesh upload/retire hooks; the server calls these at build time.
        bool uploadMesh(uint32_t meshId, const wrx::GpuMesh& mesh);
        void freeMesh(uint32_t meshId); // deferred until the device is idle

        // Test introspection
        size_t liveGpuMeshCount() const;
        uint64_t pipelineFor(uint32_t stage) const;
        bool hasHost() const { return host_.valid; }

private:
        struct Impl;
        std::unique_ptr<Impl> impl_;
        IVkDispatch& dispatch_;
        wrx::HostHandles host_{};
};

} // namespace wrvk
