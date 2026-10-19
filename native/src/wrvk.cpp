// SPDX-License-Identifier: MIT
//
// wrvk.cpp — the Vulkan recorder: pipelines, mesh uploads and frame recording.
//
// All Vulkan entry goes through IVkDispatch. The real dispatch resolves the
// loader dynamically (wrvk_loader.cpp); the tests substitute RecordingDispatch
// and assert on the emitted command stream.

#include "wrvk/wrvk.hpp"

#include <algorithm>
#include <cstring>

#include "vulkan/vulkan.h"

namespace wrvk {

namespace {

void* h2p(uint64_t handle) {
        return reinterpret_cast<void*>(static_cast<uintptr_t>(handle));
}

VkDevice devH(uint64_t handle) {
        return static_cast<VkDevice>(h2p(handle));
}

VkPhysicalDevice physH(uint64_t handle) {
        return static_cast<VkPhysicalDevice>(h2p(handle));
}

VkBuffer bufH(uint64_t handle) {
        return static_cast<VkBuffer>(h2p(handle));
}

VkDescriptorSetLayout setLayoutH(uint64_t handle) {
        return static_cast<VkDescriptorSetLayout>(h2p(handle));
}

VkDescriptorSet setH(uint64_t handle) {
        return static_cast<VkDescriptorSet>(h2p(handle));
}

VkDescriptorPool poolH(uint64_t handle) {
        return static_cast<VkDescriptorPool>(h2p(handle));
}

VkPipelineLayout pipelineLayoutH(uint64_t handle) {
        return static_cast<VkPipelineLayout>(h2p(handle));
}

VkRenderPass renderPassH(uint64_t handle) {
        return static_cast<VkRenderPass>(h2p(handle));
}

VkShaderModule shaderModuleH(uint64_t handle) {
        return static_cast<VkShaderModule>(h2p(handle));
}

VkPipeline pipelineH(uint64_t handle) {
        return static_cast<VkPipeline>(h2p(handle));
}

VkCommandBuffer commandBufferH(uint64_t handle) {
        return static_cast<VkCommandBuffer>(h2p(handle));
}

VkImageLayout imageLayoutH(uint32_t layout) {
        return static_cast<VkImageLayout>(layout);
}

} // namespace

// Shader keys carried by MSG_LOAD_SHADERS.
static constexpr uint32_t SHADER_CUTOUT = 0;
static constexpr uint32_t SHADER_TRANSLUCENT = 1;
static constexpr uint32_t SHADER_BEACON = 2;

// RenderStage ordinals from org.mtr.resource.RenderStage.
static constexpr uint32_t STAGE_LIGHT = 0;
static constexpr uint32_t STAGE_ALWAYS_ON_LIGHT = 1;
static constexpr uint32_t STAGE_INTERIOR = 2;
static constexpr uint32_t STAGE_INTERIOR_TRANSLUCENT = 3;
static constexpr uint32_t STAGE_EXTERIOR = 4;
static constexpr uint32_t STAGE_COUNT = 5;

// Descriptor set / binding layout.
static constexpr uint32_t SET_SAMPLER = 0;
static constexpr uint32_t SET_UBO = 1;
static constexpr uint32_t UBO_SLOTS = 64;      // 128 B each, 8 KiB total
static constexpr uint32_t POOL_RING = 6;      // > any sane swapchain in-flight depth
static constexpr uint32_t MAX_MESHES = 2048;   // vkAllocateMemory budget guard
static constexpr uint32_t UBO_BYTES = 128;

// std140 frame uniform block: mat4 P; vec4 FogColor; float FogStart; float
// FogEnd; vec4 Light0; vec4 Light1 — offsets 0/64/80/84/88/104, size 120.
static constexpr uint32_t UBO_P_OFF = 0;
static constexpr uint32_t UBO_FOG_COLOR_OFF = 64;
static constexpr uint32_t UBO_FOG_START_OFF = 80;
static constexpr uint32_t UBO_FOG_END_OFF = 84;
static constexpr uint32_t UBO_LIGHT0_OFF = 88;
static constexpr uint32_t UBO_LIGHT1_OFF = 104;

// Push constants (128 B guaranteed): vec4 Color; mat4 MV; mat3 N (3×vec4).
static constexpr uint32_t PUSH_BYTES = 128;
static constexpr uint32_t PUSH_COLOR_OFF = 0;
static constexpr uint32_t PUSH_MV_OFF = 16;
static constexpr uint32_t PUSH_N_OFF = 80;

struct WrvkRecorder::Impl {
        IVkDispatch& dispatchRef;

