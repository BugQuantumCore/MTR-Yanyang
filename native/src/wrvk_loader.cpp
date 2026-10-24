// SPDX-License-Identifier: MIT
//
// wrvk_loader.cpp — the real IVkDispatch: the Vulkan loader is dlopen'd and
// every entry point resolves through vkGetInstanceProcAddr with the instance
// handle Java supplied. Nothing is linked at build time, so the library loads
// on machines without any Vulkan runtime and the caller can feature-detect.

#include "wrvk/wrvk.hpp"

#include <cstring>

#include "vulkan/vulkan.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace wrvk {

namespace {

void* handleToPtr(uint64_t handle) {
        return reinterpret_cast<void*>(static_cast<uintptr_t>(handle));
}

uint64_t ptrToHandle(void* pointer) {
        return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(pointer));
}

void* loadLibrary(const char* name) {
#ifdef _WIN32
        return reinterpret_cast<void*>(LoadLibraryA(name));
#else
        return dlopen(name, RTLD_NOW | RTLD_LOCAL);
#endif
}

void closeLibrary(void* library) {
#ifdef _WIN32
        if (library != nullptr) {
                FreeLibrary(static_cast<HMODULE>(library));
        }
#else
        if (library != nullptr) {
                dlclose(library);
        }
#endif
}

void* librarySymbol(void* library, const char* name) {
#ifdef _WIN32
        return reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(GetProcAddress(static_cast<HMODULE>(library), name)));
#else
        return dlsym(library, name);
#endif
}

class RealDispatch final : public IVkDispatch {
public:
        RealDispatch(void* library, void* instance)
                : library_(library), instance_(static_cast<VkInstance>(instance)) {
        }

        ~RealDispatch() override {
                closeLibrary(library_);
        }

