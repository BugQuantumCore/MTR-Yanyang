package org.mtr.nativeobj;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.mtr.MTR;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The native texture pool's Java-side bookkeeper (protocol v2).
 *
 * <p>Every texture a native model references is registered here once and
 * receives a stable pool key ({@code 1, 2, 3, …} — keys live for the whole
 * client session and are never reused or reassigned, so draw items and
 * per-frame external overrides can reference them without ambiguity).
 * Static textures are uploaded once through {@code MSG_UPLOAD_TEXTURE}
 * (RGBA8 pixels read straight from the resource manager); animated textures
 * (a {@code .mcmeta} companion exists) stay <b>external</b> — VulkanMod
 * owns their images, and the frame recorder resolves their handles per
 * frame instead.</p>
 *
 * <p>Dynamic textures (MTR's {@code DynamicTextureCache}: PIDS displays,
 * route maps, station names) are re-uploaded when their pixels change:
 * {@link #markDirty} records the identifier, and the render drain calls
 * {@link #flushDirty()} once per frame, re-reading the live
 * {@link DynamicTexture} pixels and re-publishing the same key.</p>
 *
 * <p>Failure discipline: every Minecraft call is guarded — a texture that
 * cannot be read, decoded or published simply stays absent from the pool
 * and the C++ recorder skips its draws; the Java side must never crash.
 * Every method is also safe when the native runtime is inactive (no-op).
 * Threading: {@link #register} may run on model-load worker threads or the
 * render thread; {@link #markDirty} may run anywhere; {@link #flushDirty}
 * and {@link #freePool} run on the render thread.</p>
 *
 * @see NativeFrameRecorder
 * @see WrxRegion (and native/include/wrx/wrx.hpp, docs/NATIVE_VULKAN.md, for the contract)
 */
public final class NativeTextureManager {

        public static final NativeTextureManager INSTANCE = new NativeTextureManager();

        /** Hard cap on pool uploads — a runaway pack must not exhaust native memory. */
        private static final int MAX_UPLOADED_TEXTURES = 4096;

        /** ResourceLocation → stable pool key (from 1; 0 means "never registered"). */
        private final ConcurrentHashMap<ResourceLocation, Integer> keys = new ConcurrentHashMap<>();
        private final AtomicInteger nextKey = new AtomicInteger();

        /** Animated (mcmeta) textures — never uploaded, resolved through VulkanMod per frame. */
        private final Set<ResourceLocation> external = ConcurrentHashMap.newKeySet();
        /** Textures currently living in the native pool (cleared by {@link #freePool}). */
        private final Set<ResourceLocation> uploaded = ConcurrentHashMap.newKeySet();
        /** Textures whose upload threw — retried once per reload, never per model. */
        private final Set<ResourceLocation> broken = ConcurrentHashMap.newKeySet();
        /** Dynamic textures whose pixels changed and need a re-upload on the next drain. */
        private final Set<ResourceLocation> dirty = ConcurrentHashMap.newKeySet();

        private boolean budgetWarned;

        private NativeTextureManager() {
        }

        /**
         * Assign (or return) the stable pool key for a model texture, and make
         * sure the texture is represented in the native pool: static textures
         * are uploaded from the resource manager, animated (mcmeta) textures
         * are marked external. Safe to call repeatedly and from any thread.
         *
         * @return the pool key, or 0 for a {@code null} texture (C++ skips the draw)
         */
        public int register(ResourceLocation texture) {
                if (texture == null) {
                        return 0;
                }
                final int key = keys.computeIfAbsent(texture, ignored -> nextKey.incrementAndGet());
                ensurePoolEntry(texture, key);
                return key;
        }

        /** True when the texture is animated (mcmeta) and handled through per-frame VulkanMod handles. */
        public boolean isExternal(ResourceLocation texture) {
                return texture != null && external.contains(texture);
        }

        /**
         * The stable pool key for an already-registered texture.
         *
         * @return the key, or -1 when the texture was never registered
         */
        public int keyOfExisting(ResourceLocation texture) {
                final Integer key = texture == null ? null : keys.get(texture);
                return key == null ? -1 : key;
        }

        /**
         * Record that a dynamic texture's pixels changed; the next
         * {@link #flushDirty()} re-uploads it. Cheap and safe when the native
         * runtime is inactive (the entry is dropped there).
         */
        public void markDirty(ResourceLocation texture) {
                if (texture != null) {
                        dirty.add(texture);
                }
        }

        /**
         * Re-upload every dirty, registered, non-external texture by reading
         * the live pixels from the texture manager. Called at the start of
         * each render drain. Entries that are unregistered, external, or no
         * longer backed by a live {@link DynamicTexture} are dropped — a
         * broken texture must not spin the drain.
         */
        public void flushDirty() {
                if (dirty.isEmpty()) {
                        return;
                }
                if (NativeRuntime.getInstance() == null) {
                        dirty.clear();
                        return;
                }
                final Iterator<ResourceLocation> iterator = dirty.iterator();
                while (iterator.hasNext()) {
                        final ResourceLocation texture = iterator.next();
                        iterator.remove();
                        if (external.contains(texture)) {
                                continue;
                        }
                        final Integer key = keys.get(texture);
                        if (key == null) {
                                continue; // never referenced by a native model
                        }
                        try {
                                final AbstractTexture abstractTexture = Minecraft.getInstance().getTextureManager().getTexture(texture);
                                if (abstractTexture instanceof DynamicTexture dynamicTexture) {
                                        final NativeImage image = dynamicTexture.getPixels();
                                        if (image != null) {
                                                upload(texture, key, image);
                                        }
                                }
                        } catch (Throwable throwable) {
                                MTR.LOGGER.error("[wrvk] native re-upload of dynamic texture [{}] failed", texture, throwable);
                        }
                }
        }

        /**
         * Retire every uploaded pool texture (resource reload: the models
         * referencing them were just freed). Publishes
         * {@code MSG_FREE_TEXTURE} for the uploaded keys and clears the
         * upload bookkeeping — keys stay assigned, and the next
         * {@link #register} re-uploads.
         */
        public void freePool() {
                if (NativeRuntime.getInstance() == null || uploaded.isEmpty()) {
                        return;
                }
                try {
                        final int[] poolKeys = new int[uploaded.size()];
                        int count = 0;
                        for (final ResourceLocation texture : uploaded) {
                                final Integer key = keys.get(texture);
                                if (key != null) {
                                        poolKeys[count++] = key;
                                }
                        }
                        final ByteBuffer body = ByteBuffer.allocate(4 + 4 * count).order(ByteOrder.LITTLE_ENDIAN);
                        body.putInt(count);
                        for (int i = 0; i < count; i++) {
                                body.putInt(poolKeys[i]);
                        }
                        publish(WrxRegion.MSG_FREE_TEXTURE, body);
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native texture pool free failed", throwable);
                } finally {
                        uploaded.clear();
                        broken.clear();
                }
        }

        /**
         * Bring a registered texture into its correct pool state exactly
         * once per state: external textures are only ever classified, static
         * textures only uploaded while absent from the pool.
         */
        private void ensurePoolEntry(ResourceLocation texture, int key) {
                if (NativeRuntime.getInstance() == null || external.contains(texture) || uploaded.contains(texture) || broken.contains(texture)) {
                        return;
                }
                try {
                        if (uploaded.size() >= MAX_UPLOADED_TEXTURES) {
                                warnBudgetOnce();
                                return;
                        }
                        if (hasAnimationMetadata(texture)) {
                                external.add(texture);
                                return;
                        }
                        final Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
                        if (resource.isEmpty()) {
                                return; // absent pack texture — the C++ side skips its draws
                        }
                        // The NativeImage is closed on every path (uploaded, budget-skipped
                        // or failed) — its w·h·4 off-heap bytes would otherwise sit
                        // until the GC cleaner runs, once per registration.
                        try (final InputStream stream = resource.get().open(); final NativeImage image = NativeImage.read(stream)) {
                                if (image != null) {
                                        upload(texture, key, image);
                                }
                        }
                } catch (Throwable throwable) {
                        if (broken.add(texture)) {
                                MTR.LOGGER.error("[wrvk] native texture upload failed for [{}]; its models are skipped by the native renderer until the next reload", texture, throwable);
                        }
                }
        }

        /**
         * Whether the texture has an animated {@code .mcmeta} companion in
         * the resource manager — the vanilla marker for frame-animated
         * textures, which VulkanMod keeps owning frame by frame.
         */
        private static boolean hasAnimationMetadata(ResourceLocation texture) {
                try {
                        final ResourceLocation mcmeta = ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), texture.getPath() + ".mcmeta");
                        return Minecraft.getInstance().getResourceManager().getResource(mcmeta).isPresent();
                } catch (Throwable throwable) {
                        return false;
                }
        }

        /** One-shot warn when the upload budget is exhausted. */
        private void warnBudgetOnce() {
                if (!budgetWarned) {
                        budgetWarned = true;
                        MTR.LOGGER.warn("[wrvk] native texture pool budget ({} textures) reached; further textures are skipped by the native renderer", MAX_UPLOADED_TEXTURES);
                }
        }

        /**
         * Encode and publish {@code MSG_UPLOAD_TEXTURE}: u32 key, u32 width,
         * u32 height, u32 flags, then RGBA8 pixels (row-major, top-left
         * origin). A repeated key re-uploads, so dynamic textures reuse it.
         */
        private void upload(ResourceLocation texture, int key, NativeImage image) {
                if (!uploaded.contains(texture) && uploaded.size() >= MAX_UPLOADED_TEXTURES) {
                        warnBudgetOnce();
                        return;
                }
                final int width = image.getWidth();
                final int height = image.getHeight();
                final ByteBuffer body = ByteBuffer.allocate(16 + width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
                body.putInt(key);
                body.putInt(width);
                body.putInt(height);
                body.putInt(0); // flags
                putPixelsRgba(body, image);
                publish(WrxRegion.MSG_UPLOAD_TEXTURE, body);
                uploaded.add(texture);
        }

        /**
         * RGBA bytes, row-major from the top-left (0, 0). The
         * {@link NativeImage} pixel packing differs across the stonecutter
         * targets: 1.21.1's {@code getPixelRGBA} returns ABGR, 1.21.4's
         * {@code getPixel} returns ARGB (see {@code RouteMapGenerator}'s
         * colour conversion for the same divergence).
         */
        private static void putPixelsRgba(ByteBuffer body, NativeImage image) {
                final int width = image.getWidth();
                final int height = image.getHeight();
                for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
//? if >= 1.21.4 {
                                final int argb = image.getPixel(x, y);
                                body.put((byte) (argb >>> 16)); // red
                                body.put((byte) (argb >>> 8));  // green
                                body.put((byte) argb);          // blue
                                body.put((byte) (argb >>> 24)); // alpha
//? } else {
                                /*final int abgr = image.getPixelRGBA(x, y);
                                body.put((byte) abgr);          // red
                                body.put((byte) (abgr >>> 8));  // green
                                body.put((byte) (abgr >>> 16)); // blue
                                body.put((byte) (abgr >>> 24)); // alpha
*///? }
                        }
                }
        }

        /** Wrap a message body in the u32-type envelope and publish it (no reply). */
        private static void publish(int type, ByteBuffer body) {
                final NativeRuntime runtime = NativeRuntime.getInstance();
                if (runtime == null) {
                        return;
                }
                final byte[] bodyBytes = new byte[body.position()];
                body.flip();
                body.get(bodyBytes);
                runtime.publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(type).put(bodyBytes).array());
        }
}