        explicit Impl(IVkDispatch& dispatch)
                : dispatchRef(dispatch) {
        }

        std::mutex mutex;
        wrx::HostHandles host{};
        bool hostReady{false};

        std::unordered_map<uint32_t, std::pair<std::vector<uint8_t>, std::vector<uint8_t>>> shaders;
        bool shadersDirty{true};

        uint64_t samplerSetLayout{0};
        uint64_t uboSetLayout{0};
        uint64_t pipelineLayout{0};

        uint64_t uboBuffer{0};
        uint64_t uboMemory{0};
        void* uboMapped{nullptr};
        uint64_t uboSets[UBO_SLOTS]{};

        uint64_t pools[POOL_RING]{};
        uint64_t frameCounter{0};

        uint64_t currentRenderPass{0};
        uint64_t pipelines[STAGE_COUNT]{};

        struct MeshGpu {
                uint64_t buffer{0};
                uint64_t memory{0};
                void* mapped{nullptr};
                uint64_t indexOffset{0};
                uint32_t indexCount{0};
                int refs{0};
                const void* meshPtr{nullptr};
        };
        std::unordered_map<const void*, MeshGpu> gpuByMesh;
        std::unordered_map<uint32_t, const void*> meshIdToPtr;

        bool fatal{false};

        ~Impl() { destroyAll(); }

        void destroyAll() {
                if (!hostReady) {
                        return;
                }
                dispatchDeviceWaitIdle();
                for (uint32_t i = 0; i < STAGE_COUNT; i++) {
                        if (pipelines[i] != 0) {
                                destroyPipeline(pipelines[i]);
                                pipelines[i] = 0;
                        }
                }
                for (uint64_t pool : pools) {
                        if (pool != 0) {
                                destroyPool(pool);
                        }
                }
                for (auto& entry : gpuByMesh) {
                        destroyMesh(entry.second);
                }
                gpuByMesh.clear();
                meshIdToPtr.clear();
                if (uboBuffer != 0) {
                        destroyBuffer(uboBuffer);
                        uboBuffer = 0;
                }
                if (uboMemory != 0) {
                        freeMem(uboMemory);
                        uboMemory = 0;
                        uboMapped = nullptr;
                }
                if (pipelineLayout != 0) {
                        destroyPipelineLayout(pipelineLayout);
                        pipelineLayout = 0;
                }
                if (samplerSetLayout != 0) {
                        destroySetLayout(samplerSetLayout);
                        samplerSetLayout = 0;
                }
                if (uboSetLayout != 0) {
                        destroySetLayout(uboSetLayout);
                        uboSetLayout = 0;
                }
                hostReady = false;
        }

        // dispatch helpers (IVkDispatch& lives on the recorder)
        IVkDispatch& d();

        uint32_t device() const { return static_cast<uint32_t>(host.device); }

        void dispatchDeviceWaitIdle() { d().deviceWaitIdle(device()); }
        void destroyPipeline(uint64_t p) { d().destroyPipeline(device(), p); }
        void destroyPool(uint64_t p) { d().destroyDescriptorPool(device(), p); }
        void destroyBuffer(uint64_t b) { d().destroyBuffer(device(), b); }
        void freeMem(uint64_t m) { d().freeMemory(device(), m); }
        void destroyPipelineLayout(uint64_t l) { d().destroyPipelineLayout(device(), l); }
        void destroySetLayout(uint64_t l) { d().destroyDescriptorSetLayout(device(), l); }

        void destroyMesh(MeshGpu& mesh) {
                if (mesh.buffer != 0) {
                        destroyBuffer(mesh.buffer);
                }
                if (mesh.memory != 0) {
                        if (mesh.mapped != nullptr) {
                                d().unmapMemory(device(), mesh.memory);
                                mesh.mapped = nullptr;
                        }
                        freeMem(mesh.memory);
                }
        }