        static std::unique_ptr<RealDispatch> create(const std::string& explicitLibraryPath, uint64_t instance) {
                const char* candidates[] = {
                        explicitLibraryPath.empty() ? nullptr : explicitLibraryPath.c_str(),
#if defined(_WIN32)
                        "vulkan-1.dll",
#elif defined(__APPLE__)
#if defined(__ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__)
                        // iOS/iPadOS hosts embed MoltenVK in the app bundle;
                        // the plain names below never resolve there.
                        "@executable_path/Frameworks/libMoltenVK.dylib",
                        "@executable_path/Frameworks/MoltenVK.framework/MoltenVK",
                        "@executable_path/libMoltenVK.dylib",
                        "@rpath/libMoltenVK.dylib",
#endif
                        "libvulkan.1.dylib", "libMoltenVK.dylib", "/usr/local/lib/libvulkan.dylib",
#else
                        // Android resolves the system loader through the
                        // plain soname (no libvulkan.so.1 there); desktop
                        // Linux hits the versioned one first.
                        "libvulkan.so.1", "libvulkan.so",
#endif
                };
                void* library = nullptr;
                for (const char* candidate : candidates) {
                        if (candidate == nullptr) {
                                continue;
                        }
                        library = loadLibrary(candidate);
                        if (library != nullptr) {
                                break;
                        }
                }
                if (library == nullptr) {
                        return nullptr;
                }
                auto* getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(librarySymbol(library, "vkGetInstanceProcAddr"));
                if (getProc == nullptr) {
                        closeLibrary(library);
                        return nullptr;
                }
                VkInstance vkInstance = static_cast<VkInstance>(handleToPtr(instance));
                std::unique_ptr<RealDispatch> dispatch(new RealDispatch(library, vkInstance));
#define WR_RESOLVE(name) \
        if ((dispatch->name##_ = reinterpret_cast<decltype(dispatch->name##_)>((getProc)(vkInstance, #name))) == nullptr) { \
                return nullptr; \
        }
                WR_RESOLVE(vkGetPhysicalDeviceMemoryProperties)
                WR_RESOLVE(vkCreateBuffer)
                WR_RESOLVE(vkGetBufferMemoryRequirements)
                WR_RESOLVE(vkAllocateMemory)
                WR_RESOLVE(vkMapMemory)
                WR_RESOLVE(vkUnmapMemory)
                WR_RESOLVE(vkBindBufferMemory)
                WR_RESOLVE(vkDestroyBuffer)
                WR_RESOLVE(vkFreeMemory)
                WR_RESOLVE(vkCreateImage)
                WR_RESOLVE(vkDestroyImage)
                WR_RESOLVE(vkGetImageMemoryRequirements)
                WR_RESOLVE(vkBindImageMemory)
                WR_RESOLVE(vkCreateImageView)
                WR_RESOLVE(vkDestroyImageView)
                WR_RESOLVE(vkCreateSampler)
                WR_RESOLVE(vkDestroySampler)
                WR_RESOLVE(vkCreateDescriptorSetLayout)
                WR_RESOLVE(vkCreatePipelineLayout)
                WR_RESOLVE(vkCreateDescriptorPool)
                WR_RESOLVE(vkAllocateDescriptorSets)
                WR_RESOLVE(vkUpdateDescriptorSets)
                WR_RESOLVE(vkResetDescriptorPool)
                WR_RESOLVE(vkDestroyDescriptorPool)
                WR_RESOLVE(vkDestroyPipelineLayout)
                WR_RESOLVE(vkDestroyDescriptorSetLayout)
                WR_RESOLVE(vkCreateShaderModule)
                WR_RESOLVE(vkDestroyShaderModule)
                WR_RESOLVE(vkCreateGraphicsPipelines)
                WR_RESOLVE(vkDestroyPipeline)
                WR_RESOLVE(vkDeviceWaitIdle)
                WR_RESOLVE(vkCmdSetViewport)
                WR_RESOLVE(vkCmdSetScissor)
                WR_RESOLVE(vkCmdBindPipeline)
                WR_RESOLVE(vkCmdBindVertexBuffers)
                WR_RESOLVE(vkCmdBindIndexBuffer)
                WR_RESOLVE(vkCmdBindDescriptorSets)
                WR_RESOLVE(vkCmdPushConstants)
                WR_RESOLVE(vkCmdDrawIndexed)
                WR_RESOLVE(vkCmdPipelineBarrier)
                WR_RESOLVE(vkCmdCopyBufferToImage)
#undef WR_RESOLVE
                return dispatch;
        }

        VkDevice dev(uint64_t device) const { return static_cast<VkDevice>(handleToPtr(device)); }
        VkCommandBuffer cmd(uint64_t commandBuffer) const { return static_cast<VkCommandBuffer>(handleToPtr(commandBuffer)); }

        uint32_t getPhysicalDeviceMemoryProperties(uint64_t physicalDevice, void* outProps) override {
                vkGetPhysicalDeviceMemoryProperties_(static_cast<VkPhysicalDevice>(handleToPtr(physicalDevice)),
                        static_cast<VkPhysicalDeviceMemoryProperties*>(outProps));
                return VK_SUCCESS;
        }

        uint32_t createBuffer(uint64_t device, const void* createInfo, uint64_t* outBuffer) override {
                VkBuffer buffer = VK_NULL_HANDLE;
                const uint32_t result = vkCreateBuffer_(dev(device), static_cast<const VkBufferCreateInfo*>(createInfo), nullptr, &buffer);
                *outBuffer = ptrToHandle(buffer);
                return result;
        }

        void getBufferMemoryRequirements(uint64_t device, uint64_t buffer, void* outRequirements) override {
                vkGetBufferMemoryRequirements_(dev(device), static_cast<VkBuffer>(handleToPtr(buffer)),
                        static_cast<VkMemoryRequirements*>(outRequirements));
        }

        uint32_t allocateMemory(uint64_t device, const void* allocInfo, uint64_t* outMemory) override {
                VkDeviceMemory memory = VK_NULL_HANDLE;
                const uint32_t result = vkAllocateMemory_(dev(device), static_cast<const VkMemoryAllocateInfo*>(allocInfo), nullptr, &memory);
                *outMemory = ptrToHandle(memory);
                return result;
        }

        uint32_t mapMemory(uint64_t device, uint64_t memory, uint64_t offset, uint64_t size, void** outData) override {
                return vkMapMemory_(dev(device), static_cast<VkDeviceMemory>(handleToPtr(memory)), offset, size, 0, outData);
        }

        void unmapMemory(uint64_t device, uint64_t memory) override {
                vkUnmapMemory_(dev(device), static_cast<VkDeviceMemory>(handleToPtr(memory)));
        }

        uint32_t bindBufferMemory(uint64_t device, uint64_t buffer, uint64_t memory, uint64_t offset) override {
                return vkBindBufferMemory_(dev(device), static_cast<VkBuffer>(handleToPtr(buffer)),
                        static_cast<VkDeviceMemory>(handleToPtr(memory)), offset);
        }

        void destroyBuffer(uint64_t device, uint64_t buffer) override {
                vkDestroyBuffer_(dev(device), static_cast<VkBuffer>(handleToPtr(buffer)), nullptr);
        }

        void freeMemory(uint64_t device, uint64_t memory) override {
                vkFreeMemory_(dev(device), static_cast<VkDeviceMemory>(handleToPtr(memory)), nullptr);
        }

        uint32_t createImage(uint64_t device, const void* createInfo, uint64_t* outImage) override {
                VkImage image = VK_NULL_HANDLE;
                const uint32_t result = vkCreateImage_(dev(device), static_cast<const VkImageCreateInfo*>(createInfo), nullptr, &image);
                *outImage = ptrToHandle(image);
                return result;
        }

        void destroyImage(uint64_t device, uint64_t image) override {
                vkDestroyImage_(dev(device), static_cast<VkImage>(handleToPtr(image)), nullptr);
        }

        void getImageMemoryRequirements(uint64_t device, uint64_t image, void* outRequirements) override {
                vkGetImageMemoryRequirements_(dev(device), static_cast<VkImage>(handleToPtr(image)),
                        static_cast<VkMemoryRequirements*>(outRequirements));
        }

        uint32_t bindImageMemory(uint64_t device, uint64_t image, uint64_t memory, uint64_t offset) override {
                return vkBindImageMemory_(dev(device), static_cast<VkImage>(handleToPtr(image)),
                        static_cast<VkDeviceMemory>(handleToPtr(memory)), offset);
        }

        uint32_t createImageView(uint64_t device, const void* createInfo, uint64_t* outView) override {
                VkImageView view = VK_NULL_HANDLE;
                const uint32_t result = vkCreateImageView_(dev(device), static_cast<const VkImageViewCreateInfo*>(createInfo), nullptr, &view);
                *outView = ptrToHandle(view);
                return result;
        }

        void destroyImageView(uint64_t device, uint64_t view) override {
                vkDestroyImageView_(dev(device), static_cast<VkImageView>(handleToPtr(view)), nullptr);
        }

        uint32_t createSampler(uint64_t device, const void* createInfo, uint64_t* outSampler) override {
                VkSampler sampler = VK_NULL_HANDLE;
                const uint32_t result = vkCreateSampler_(dev(device), static_cast<const VkSamplerCreateInfo*>(createInfo), nullptr, &sampler);
                *outSampler = ptrToHandle(sampler);
                return result;
        }

        void destroySampler(uint64_t device, uint64_t sampler) override {
                vkDestroySampler_(dev(device), static_cast<VkSampler>(handleToPtr(sampler)), nullptr);
        }

        uint32_t createDescriptorSetLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) override {
                VkDescriptorSetLayout layout = VK_NULL_HANDLE;
                const uint32_t result = vkCreateDescriptorSetLayout_(dev(device), static_cast<const VkDescriptorSetLayoutCreateInfo*>(createInfo), nullptr, &layout);
                *outLayout = ptrToHandle(layout);
                return result;
        }

