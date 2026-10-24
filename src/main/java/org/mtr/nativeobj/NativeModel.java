package org.mtr.nativeobj;

import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.resource.RenderStage;

/**
 * A mesh set built by the native pipeline. One instance exists per
 * (material, render stage) bucket of one model, mirroring what the Java path
 * stores in a {@code NewOptimizedModel} — the difference is that the vertex
 * data never leaves the native side: the {@code meshId} addresses a GPU
 * buffer the C++ recorder owns.
 *
 * <p>Protocol v2: draws reference the texture by the native pool key
 * ({@link #textureKey}, assigned once per client session by
 * {@code NativeTextureManager}); the material Kd and the raw lightmap
 * brightness are combined natively at record time.</p>
 */
public final class NativeModel {

        public final int modelId;
        public final int meshId;
        public final ResourceLocation texture;
        /**
         * The native texture-pool key for {@link #texture} (0 when the texture
         * never registered — the C++ side then skips the draw). Stable for the
         * whole client session.
         */
        public final int textureKey;
        /**
         * The stage this mesh was built for. {@code null} follows the javagl
         * pipeline's semantics exactly: an un-staged entry is rescued to the
         * placement default by {@code NewOptimizedModelGroup.merge}, and is
         * dropped by {@code build} if it never merged. A queued draw always
         * carries a non-null stage.
         */
        @Nullable
        public final RenderStage renderStage;
        /**
         * The material Kd colour, r << 24 | g << 16 | b << 8 | a (1.20.x
         * packing). Kept for the build-reply decoding chain only — under
         * protocol v2 the draw path no longer reads it (the native recorder
         * multiplies Kd into the brightness itself).
         */
        public final int colorPacked;
        public final float[] aabb;

        public NativeModel(int modelId, int meshId, ResourceLocation texture, @Nullable RenderStage renderStage, int colorPacked, float[] aabb, int textureKey) {
                this.modelId = modelId;
                this.meshId = meshId;
                this.texture = texture;
                this.textureKey = textureKey;
                this.renderStage = renderStage;
                this.colorPacked = colorPacked;
                this.aabb = aabb;
        }

        /**
         * The colour the v1 draw item multiplied into the shader — the Kd times
         * the fork's scalar brightness ({@code light / 0xF} or full bright).
         * Unused by the v2 draw path (native math); kept for tooling that
         * inspects material colours.
         */
        public int colorFor(float lightMultiplier) {
                final int r = clampToByte(((colorPacked >>> 24) & 0xFF) / 255.0F * lightMultiplier);
                final int g = clampToByte(((colorPacked >>> 16) & 0xFF) / 255.0F * lightMultiplier);
                final int b = clampToByte(((colorPacked >>> 8) & 0xFF) / 255.0F * lightMultiplier);
                final int a = colorPacked & 0xFF;
                return r << 24 | g << 16 | b << 8 | a;
        }

        private static int clampToByte(float value) {
                return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
        }
}