        // find a HOST_VISIBLE | HOST_COHERENT memory type
        int32_t hostVisibleMemoryType() {
                VkPhysicalDeviceMemoryProperties props{};
                d().getPhysicalDeviceMemoryProperties(host.physicalDevice, &props);
                const VkMemoryPropertyFlags wanted = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
                        if ((props.memoryTypes[i].propertyFlags & wanted) == wanted) {
                                return static_cast<int32_t>(i);
                        }
                }
                return -1;
        }

        bool allocateAndMap(VkBufferCreateInfo& bufferInfo, uint64_t& outBuffer, uint64_t& outMemory, void** outMapped) {
                VkDeviceSize required{};
                {
                        if (d().createBuffer(device(), &bufferInfo, &outBuffer) != VK_SUCCESS) {
                                return false;
                        }
                        VkMemoryRequirements requirements{};
                        d().getBufferMemoryRequirements(device(), outBuffer, &requirements);
                        required = requirements.size;
                }
                VkMemoryAllocateInfo allocInfo{};
                allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
                allocInfo.allocationSize = required;
                const int32_t type = hostVisibleMemoryType();
                if (type < 0) {
                        destroyBuffer(outBuffer);
                        outBuffer = 0;
                        return false;
                }
                allocInfo.memoryTypeIndex = static_cast<uint32_t>(type);
                if (d().allocateMemory(device(), &allocInfo, &outMemory) != VK_SUCCESS) {
                        destroyBuffer(outBuffer);
                        outBuffer = 0;
                        return false;
                }
                if (d().bindBufferMemory(device(), outBuffer, outMemory, 0) != VK_SUCCESS) {
                        destroyBuffer(outBuffer);
                        freeMem(outMemory);
                        outBuffer = 0;
                        outMemory = 0;
                        return false;
                }
                if (d().mapMemory(device(), outMemory, 0, VK_WHOLE_SIZE, outMapped) != VK_SUCCESS) {
                        destroyBuffer(outBuffer);
                        freeMem(outMemory);
                        outBuffer = 0;
                        outMemory = 0;
                        return false;
                }
                return true;
        }
};

IVkDispatch& WrvkRecorder::Impl::d() {
        return dispatchRef;
}

// ---------------------------------------------------------------------------
// lifecycle
// ---------------------------------------------------------------------------

WrvkRecorder::WrvkRecorder(IVkDispatch& dispatch) : impl_(new Impl(dispatch)), dispatch_(dispatch) {
}

WrvkRecorder::~WrvkRecorder() = default;