        uint32_t createPipelineLayout(uint64_t device, const void* createInfo, uint64_t* outLayout) override {
                VkPipelineLayout layout = VK_NULL_HANDLE;
                const uint32_t result = vkCreatePipelineLayout_(dev(device), static_cast<const VkPipelineLayoutCreateInfo*>(createInfo), nullptr, &layout);
                *outLayout = ptrToHandle(layout);
                return result;
        }

        uint32_t createDescriptorPool(uint64_t device, const void* createInfo, uint64_t* outPool) override {
                VkDescriptorPool pool = VK_NULL_HANDLE;
                const uint32_t result = vkCreateDescriptorPool_(dev(device), static_cast<const VkDescriptorPoolCreateInfo*>(createInfo), nullptr, &pool);
                *outPool = ptrToHandle(pool);
                return result;
        }

        uint32_t allocateDescriptorSets(uint64_t device, const void* allocInfo, uint64_t* outSets) override {
                VkDescriptorSet set = VK_NULL_HANDLE;
                const auto* info = static_cast<const VkDescriptorSetAllocateInfo*>(allocInfo);
                VkDescriptorSetAllocateInfo single{};
                single.sType = info->sType;
                single.descriptorPool = info->descriptorPool;
                single.descriptorSetCount = 1;
                single.pSetLayouts = info->pSetLayouts;
                const uint32_t result = vkAllocateDescriptorSets_(dev(device), &single, &set);
                *outSets = ptrToHandle(set);
                return result;
        }

