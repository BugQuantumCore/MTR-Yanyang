// SPDX-License-Identifier: MIT
//
// wrvk_stub.cpp — RecordingDispatch: the device-free test backend.
//
// It interprets the real Vulkan structs (the vendored headers) so the tests
// assert against exactly what the recorder would hand a driver, and it keeps
// object counters so leak tests can assert a clean teardown.

#include "wrvk/wrvk.hpp"

#include <cstring>
#include <new>
#include <unordered_map>

#include "vulkan/vulkan.h"

namespace wrvk {

namespace {
uint64_t asHandle(const void* pointer) {
	return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(pointer));
}
} // namespace

uint32_t RecordingDispatch::getPhysicalDeviceMemoryProperties(uint64_t, void* outProps) {
	auto* props = static_cast<VkPhysicalDeviceMemoryProperties*>(outProps);
	props->memoryTypeCount = 1;
	props->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
	props->memoryTypes[0].heapIndex = 0;
	props->memoryHeapCount = 1;
	props->memoryHeaps[0].size = 1ULL << 30;
	props->memoryHeaps[0].flags = VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
	return VK_SUCCESS;
}

uint32_t RecordingDispatch::createBuffer(uint64_t, const void* createInfo, uint64_t* outBuffer) {
	const auto* info = static_cast<const VkBufferCreateInfo*>(createInfo);
	const uint64_t handle = newHandle();
	buffers.emplace(handle, BufferRecord{info->size});
	liveBuffers++;
	*outBuffer = handle;
	log.push_back({Op::CreateBuffer, handle, info->size});
	return VK_SUCCESS;
}

void RecordingDispatch::getBufferMemoryRequirements(uint64_t, uint64_t buffer, void* outRequirements) {
	const auto it = buffers.find(buffer);
	auto* requirements = static_cast<VkMemoryRequirements*>(outRequirements);
	requirements->size = it == buffers.end() ? 0 : (it->second.size + 15) & ~15ULL;
	requirements->alignment = 16;
	requirements->memoryTypeBits = 1;
}

uint32_t RecordingDispatch::allocateMemory(uint64_t, const void* allocInfo, uint64_t* outMemory) {
	if (failAllocation) {
		return VK_ERROR_OUT_OF_HOST_MEMORY;
	}
	const auto* info = static_cast<const VkMemoryAllocateInfo*>(allocInfo);
	const uint64_t handle = newHandle();
	memorySizes.emplace(handle, info->allocationSize);
	liveMemory++;
	*outMemory = handle;
	log.push_back({Op::AllocateMemory, handle, info->allocationSize});
	return VK_SUCCESS;
}

uint32_t RecordingDispatch::mapMemory(uint64_t, uint64_t memory, uint64_t, uint64_t, void** outData) {
	mapped.emplace(memory, std::vector<uint8_t>(memorySizes[memory]));
	*outData = mapped[memory].data();
	log.push_back({Op::MapMemory, memory, memorySizes[memory]});
	return VK_SUCCESS;
}

void RecordingDispatch::unmapMemory(uint64_t, uint64_t memory) {
	mapped.erase(memory);
	log.push_back({Op::UnmapMemory, memory});
}

uint32_t RecordingDispatch::bindBufferMemory(uint64_t, uint64_t buffer, uint64_t memory, uint64_t offset) {
	bufferMemory[buffer] = memory;
	bufferOffset[buffer] = offset;
	log.push_back({Op::BindBufferMemory, buffer, memory, offset});
	return VK_SUCCESS;
}

void RecordingDispatch::destroyBuffer(uint64_t, uint64_t buffer) {
	liveBuffers--;
	buffers.erase(buffer);
	bufferMemory.erase(buffer);
	log.push_back({Op::DestroyBuffer, buffer});
}

void RecordingDispatch::freeMemory(uint64_t, uint64_t memory) {
	liveMemory--;
	memorySizes.erase(memory);
	mapped.erase(memory);
	log.push_back({Op::FreeMemory, memory});
}