void WrvkRecorder::attachHost(const wrx::HostHandles& host) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        host_ = host;
        impl_->host = host;
        if (!impl_->hostReady) {
                // create the descriptor set layouts, pipeline layout, UBO ring and pools
                VkDescriptorSetLayoutBinding samplerBinding{};
                samplerBinding.binding = 0;
                samplerBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                samplerBinding.descriptorCount = 1;
                samplerBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
                VkDescriptorSetLayoutCreateInfo samplerLayoutInfo{};
                samplerLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
                samplerLayoutInfo.bindingCount = 1;
                samplerLayoutInfo.pBindings = &samplerBinding;
                impl_->d().createDescriptorSetLayout(impl_->device(), &samplerLayoutInfo, &impl_->samplerSetLayout);

                VkDescriptorSetLayoutBinding uboBinding{};
                uboBinding.binding = 0;
                uboBinding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                uboBinding.descriptorCount = 1;
                uboBinding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                VkDescriptorSetLayoutCreateInfo uboLayoutInfo{};
                uboLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
                uboLayoutInfo.bindingCount = 1;
                uboLayoutInfo.pBindings = &uboBinding;
                impl_->d().createDescriptorSetLayout(impl_->device(), &uboLayoutInfo, &impl_->uboSetLayout);

                VkPushConstantRange pushRange{};
                pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                pushRange.offset = 0;
                pushRange.size = PUSH_BYTES;
                const VkDescriptorSetLayout setLayouts[2] = {setLayoutH(impl_->samplerSetLayout), setLayoutH(impl_->uboSetLayout)};
                VkPipelineLayoutCreateInfo layoutInfo{};
                layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
                layoutInfo.setLayoutCount = 2;
                layoutInfo.pSetLayouts = setLayouts;
                layoutInfo.pushConstantRangeCount = 1;
                layoutInfo.pPushConstantRanges = &pushRange;
                impl_->d().createPipelineLayout(impl_->device(), &layoutInfo, &impl_->pipelineLayout);

                // UBO ring: one buffer, 64 slots of 128 bytes, permanently mapped.
                VkBufferCreateInfo uboInfo{};
                uboInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
                uboInfo.size = UBO_SLOTS * UBO_BYTES;
                uboInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                uboInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
                impl_->allocateAndMap(uboInfo, impl_->uboBuffer, impl_->uboMemory, &impl_->uboMapped);

                // one UBO descriptor set per slot, never reset
                if (impl_->uboBuffer != 0) {
                        VkDescriptorPoolCreateInfo poolInfo{};
                        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                        poolInfo.maxSets = UBO_SLOTS;
                        VkDescriptorPoolSize size{};
                        size.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                        size.descriptorCount = UBO_SLOTS;
                        poolInfo.poolSizeCount = 1;
                        poolInfo.pPoolSizes = &size;
                        uint64_t uboPool = 0;
                        impl_->d().createDescriptorPool(impl_->device(), &poolInfo, &uboPool);
                        VkDescriptorSetAllocateInfo allocInfo{};
                        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
                        allocInfo.descriptorPool = poolH(uboPool);
                        allocInfo.descriptorSetCount = 1;
                        VkDescriptorSetLayout uboLayoutHandle = setLayoutH(impl_->uboSetLayout);
                        allocInfo.pSetLayouts = &uboLayoutHandle;
                        for (uint32_t slot = 0; slot < UBO_SLOTS; slot++) {
                                uint64_t set = 0;
                                if (impl_->d().allocateDescriptorSets(impl_->device(), &allocInfo, &set) == VK_SUCCESS) {
                                        VkDescriptorBufferInfo bufferInfo{};
                                        bufferInfo.buffer = bufH(impl_->uboBuffer);
                                        bufferInfo.offset = slot * UBO_BYTES;
                                        bufferInfo.range = UBO_BYTES;
                                        VkWriteDescriptorSet write{};
                                        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                                        write.dstSet = setH(set);
                                        write.dstBinding = 0;
                                        write.descriptorCount = 1;
                                        write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
                                        write.pBufferInfo = &bufferInfo;
                                        impl_->d().updateDescriptorSets(impl_->device(), 1, &write, 0, nullptr);
                                        impl_->uboSets[slot] = set;
                                }
                        }
                }

                // rotating per-frame pools for sampler sets
                for (uint32_t i = 0; i < POOL_RING; i++) {
                        VkDescriptorPoolCreateInfo poolInfo{};
                        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
                        poolInfo.flags = 0; // reset per reuse, no FREE_DESCRIPTOR_SET_BIT needed
                        poolInfo.maxSets = 512;
                        VkDescriptorPoolSize size{};
                        size.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                        size.descriptorCount = 512;
                        poolInfo.poolSizeCount = 1;
                        poolInfo.pPoolSizes = &size;
                        impl_->d().createDescriptorPool(impl_->device(), &poolInfo, &impl_->pools[i]);
                }
                impl_->hostReady = true;
        }
        impl_->shadersDirty = true;
}

void WrvkRecorder::uploadShader(uint32_t key, const uint8_t* code, uint32_t size) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (key > SHADER_BEACON || size < 4) {
                return;
        }
        uint32_t vertSize = 0;
        std::memcpy(&vertSize, code, sizeof(uint32_t));
        if (vertSize > size - 4) {
                return;
        }
        auto pair = std::make_pair(std::vector<uint8_t>(code + 4, code + 4 + vertSize),
                std::vector<uint8_t>(code + 4 + vertSize, code + size));
        impl_->shaders[key] = std::move(pair);
        impl_->shadersDirty = true;
}

// ---------------------------------------------------------------------------
// mesh upload / free
// ---------------------------------------------------------------------------