        void updateDescriptorSets(uint64_t device, uint32_t writeCount, const void* writes, uint32_t, const void*) override {
                vkUpdateDescriptorSets_(dev(device), writeCount, static_cast<const VkWriteDescriptorSet*>(writes), 0, nullptr);
        }

        void resetDescriptorPool(uint64_t device, uint64_t pool) override {
                vkResetDescriptorPool_(dev(device), static_cast<VkDescriptorPool>(handleToPtr(pool)), 0);
        }

        void destroyDescriptorPool(uint64_t device, uint64_t pool) override {
                vkDestroyDescriptorPool_(dev(device), static_cast<VkDescriptorPool>(handleToPtr(pool)), nullptr);
        }

        void destroyPipelineLayout(uint64_t device, uint64_t layout) override {
                vkDestroyPipelineLayout_(dev(device), static_cast<VkPipelineLayout>(handleToPtr(layout)), nullptr);
        }

        void destroyDescriptorSetLayout(uint64_t device, uint64_t layout) override {
                vkDestroyDescriptorSetLayout_(dev(device), static_cast<VkDescriptorSetLayout>(handleToPtr(layout)), nullptr);
        }

        uint32_t createShaderModule(uint64_t device, const void* createInfo, uint64_t* outModule) override {
                VkShaderModule module = VK_NULL_HANDLE;
                const uint32_t result = vkCreateShaderModule_(dev(device), static_cast<const VkShaderModuleCreateInfo*>(createInfo), nullptr, &module);
                *outModule = ptrToHandle(module);
                return result;
        }

        void destroyShaderModule(uint64_t device, uint64_t module) override {
                vkDestroyShaderModule_(dev(device), static_cast<VkShaderModule>(handleToPtr(module)), nullptr);
        }

        uint32_t createGraphicsPipelines(uint64_t device, uint64_t, uint32_t count, const void* createInfos, uint64_t* outPipelines) override {
                VkPipeline pipeline = VK_NULL_HANDLE;
                VkGraphicsPipelineCreateInfo single = static_cast<const VkGraphicsPipelineCreateInfo*>(createInfos)[0];
                (void)count;
                const uint32_t result = vkCreateGraphicsPipelines_(dev(device), VK_NULL_HANDLE, 1, &single, nullptr, &pipeline);
                *outPipelines = ptrToHandle(pipeline);
                return result;
        }