uint32_t RecordingDispatch::createDescriptorSetLayout(uint64_t, const void*, uint64_t* outLayout) {
	*outLayout = newHandle();
	liveDescriptorSetLayouts++;
	log.push_back({Op::CreateDescriptorSetLayout, *outLayout});
	return VK_SUCCESS;
}

uint32_t RecordingDispatch::createPipelineLayout(uint64_t, const void*, uint64_t* outLayout) {
	*outLayout = newHandle();
	livePipelineLayouts++;
	log.push_back({Op::CreatePipelineLayout, *outLayout});
	return VK_SUCCESS;
}

uint32_t RecordingDispatch::createDescriptorPool(uint64_t, const void*, uint64_t* outPool) {
	*outPool = newHandle();
	liveDescriptorPools++;
	log.push_back({Op::CreateDescriptorPool, *outPool});
	return VK_SUCCESS;
}

uint32_t RecordingDispatch::allocateDescriptorSets(uint64_t, const void* allocInfo, uint64_t* outSets) {
	if (failAllocation) {
		return VK_ERROR_OUT_OF_POOL_MEMORY;
	}
	*outSets = newHandle();
	poolSets[asHandle(static_cast<const VkDescriptorSetAllocateInfo*>(allocInfo)->descriptorPool)].push_back(*outSets);
	log.push_back({Op::AllocateDescriptorSets, *outSets});
	return VK_SUCCESS;
}

void RecordingDispatch::updateDescriptorSets(uint64_t, uint32_t, const void*, uint32_t, const void*) {
	log.push_back({Op::UpdateDescriptorSets});
}

void RecordingDispatch::resetDescriptorPool(uint64_t, uint64_t pool) {
	poolSets[pool].clear();
	log.push_back({Op::ResetDescriptorPool, pool});
}

void RecordingDispatch::destroyDescriptorPool(uint64_t, uint64_t pool) {
	liveDescriptorPools--;
	poolSets.erase(pool);
	log.push_back({Op::DestroyDescriptorPool, pool});
}

void RecordingDispatch::destroyPipelineLayout(uint64_t, uint64_t layout) {
	livePipelineLayouts--;
	log.push_back({Op::DestroyPipelineLayout, layout});
}

void RecordingDispatch::destroyDescriptorSetLayout(uint64_t, uint64_t layout) {
	liveDescriptorSetLayouts--;
	log.push_back({Op::DestroyDescriptorSetLayout, layout});
}

uint32_t RecordingDispatch::createShaderModule(uint64_t, const void*, uint64_t* outModule) {
	*outModule = newHandle();
	liveShaderModules++;
	log.push_back({Op::CreateShaderModule, *outModule});
	return VK_SUCCESS;
}

void RecordingDispatch::destroyShaderModule(uint64_t, uint64_t module) {
	liveShaderModules--;
	log.push_back({Op::DestroyShaderModule, module});
}

uint32_t RecordingDispatch::createGraphicsPipelines(uint64_t, uint64_t, uint32_t count, const void* createInfos, uint64_t* outPipelines) {
	const auto* infos = static_cast<const VkGraphicsPipelineCreateInfo*>(createInfos);
	for (uint32_t i = 0; i < count; i++) {
		outPipelines[i] = newHandle();
		livePipelines++;
		createdPipelineRenderPass[outPipelines[i]] = asHandle(infos[i].renderPass);
		createdPipelineLayout[outPipelines[i]] = asHandle(infos[i].layout);
	}
	log.push_back({Op::CreateGraphicsPipelines, outPipelines[0]});
	return VK_SUCCESS;
}

void RecordingDispatch::destroyPipeline(uint64_t, uint64_t pipeline) {
	livePipelines--;
	createdPipelineRenderPass.erase(pipeline);
	createdPipelineLayout.erase(pipeline);
	log.push_back({Op::DestroyPipeline, pipeline});
}

uint32_t RecordingDispatch::deviceWaitIdle(uint64_t device) {
	log.push_back({Op::DeviceWaitIdle, device});
	return VK_SUCCESS;
}