bool WrvkRecorder::uploadMesh(uint32_t meshId, const wrx::Server::GpuMesh& mesh) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->hostReady || impl_->fatal) {
                return false;
        }
        const void* meshPtr = &mesh;
        const auto existing = impl_->meshIdToPtr.find(meshId);
        if (existing != impl_->meshIdToPtr.end() && existing->second == meshPtr) {
                return true; // re-upload of the same pair
        }
        auto gpuIt = impl_->gpuByMesh.find(meshPtr);
        if (gpuIt == impl_->gpuByMesh.end()) {
                if (impl_->gpuByMesh.size() >= MAX_MESHES) {
                        return false;
                }
                const uint64_t vertexBytes = mesh.vertices.size();
                const uint64_t indexBytes = mesh.indices.size() * sizeof(uint32_t);
                VkBufferCreateInfo bufferInfo{};
                bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
                bufferInfo.size = vertexBytes + indexBytes;
                bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
                Impl::MeshGpu gpu{};
                if (!impl_->allocateAndMap(bufferInfo, gpu.buffer, gpu.memory, &gpu.mapped)) {
                        return false;
                }
                std::memcpy(gpu.mapped, mesh.vertices.data(), vertexBytes);
                std::memcpy(static_cast<uint8_t*>(gpu.mapped) + vertexBytes, mesh.indices.data(), indexBytes);
                gpu.indexOffset = vertexBytes;
                gpu.indexCount = static_cast<uint32_t>(mesh.indices.size());
                gpu.refs = 0;
                gpu.meshPtr = meshPtr;
                gpuIt = impl_->gpuByMesh.emplace(meshPtr, gpu).first;
        }
        impl_->meshIdToPtr[meshId] = meshPtr;
        gpuIt->second.refs++;
        return true;
}

void WrvkRecorder::freeMesh(uint32_t meshId) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        const auto idIt = impl_->meshIdToPtr.find(meshId);
        if (idIt == impl_->meshIdToPtr.end()) {
                return;
        }
        const void* meshPtr = idIt->second;
        impl_->meshIdToPtr.erase(idIt);
        const auto gpuIt = impl_->gpuByMesh.find(meshPtr);
        if (gpuIt == impl_->gpuByMesh.end()) {
                return;
        }
        if (--gpuIt->second.refs > 0) {
                return;
        }
        // The mesh may still be referenced by an in-flight frame, so retire it
        // only after the device drains. Frees happen at resource reload, where a
        // hitch is already expected.
        impl_->dispatchDeviceWaitIdle();
        impl_->destroyMesh(gpuIt->second);
        impl_->gpuByMesh.erase(gpuIt);
}

size_t WrvkRecorder::liveGpuMeshCount() const {
        return const_cast<WrvkRecorder*>(this)->impl_->meshIdToPtr.size();
}

uint64_t WrvkRecorder::pipelineFor(uint32_t stage) const {
        return stage < STAGE_COUNT ? const_cast<WrvkRecorder*>(this)->impl_->pipelines[stage] : 0;
}

// ---------------------------------------------------------------------------
// pipeline creation (per render pass, invalidated by shader or pass changes)
// ---------------------------------------------------------------------------

static uint32_t shaderKeyForStage(uint32_t stage) {
        switch (stage) {
                case STAGE_LIGHT:
                case STAGE_ALWAYS_ON_LIGHT:
                        return SHADER_BEACON;
                case STAGE_INTERIOR:
                case STAGE_EXTERIOR:
                        return SHADER_CUTOUT;
                case STAGE_INTERIOR_TRANSLUCENT:
                default:
                        return SHADER_TRANSLUCENT;
        }
}

static void stageBlendConfig(uint32_t stage, bool& blend, bool& cullOff, bool& depthWriteOff) {
        switch (stage) {
                case STAGE_LIGHT:
                        blend = false;
                        cullOff = true;
                        depthWriteOff = false;
                        break;
                case STAGE_ALWAYS_ON_LIGHT:
                        blend = true;
                        cullOff = true;
                        depthWriteOff = true;
                        break;
                case STAGE_INTERIOR:
                case STAGE_EXTERIOR:
                        blend = false;
                        cullOff = false;
                        depthWriteOff = false;
                        break;
                case STAGE_INTERIOR_TRANSLUCENT:
                default:
                        blend = true;
                        cullOff = false;
                        depthWriteOff = false;
                        break;
        }
}

