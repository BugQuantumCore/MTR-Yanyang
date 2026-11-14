package org.mtr.nativeobj;

import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stable keys for textures owned by Minecraft and VulkanMod.
 *
 * <p>The native recorder borrows image/view/sampler handles for each drain.
 * VulkanMod performs uploads and layout transitions outside its active render
 * pass, and also owns animation, dynamic updates and resource retirement.
 * Copying a second texture pool from the world-render callback both doubled
 * texture memory and recorded illegal Vulkan transfers inside that pass.</p>
 */
public final class NativeTextureManager {

        public static final NativeTextureManager INSTANCE = new NativeTextureManager();

        private final ConcurrentHashMap<ResourceLocation, Integer> keys = new ConcurrentHashMap<>();
        private final AtomicInteger nextKey = new AtomicInteger();

        private NativeTextureManager() {
        }

        /** Thread-safe registration; texture access itself happens on the render thread. */
        public int register(ResourceLocation texture) {
                return texture == null ? 0 : keys.computeIfAbsent(texture, ignored -> nextKey.incrementAndGet());
        }

        /** All textures are borrowed from the host, including static and dynamic textures. */
        public boolean isExternal(ResourceLocation texture) {
                return texture != null;
        }

        public int keyOfExisting(ResourceLocation texture) {
                final Integer key = texture == null ? null : keys.get(texture);
                return key == null ? -1 : key;
        }

        /** Host texture uploads already publish pixel updates; no second CPU/GPU copy is needed. */
        public void markDirty(ResourceLocation texture) {
        }

        public void flushDirty() {
        }

        /** Called after all native meshes are retired. Keys are never reused. */
        public void freePool() {
                keys.clear();
        }
}