void RecordingDispatch::cmdSetViewport(uint64_t cmd, float x, float y, float width, float height) {
	const auto bits = [](float value) {
		uint32_t raw;
		std::memcpy(&raw, &value, sizeof(raw));
		return static_cast<uint64_t>(raw);
	};
	log.push_back({Op::CmdSetViewport, cmd, bits(x), bits(y), bits(width)});
	lastViewport[0] = x;
	lastViewport[1] = y;
	lastViewport[2] = width;
	lastViewport[3] = height;
}

void RecordingDispatch::cmdSetScissor(uint64_t cmd, int32_t x, int32_t y, uint32_t width, uint32_t height) {
	log.push_back({Op::CmdSetScissor, cmd, static_cast<uint64_t>(x), static_cast<uint64_t>(y), width});
	lastScissor[0] = x;
	lastScissor[1] = y;
	lastScissor[2] = static_cast<int32_t>(width);
	lastScissor[3] = static_cast<int32_t>(height);
}

void RecordingDispatch::cmdBindPipeline(uint64_t cmd, uint32_t bindPoint, uint64_t pipeline) {
	log.push_back({Op::CmdBindPipeline, cmd, pipeline, bindPoint});
	lastBoundPipeline = pipeline;
}

void RecordingDispatch::cmdBindVertexBuffers(uint64_t cmd, uint32_t first, uint32_t count, const uint64_t* buffers, const uint64_t* offsets) {
	log.push_back({Op::CmdBindVertexBuffers, cmd, buffers[0], offsets[0], count});
	lastVertexBuffer = buffers[0];
	(void)first;
}

void RecordingDispatch::cmdBindIndexBuffer(uint64_t cmd, uint64_t buffer, uint64_t offset, uint32_t indexType) {
	log.push_back({Op::CmdBindIndexBuffer, cmd, buffer, offset, indexType});
	lastIndexBuffer = buffer;
}

void RecordingDispatch::cmdBindDescriptorSets(uint64_t cmd, uint32_t bindPoint, uint64_t layout, uint32_t first, uint32_t count, const uint64_t* sets, uint32_t, const uint32_t*) {
	log.push_back({Op::CmdBindDescriptorSets, cmd, sets[0], layout, count});
	lastBoundSets.assign(sets, sets + count);
	(void)first;
	(void)bindPoint;
}

void RecordingDispatch::cmdPushConstants(uint64_t cmd, uint64_t layout, uint32_t stages, uint32_t offset, uint32_t size, const void* data) {
	log.push_back({Op::CmdPushConstants, cmd, layout, (static_cast<uint64_t>(stages) << 32) | offset, size});
	std::array<float, 32> copy{};
	std::memcpy(copy.data(), data, size < 128 ? size : 128);
	pushConstants.push_back(copy);
}

void RecordingDispatch::cmdDrawIndexed(uint64_t cmd, uint32_t indexCount, uint32_t instanceCount, uint32_t firstIndex, int32_t vertexOffset, uint32_t firstInstance) {
	log.push_back({Op::CmdDrawIndexed, cmd, indexCount, static_cast<uint64_t>(firstIndex) << 32 | vertexOffset, firstInstance});
	drawCalls++;
	(void)instanceCount;
}

void RecordingDispatch::mapBuffer(uint64_t buffer, uint64_t size, void** outData) {
	buffers.emplace(buffer, BufferRecord{size});
	*outData = mmapScratch.data();
}

uint8_t* RecordingDispatch::mappedData(uint64_t buffer) {
	const auto it = bufferMemory.find(buffer);
	if (it == bufferMemory.end()) {
		return nullptr;
	}
	const auto mappedIt = mapped.find(it->second);
	return mappedIt == mapped.end() ? nullptr : mappedIt->second.data();
}

uint64_t RecordingDispatch::mappedSize(uint64_t buffer) {
	const auto it = buffers.find(buffer);
	return it == buffers.end() ? 0 : it->second.size;
}

void RecordingDispatch::eraseAllObjects() {
	buffers.clear();
	bufferMemory.clear();
	bufferOffset.clear();
	memorySizes.clear();
	mapped.clear();
	poolSets.clear();
	createdPipelineRenderPass.clear();
	createdPipelineLayout.clear();
}

} // namespace wrvk
