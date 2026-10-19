package org.mtr.nativeobj;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Reflection bridge to VulkanMod's internals. Everything here is
 * feature-detected: a single missing method, field or class disables the
 * native path with one log line instead of crashing the client.
 *
 * <p>None of {@code net.vulkanmod} is on the compile-time class path, so all
 * access goes through {@link Class#forName} and reflection — the same
 * structural-resolution discipline the 1.20.x pose-matrix fix used for the
 * yarn/intermediary divergence.</p>
 */
final class VulkanHostAccess {

        private static final boolean VULKANMOD_PRESENT = isClassPresent("net.vulkanmod.Initializer");

        private @Nullable Method getVkDevice;
        private @Nullable Field instanceField;
        private @Nullable Method getGraphicsQueue;
        private @Nullable Method queueHandle;
        private @Nullable Method getRenderer;
        private @Nullable Field currentCmdBufferField;
        private @Nullable Method getBoundRenderPass;
        private @Nullable Method getMainPass;
        private @Nullable Method renderPassId;
        private @Nullable Method swapChainGetter;
        private @Nullable Field swapChainFormat;
        private @Nullable Method textureGetVulkanImage;
        private @Nullable Method imageGetId;
        private @Nullable Method imageGetView;
        private @Nullable Method imageGetSampler;
        private @Nullable Field lightDirection0;
        private @Nullable Field lightDirection1;

        private VulkanHostAccess() {
        }

        /** Resolve everything once; returns null (logged) when anything is missing. */
        @Nullable
        static VulkanHostAccess resolve() {
                if (!VULKANMOD_PRESENT) {
                        return null;
                }
                final VulkanHostAccess access = new VulkanHostAccess();
                if (!access.resolveAll()) {
                        return null;
                }
                return access;
        }

        private static boolean isClassPresent(String name) {
                try {
                        Class.forName(name, false, VulkanHostAccess.class.getClassLoader());
                        return true;
                } catch (ClassNotFoundException e) {
                        return false;
                }
        }

        private boolean resolveAll() {
                try {
                        // device + instance
                        final Class<?> vulkanClass = Class.forName("net.vulkanmod.vulkan.Vulkan");
                        getVkDevice = vulkanClass.getMethod("getVkDevice");

                        final Class<?> deviceManager = Class.forName("net.vulkanmod.vulkan.device.DeviceManager");
                        final Field physicalDeviceField = deviceManager.getField("physicalDevice");
                        final Field vkDeviceField = deviceManager.getField("vkDevice");
                        final Object vkDevice = vkDeviceField.get(null);

                        getGraphicsQueue = deviceManager.getMethod("getGraphicsQueue");
                        final Object graphicsQueue = getGraphicsQueue.invoke(null);
                        if (graphicsQueue == null) {
                                logFail("graphics queue is not initialised yet");
                                return false;
                        }
                        queueHandle = graphicsQueue.getClass().getMethod("queue");
                        final Object vkQueue = queueHandle.invoke(graphicsQueue);
                        if (vkQueue == null) {
                                logFail("queue handle is null");
                                return false;
                        }

                        // queue family: QueueFamilyIndices.graphicsFamily through the queue's static helper
                        int resolvedQueueFamily = -1;
                        try {
                                final Method getQueueFamilies = graphicsQueue.getClass().getMethod("getQueueFamilies");
                                final Object indices = getQueueFamilies.invoke(null);
                                final Field graphicsFamily = indices.getClass().getField("graphicsFamily");
                                resolvedQueueFamily = graphicsFamily.getInt(indices);
                        } catch (ReflectiveOperationException e) {
                                // fall back to the family enum's ordinal on the concrete queue class
                                final Field familyField = graphicsQueue.getClass().getDeclaredField("family");
                                familyField.setAccessible(true);
                                queueFamilyIndex = ((Enum<?>) familyField.get(graphicsQueue)).ordinal();
                        }

                        // renderer + command buffer + render pass
                        final Class<?> rendererClass = Class.forName("net.vulkanmod.vulkan.Renderer");
                        getRenderer = rendererClass.getMethod("getInstance");
                        currentCmdBufferField = rendererClass.getDeclaredField("currentCmdBuffer");
                        currentCmdBufferField.setAccessible(true);
                        getBoundRenderPass = rendererClass.getMethod("getBoundRenderPass");
                        getMainPass = rendererClass.getMethod("getMainPass");

                        final Class<?> renderPassClass = Class.forName("net.vulkanmod.vulkan.framebuffer.RenderPass");
                        renderPassId = renderPassClass.getMethod("getId");

                        // swapchain colour format (for pipeline compatibility logging only)
                        try {
                                swapChainGetter = vulkanClass.getMethod("getSwapChain");
                                final Class<?> swapChainClass = Class.forName("net.vulkanmod.vulkan.framebuffer.SwapChain");
                                swapChainFormat = swapChainClass.getDeclaredField("format");
                                swapChainFormat.setAccessible(true);
                        } catch (ReflectiveOperationException ignored) {
                                swapChainGetter = null;
                                swapChainFormat = null;
                        }

                        // textures: AbstractTexture -> getVulkanImage() (mixin-injected) -> VulkanImage
                        textureGetVulkanImage = AbstractTexture.class.getMethod("getVulkanImage");
                        final Class<?> vulkanImageClass = Class.forName("net.vulkanmod.vulkan.texture.VulkanImage");
                        imageGetId = vulkanImageClass.getMethod("getId");
                        imageGetView = vulkanImageClass.getMethod("getImageView");
                        imageGetSampler = vulkanImageClass.getMethod("getSampler");

                        // directional light state (exactly what VulkanMod's patched shaders sample)
                        final Class<?> vRenderSystem = Class.forName("net.vulkanmod.vulkan.VRenderSystem");
                        lightDirection0 = vRenderSystem.getField("lightDirection0");
                        lightDirection1 = vRenderSystem.getField("lightDirection1");

                        // instance handle: Vulkan.instance is private; resolved lazily through
                        // instanceHandle() because the physical device + device are all the
                        // pipeline needs eagerly
                        deviceHandle = address(vkDevice);
                        physicalDeviceHandle = address(physicalDeviceField.get(null));
                        queueHandle = address(vkQueue);
                        queueFamily = resolvedQueueFamily;
                        return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                        MTR.LOGGER.error("[wrvk] VulkanMod was detected but the native pipeline could not resolve its internals; the Java renderer stays active", e);
                        return false;
                }
        }

        private void logFail(String message) {
                MTR.LOGGER.error("[wrvk] Vulkan handle resolution failed: {}", message);
        }

        // resolved handles, captured once at resolve() time
        private long deviceHandle;
        private long physicalDeviceHandle;
        private long queueHandle;
        private int queueFamily;
        private long instanceHandle;

        private static long address(Object lwjglHandle) {
                if (lwjglHandle == null) {
                        return 0;
                }
                try {
                        final Method address = lwjglHandle.getClass().getMethod("address");
                        return (Long) address.invoke(lwjglHandle);
                } catch (ReflectiveOperationException e) {
                        return 0;
                }
        }

        long deviceHandle() {
                return deviceHandle;
        }

        long physicalDeviceHandle() {
                return physicalDeviceHandle;
        }

        long queueHandle() {
                return queueHandle;
        }

        int queueFamily() {
                return queueFamily;
        }

        /** VulkanMod's instance: private static field on net.vulkanmod.vulkan.Vulkan. */
        long instanceHandle() {
                if (instanceHandle == 0 && instanceField == null) {
                        try {
                                final Class<?> vulkanClass = Class.forName("net.vulkanmod.vulkan.Vulkan");
                                instanceField = vulkanClass.getDeclaredField("instance");
                                instanceField.setAccessible(true);
                                instanceHandle = address(instanceField.get(null));
                        } catch (ReflectiveOperationException e) {
                                instanceHandle = -1; // sentinel: unresolved
                        }
                }
                return Math.max(0, instanceHandle);
        }

        /**
         * The command buffer VulkanMod is recording right now. Must be called on
         * the render thread between beginFrame and endFrame — which is where the
         * MTR drain runs, so the value is always mid-recording and legal to append
         * to (Vulkan command buffers are externally synchronised per thread; the
         * FFM transport records on this very thread, and the shm poller records
         * only while this thread is blocked in the bounded ack wait).
         */
        long currentCommandBuffer() {
                try {
                        final Object renderer = getRenderer.invoke(null);
                        final Object commandBuffer = currentCmdBufferField.get(renderer);
                        return address(commandBuffer);
                } catch (ReflectiveOperationException e) {
                        return 0;
                }
        }

        long boundRenderPass() {
                try {
                        final Object renderer = getRenderer.invoke(null);
                        Object renderPass = getBoundRenderPass.invoke(renderer);
                        if (renderPass == null) {
                                renderPass = getMainPass.invoke(renderer);
                        }
                        if (renderPass == null) {
                                return 0;
                        }
                        return (Long) renderPassId.invoke(renderPass);
                } catch (ReflectiveOperationException e) {
                        return 0;
                }
        }

        int colorFormat() {
                try {
                        final Object swapChain = swapChainGetter.invoke(null);
                        return swapChainFormat == null || swapChain == null ? 37 : swapChainFormat.getInt(swapChain);
                } catch (ReflectiveOperationException e) {
                        return 37; // B8G8R8A8_UNORM, the overwhelmingly common swapchain format
                }
        }

        /** {x, y, z} view-space light direction 0, or the vanilla overworld default. */
        float[] lightDirection0() {
                return readLightDirection(lightDirection0, new float[]{0.2F, 1.0F, -0.7F});
        }

        float[] lightDirection1() {
                return readLightDirection(lightDirection1, new float[]{-0.2F, 1.0F, 0.7F});
        }

        private float[] readLightDirection(@Nullable Field field, float[] fallback) {
                if (field == null) {
                        return normalise(fallback);
                }
                try {
                        final Object mappedBuffer = field.get(null);
                        final ByteBuffer buffer = (ByteBuffer) field.getType().getField("buffer").get(mappedBuffer);
                        return normalise(new float[]{buffer.getFloat(0), buffer.getFloat(4), buffer.getFloat(8)});
                } catch (ReflectiveOperationException | RuntimeException e) {
                        return normalise(fallback);
                }
        }

        private static float[] normalise(float[] vector) {
                float length = 0;
                for (final float value : vector) {
                        length += value * value;
                }
                length = (float) Math.sqrt(length);
                if (length < 1.0E-5F) {
                        return new float[]{0, 1, 0};
                }
                return new float[]{vector[0] / length, vector[1] / length, vector[2] / length};
        }

        /**
         * Resolve a model texture to {image, view, sampler} Vulkan handles. The
         * texture is owned by VulkanMod; the native side never frees it, so a
         * texture being recycled by VulkanMod cannot leak through us.
         */
        long[] textureHandles(ResourceLocation texture) {
                try {
                        final TextureManager textureManager = Minecraft.getInstance().getTextureManager();
                        final AbstractTexture abstractTexture = textureManager.getTexture(texture);
                        if (abstractTexture == null) {
                                return new long[3];
                        }
                        final Object vulkanImage = textureGetVulkanImage.invoke(abstractTexture);
                        if (vulkanImage == null) {
                                return new long[3];
                        }
                        return new long[]{
                                        (Long) imageGetId.invoke(vulkanImage),
                                        (Long) imageGetView.invoke(vulkanImage),
                                        (Long) imageGetSampler.invoke(vulkanImage)
                        };
                } catch (ReflectiveOperationException | RuntimeException e) {
                        return new long[3];
                }
        }
}