        void destroyPipeline(uint64_t device, uint64_t pipeline) override {
                vkDestroyPipeline_(dev(device), static_cast<VkPipeline>(handleToPtr(pipeline)), nullptr);
        }

        uint32_t deviceWaitIdle(uint64_t device) override {
                return vkDeviceWaitIdle_(dev(device));
        }

        void cmdSetViewport(uint64_t commandBuffer, float x, float y, float width, float height) override {
                VkViewport viewport{};
                viewport.x = x;
                viewport.y = y;
                viewport.width = width;
                viewport.height = height;
                viewport.minDepth = 0.0F;
                viewport.maxDepth = 1.0F;
                vkCmdSetViewport_(cmd(commandBuffer), 0, 1, &viewport);
        }

        void cmdSetScissor(uint64_t commandBuffer, int32_t x, int32_t y, uint32_t width, uint32_t height) override {
                VkRect2D scissor{};
                scissor.offset.x = x;
                scissor.offset.y = y;
                scissor.extent.width = width;
                scissor.extent.height = height;
                vkCmdSetScissor_(cmd(commandBuffer), 0, 1, &scissor);
        }

        void cmdBindPipeline(uint64_t commandBuffer, uint32_t bindPoint, uint64_t pipeline) override {
                vkCmdBindPipeline_(cmd(commandBuffer), static_cast<VkPipelineBindPoint>(bindPoint),
                        static_cast<VkPipeline>(handleToPtr(pipeline)));
        }

        void cmdBindVertexBuffers(uint64_t commandBuffer, uint32_t first, uint32_t count, const uint64_t* buffers, const uint64_t* offsets) override {
                vkCmdBindVertexBuffers_(cmd(commandBuffer), first, count,
                        reinterpret_cast<const VkBuffer*>(buffers), offsets);
        }

        void cmdBindIndexBuffer(uint64_t commandBuffer, uint64_t buffer, uint64_t offset, uint32_t indexType) override {
                vkCmdBindIndexBuffer_(cmd(commandBuffer), static_cast<VkBuffer>(handleToPtr(buffer)),
                        offset, static_cast<VkIndexType>(indexType));
        }

        void cmdBindDescriptorSets(uint64_t commandBuffer, uint32_t bindPoint, uint64_t layout, uint32_t first, uint32_t count, const uint64_t* sets, uint32_t, const uint32_t*) override {
                vkCmdBindDescriptorSets_(cmd(commandBuffer), static_cast<VkPipelineBindPoint>(bindPoint),
                        static_cast<VkPipelineLayout>(handleToPtr(layout)),
                        first, count, reinterpret_cast<const VkDescriptorSet*>(sets), 0, nullptr);
        }

        void cmdPushConstants(uint64_t commandBuffer, uint64_t layout, uint32_t stages, uint32_t offset, uint32_t size, const void* data) override {
                vkCmdPushConstants_(cmd(commandBuffer), static_cast<VkPipelineLayout>(handleToPtr(layout)),
                        static_cast<VkShaderStageFlags>(stages), offset, size, data);
        }