bool WrvkRecorder::recordFrame(const wrx::FrameHeader& header, const uint8_t* textures, const uint8_t* items) {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->hostReady || header.cmdBuffer == 0 || header.renderPass == 0) {
                return false;
        }
        if (impl_->shaders.size() < 3) {
                return false; // Java must load all three shader pairs first
        }

        // (re)create pipelines when the render pass or shaders changed
        const bool passChanged = header.renderPass != impl_->currentRenderPass;
        if (passChanged || impl_->shadersDirty) {
                impl_->dispatchDeviceWaitIdle();
                for (uint32_t s = 0; s < STAGE_COUNT; s++) {
                        if (impl_->pipelines[s] != 0) {
                                impl_->destroyPipeline(impl_->pipelines[s]);
                                impl_->pipelines[s] = 0;
                        }
                }
                for (uint32_t s = 0; s < STAGE_COUNT; s++) {
                        const uint32_t shaderKey = shaderKeyForStage(s);
                        const auto& pair = impl_->shaders[shaderKey];
                        VkShaderModuleCreateInfo vertInfo{}, fragInfo{};
                        vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
                        vertInfo.codeSize = pair.first.size();
                        vertInfo.pCode = reinterpret_cast<const uint32_t*>(pair.first.data());
                        fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
                        fragInfo.codeSize = pair.second.size();
                        fragInfo.pCode = reinterpret_cast<const uint32_t*>(pair.second.data());
                        uint64_t vertModule = 0, fragModule = 0;
                        if (impl_->d().createShaderModule(impl_->device(), &vertInfo, &vertModule) != VK_SUCCESS ||
                                impl_->d().createShaderModule(impl_->device(), &fragInfo, &fragModule) != VK_SUCCESS) {
                                if (vertModule != 0) {
                                        impl_->d().destroyShaderModule(impl_->device(), vertModule);
                                }
                                if (fragModule != 0) {
                                        impl_->d().destroyShaderModule(impl_->device(), fragModule);
                                }
                                return false;
                        }

                        VkPipelineShaderStageCreateInfo stages[2]{};
                        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
                        stages[0].module = shaderModuleH(vertModule);
                        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
                        stages[1].module = shaderModuleH(fragModule);

                        VkVertexInputBindingDescription binding{};
                        binding.stride = wrx::VERTEX_BYTES;
                        VkVertexInputAttributeDescription attributes[3]{};
                        attributes[0].location = 0; // vec3 float position
                        attributes[0].binding = 0;
                        attributes[0].format = VK_FORMAT_R32G32B32_SFLOAT;
                        attributes[0].offset = 0;
                        attributes[1].location = 1; // vec2 float uv
                        attributes[1].binding = 0;
                        attributes[1].format = VK_FORMAT_R32G32_SFLOAT;
                        attributes[1].offset = 12;
                        attributes[2].location = 2; // snorm8 normal (xyz + pad)
                        attributes[2].binding = 0;
                        attributes[2].format = VK_FORMAT_R8G8B8A8_SNORM;
                        attributes[2].offset = 20;

                        VkPipelineVertexInputStateCreateInfo vertexInput{};
                        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
                        vertexInput.vertexBindingDescriptionCount = 1;
                        vertexInput.pVertexBindingDescriptions = &binding;
                        vertexInput.vertexAttributeDescriptionCount = 3;
                        vertexInput.pVertexAttributeDescriptions = attributes;

                        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
                        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
                        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

                        VkPipelineViewportStateCreateInfo viewportState{};
                        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
                        viewportState.viewportCount = 1;
                        viewportState.scissorCount = 1;

                        VkPipelineRasterizationStateCreateInfo raster{};
                        raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
                        raster.polygonMode = VK_POLYGON_MODE_FILL;
                        raster.lineWidth = 1.0F;

                        bool blend, cullOff, depthWriteOff;
                        stageBlendConfig(s, blend, cullOff, depthWriteOff);
                        raster.cullMode = cullOff ? VK_CULL_MODE_NONE : VK_CULL_MODE_BACK_BIT;
                        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

                        VkPipelineMultisampleStateCreateInfo multisample{};
                        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
                        const uint32_t samples = std::max(1u, impl_->host.sampleCount);
                        multisample.rasterizationSamples = static_cast<VkSampleCountFlagBits>(samples);

                        VkPipelineDepthStencilStateCreateInfo depth{};
                        depth.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
                        depth.depthTestEnable = VK_TRUE;
                        depth.depthWriteEnable = depthWriteOff ? VK_FALSE : VK_TRUE;
                        depth.depthCompareOp = VK_COMPARE_OP_LESS_OR_EQUAL;

                        VkPipelineColorBlendAttachmentState colorAttachment{};
                        colorAttachment.blendEnable = blend ? VK_TRUE : VK_FALSE;
                        colorAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
                        colorAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                        colorAttachment.colorBlendOp = VK_BLEND_OP_ADD;
                        colorAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
                        colorAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                        colorAttachment.alphaBlendOp = VK_BLEND_OP_ADD;
                        colorAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

                        VkPipelineColorBlendStateCreateInfo colorBlend{};
                        colorBlend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
                        colorBlend.attachmentCount = 1;
                        colorBlend.pAttachments = &colorAttachment;

                        const VkDynamicState dynamicStates[2] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
                        VkPipelineDynamicStateCreateInfo dynamic{};
                        dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
                        dynamic.dynamicStateCount = 2;
                        dynamic.pDynamicStates = dynamicStates;

                        VkGraphicsPipelineCreateInfo pipelineInfo{};
                        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
                        pipelineInfo.stageCount = 2;
                        pipelineInfo.pStages = stages;
                        pipelineInfo.pVertexInputState = &vertexInput;
                        pipelineInfo.pInputAssemblyState = &inputAssembly;
                        pipelineInfo.pViewportState = &viewportState;
                        pipelineInfo.pRasterizationState = &raster;
                        pipelineInfo.pMultisampleState = &multisample;
                        pipelineInfo.pDepthStencilState = &depth;
                        pipelineInfo.pColorBlendState = &colorBlend;
                        pipelineInfo.pDynamicState = &dynamic;
                        pipelineInfo.layout = pipelineLayoutH(impl_->pipelineLayout);
                        pipelineInfo.renderPass = renderPassH(header.renderPass);
                        pipelineInfo.subpass = 0;

                        uint64_t pipeline = 0;
                        const uint32_t result = impl_->d().createGraphicsPipelines(impl_->device(), 0, 1, &pipelineInfo, &pipeline);
                        impl_->d().destroyShaderModule(impl_->device(), vertModule);
                        impl_->d().destroyShaderModule(impl_->device(), fragModule);
                        if (result != VK_SUCCESS) {
                                return false;
                        }
                        impl_->pipelines[s] = pipeline;
                }
                impl_->currentRenderPass = header.renderPass;
                impl_->shadersDirty = false;
        }

        // frame uniforms into the rotating UBO slot
        const uint64_t slot = impl_->frameCounter % UBO_SLOTS;
        float* ubo = reinterpret_cast<float*>(static_cast<uint8_t*>(impl_->uboMapped) + slot * UBO_BYTES);
        {
                uint8_t* raw = reinterpret_cast<uint8_t*>(ubo);
                std::memcpy(raw + UBO_P_OFF, header.projection, sizeof(header.projection));
                std::memcpy(raw + UBO_FOG_COLOR_OFF, header.fogColor, sizeof(header.fogColor));
                std::memcpy(raw + UBO_FOG_START_OFF, &header.fogStart, sizeof(float));
                std::memcpy(raw + UBO_FOG_END_OFF, &header.fogEnd, sizeof(float));
                std::memcpy(raw + UBO_LIGHT0_OFF, header.lightDir0, sizeof(header.lightDir0));
                std::memcpy(raw + UBO_LIGHT1_OFF, header.lightDir1, sizeof(header.lightDir1));
        }

        IVkDispatch& d = impl_->d();
        const uint64_t cmd = header.cmdBuffer;
        d.cmdSetViewport(cmd, 0.0F, static_cast<float>(header.viewportHeight), static_cast<float>(header.viewportWidth), -static_cast<float>(header.viewportHeight));
        d.cmdSetScissor(cmd, 0, 0, header.viewportWidth, header.viewportHeight);

        uint64_t pool = impl_->pools[impl_->frameCounter % POOL_RING];
        d.resetDescriptorPool(impl_->device(), pool);
        VkDescriptorSetAllocateInfo setAlloc{};
        setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        setAlloc.descriptorPool = poolH(pool);
        setAlloc.descriptorSetCount = 1;
        VkDescriptorSetLayout samplerLayoutHandle = setLayoutH(impl_->samplerSetLayout);
        setAlloc.pSetLayouts = &samplerLayoutHandle;

        uint64_t lastPipeline = 0;
        uint64_t lastTextureSet = 0;
        bool anyDrawn = false;

        for (uint32_t i = 0; i < header.itemCount; i++) {
                const auto* item = reinterpret_cast<const wrx::FrameItem*>(items + static_cast<size_t>(i) * sizeof(wrx::FrameItem));
                const auto ptrIt = impl_->meshIdToPtr.find(item->meshId);
                if (ptrIt == impl_->meshIdToPtr.end() || item->stage >= STAGE_COUNT) {
                        continue;
                }
                const auto gpuIt = impl_->gpuByMesh.find(ptrIt->second);
                if (gpuIt == impl_->gpuByMesh.end()) {
                        continue;
                }
                const Impl::MeshGpu& gpu = gpuIt->second;

                const auto* texture = reinterpret_cast<const wrx::FrameTexture*>(textures + static_cast<size_t>(item->textureIndex) * sizeof(wrx::FrameTexture));
                if (texture->view == 0) {
                        continue;
                }

                const uint64_t pipeline = impl_->pipelines[item->stage];
                if (pipeline == 0) {
                        continue;
                }
                if (pipeline != lastPipeline) {
                        d.cmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                        lastPipeline = pipeline;
                }

                uint64_t textureSet = 0;
                if (d.allocateDescriptorSets(impl_->device(), &setAlloc, &textureSet) != VK_SUCCESS) {
                        return anyDrawn; // pool exhausted this frame; keep what we recorded
                }
                VkDescriptorImageInfo imageInfo{};
                imageInfo.sampler = static_cast<VkSampler>(h2p(texture->sampler));
                imageInfo.imageView = static_cast<VkImageView>(h2p(texture->view));
                imageInfo.imageLayout = imageLayoutH(texture->layout != 0 ? texture->layout : 0x00000005 /* SHADER_READ_ONLY_OPTIMAL */);
                VkWriteDescriptorSet write{};
                write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                write.dstSet = setH(textureSet);
                write.dstBinding = 0;
                write.descriptorCount = 1;
                write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                write.pImageInfo = &imageInfo;
                d.updateDescriptorSets(impl_->device(), 1, &write, 0, nullptr);
                if (textureSet != lastTextureSet) {
                        const uint64_t sets[2] = {textureSet, impl_->uboSets[slot]};
                        d.cmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, impl_->pipelineLayout, SET_SAMPLER, 2, sets, 0, nullptr);
                        lastTextureSet = textureSet;
                }

                // push constants: vec4 colour (from packed) + mat4 MV + mat3 N
                float push[PUSH_BYTES / 4];
                const uint32_t packed = item->colorPacked;
                push[0] = static_cast<float>((packed >> 24) & 0xFF) * (1.0F / 255.0F);
                push[1] = static_cast<float>((packed >> 16) & 0xFF) * (1.0F / 255.0F);
                push[2] = static_cast<float>((packed >> 8) & 0xFF) * (1.0F / 255.0F);
                push[3] = static_cast<float>(packed & 0xFF) * (1.0F / 255.0F);
                std::memcpy(reinterpret_cast<uint8_t*>(push) + PUSH_MV_OFF, item->modelView, sizeof(item->modelView));
                std::memcpy(reinterpret_cast<uint8_t*>(push) + PUSH_N_OFF, item->normalMatrix, sizeof(item->normalMatrix));
                d.cmdPushConstants(cmd, impl_->pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, PUSH_BYTES, push);

                const uint64_t buffer = gpu.buffer;
                const uint64_t offset = 0;
                d.cmdBindVertexBuffers(cmd, 0, 1, &buffer, &offset);
                d.cmdBindIndexBuffer(cmd, buffer, gpu.indexOffset, VK_INDEX_TYPE_UINT32);
                d.cmdDrawIndexed(cmd, gpu.indexCount, 1, 0, 0, 0);
                anyDrawn = true;
        }

        impl_->frameCounter++;
        return anyDrawn || header.itemCount == 0;
}

} // namespace wrvk