        void cmdDrawIndexed(uint64_t commandBuffer, uint32_t indexCount, uint32_t instanceCount, uint32_t firstIndex, int32_t vertexOffset, uint32_t firstInstance) override {
                vkCmdDrawIndexed_(cmd(commandBuffer), indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }

        void cmdPipelineBarrier(uint64_t commandBuffer, uint32_t srcStageMask, uint32_t dstStageMask, uint32_t dependencyFlags, uint32_t memoryBarrierCount, const void* memoryBarriers, uint32_t bufferMemoryBarrierCount, const void* bufferMemoryBarriers, uint32_t imageMemoryBarrierCount, const void* imageMemoryBarriers) override {
                vkCmdPipelineBarrier_(cmd(commandBuffer),
                        static_cast<VkPipelineStageFlags>(srcStageMask), static_cast<VkPipelineStageFlags>(dstStageMask), dependencyFlags,
                        memoryBarrierCount, static_cast<const VkMemoryBarrier*>(memoryBarriers),
                        bufferMemoryBarrierCount, static_cast<const VkBufferMemoryBarrier*>(bufferMemoryBarriers),
                        imageMemoryBarrierCount, static_cast<const VkImageMemoryBarrier*>(imageMemoryBarriers));
        }

        void cmdCopyBufferToImage(uint64_t commandBuffer, uint64_t srcBuffer, uint64_t dstImage, uint32_t dstImageLayout, uint32_t regionCount, const void* regions) override {
                vkCmdCopyBufferToImage_(cmd(commandBuffer), static_cast<VkBuffer>(handleToPtr(srcBuffer)),
                        static_cast<VkImage>(handleToPtr(dstImage)), static_cast<VkImageLayout>(dstImageLayout),
                        regionCount, static_cast<const VkBufferImageCopy*>(regions));
        }

private:
        void* library_{nullptr};
        VkInstance instance_{VK_NULL_HANDLE};

#define WR_DECLARE(name) decltype(&name) name##_ = nullptr;
        WR_DECLARE(vkGetPhysicalDeviceMemoryProperties)
        WR_DECLARE(vkCreateBuffer)
        WR_DECLARE(vkGetBufferMemoryRequirements)
        WR_DECLARE(vkAllocateMemory)
        WR_DECLARE(vkMapMemory)
        WR_DECLARE(vkUnmapMemory)
        WR_DECLARE(vkBindBufferMemory)
        WR_DECLARE(vkDestroyBuffer)
        WR_DECLARE(vkFreeMemory)
        WR_DECLARE(vkCreateImage)
        WR_DECLARE(vkDestroyImage)
        WR_DECLARE(vkGetImageMemoryRequirements)
        WR_DECLARE(vkBindImageMemory)
        WR_DECLARE(vkCreateImageView)
        WR_DECLARE(vkDestroyImageView)
        WR_DECLARE(vkCreateSampler)
        WR_DECLARE(vkDestroySampler)
        WR_DECLARE(vkCreateDescriptorSetLayout)
        WR_DECLARE(vkCreatePipelineLayout)
        WR_DECLARE(vkCreateDescriptorPool)
        WR_DECLARE(vkAllocateDescriptorSets)
        WR_DECLARE(vkUpdateDescriptorSets)
        WR_DECLARE(vkResetDescriptorPool)
        WR_DECLARE(vkDestroyDescriptorPool)
        WR_DECLARE(vkDestroyPipelineLayout)
        WR_DECLARE(vkDestroyDescriptorSetLayout)
        WR_DECLARE(vkCreateShaderModule)
        WR_DECLARE(vkDestroyShaderModule)
        WR_DECLARE(vkCreateGraphicsPipelines)
        WR_DECLARE(vkDestroyPipeline)
        WR_DECLARE(vkDeviceWaitIdle)
        WR_DECLARE(vkCmdSetViewport)
        WR_DECLARE(vkCmdSetScissor)
        WR_DECLARE(vkCmdBindPipeline)
        WR_DECLARE(vkCmdBindVertexBuffers)
        WR_DECLARE(vkCmdBindIndexBuffer)
        WR_DECLARE(vkCmdBindDescriptorSets)
        WR_DECLARE(vkCmdPushConstants)
        WR_DECLARE(vkCmdDrawIndexed)
        WR_DECLARE(vkCmdPipelineBarrier)
        WR_DECLARE(vkCmdCopyBufferToImage)
#undef WR_DECLARE
};

} // namespace

std::unique_ptr<IVkDispatch> createRealDispatch(const std::string& explicitLibraryPath, uint64_t instance) {
        return RealDispatch::create(explicitLibraryPath, instance);
}

} // namespace wrvk
