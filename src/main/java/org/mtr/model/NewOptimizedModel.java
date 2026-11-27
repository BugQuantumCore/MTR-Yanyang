package org.mtr.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.nativeobj.NativeModel;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

//? if >= 1.21.4 {
import net.minecraft.client.renderer.CompiledShaderProgram;
//? } else {
/*import net.minecraft.client.renderer.ShaderInstance;
 *///? }

/**
 * One drawable mesh — a single texture, a single GPU vertex buffer, one draw call.
 *
 * <p>Instances are created during model build (see
 * {@link NewOptimizedModelGroup#build(VertexFormat.Mode)}) and live for the rest of
 * the parent loader's lifetime. The buffer is uploaded once via
 * {@link VertexBuffer#uploadStatic}; subsequent frames just call {@link #render} and
 * the GPU re-uses the stored vertices.</p>
 *
 * <p>If the supplied {@code callback} is {@code null}, no buffer is allocated and
 * {@link #render} no-ops. This is the path for empty groups.</p>

 * <p>Every instance that owns a GL buffer registers itself in a live-instance registry;
 * {@code CustomResourceLoader#reload()} closes them all via {@link #releaseAll()} before
 * the model caches are cleared, so no VAO/VBO outlives its model set.</p>
 *
 * <p><b>Naming note:</b> the "New" prefix is historical — there is no non-{@code New}
 * counterpart any more. See {@code docs/MIGRATIONS.md} §4 for the planned rename.</p>
 */
public final class NewOptimizedModel {

        public final ResourceLocation texture;
        @Nullable
        private final VertexBuffer vertexBuffer;
        /**
         * The native Vulkan mesh when this model was built through the native
         * pipeline (see {@code docs/NATIVE_VULKAN.md}); null means the GL path.
         * Dual-path build: when present, the GL {@link #vertexBuffer} is built
         * too, and the render drain decides per frame which path draws — native
         * under plain VulkanMod, GL under Beryl's pipeline or on plain OpenGL.
         */
        @Nullable
        private final NativeModel nativeModel;
        private final VertexFormat.Mode drawMode;
        @Nullable
        private final Consumer<VertexConsumer> capturedVertices;
        /**
         * Source vertex count of the retained emitter, for the multi-instance batching
         * heuristic in {@code MainRenderer#renderModel} (see {@link #getVertexCount()});
         * {@code -1} when unknown.
         */
        private final int vertexCount;
        private static final CapturedVertexConsumer CAPTURED_CONSUMER = new CapturedVertexConsumer();

        /**
         * Reusable model-view matrix scratch — render is single-threaded on the GL thread, so
         * a single buffer avoids a per-draw allocation. See {@code docs/PERFORMANCE.md} §3.2.
         */
        private static final Matrix4f MODEL_VIEW_SCRATCH = new Matrix4f();
        /**
         * Reusable shader-colour save/restore scratch, same render-thread-confinement pattern
         * as {@link #MODEL_VIEW_SCRATCH}. Copied element-wise because getShaderColor may hand
         * back RenderSystem's internal array, so it must never be kept across a draw.
         */
        private static final float[] SAVED_COLOR_SCRATCH = new float[4];
        /**
         * Live instances that own a GL vertex buffer, so a resource reload can close them all
         * before the model caches holding them are cleared (see {@link #releaseAll()}). Without
         * this, every F3+T / pack change / Beryl toggle leaked the entire previous model set's
         * VAO+VBO ids — the caches were cleared but the buffers were never closed. Guarded by
         * itself; {@code CustomResourceLoader.reload()} drains it on the render thread.
         */
        private static final Set<NewOptimizedModel> LIVE_GL_MODELS = new HashSet<>();
        /**
         * Pre-built {@code colorModulator} arrays for every value of {@code lightMultiplier}
         * the renderer ever emits. The renderer derives {@code lightMultiplier} from a 4-bit
         * lightmap value (16 levels), or sets {@code 1.0f} for full-bright stages. We pack
         * that into a 17-entry table — index {@code 0..15} for {@code i / 15f}, index
         * {@code 16} for the full-bright case. Callers that supply a different value (e.g.
         * fractional dimming for cinematic fades) fall back to a freshly allocated array.
         * See {@code docs/PERFORMANCE.md} §3.2 item 1.
         */
        private static final float[][] COLOR_MODULATORS;

        static {
                COLOR_MODULATORS = new float[17][];
                for (int i = 0; i < 16; i++) {
                        final float v = i / 15f;
                        COLOR_MODULATORS[i] = new float[]{v, v, v, 1f};
                }
                COLOR_MODULATORS[16] = new float[]{1f, 1f, 1f, 1f};
        }

        /**
         * @param texture  the texture this mesh draws with
         * @param drawMode the GL primitive type ({@code TRIANGLES} for OBJ, {@code QUADS} for
         *                 Blockbench)
         * @param callback the vertex-emitter callback invoked exactly once to populate the
         *                 buffer, or {@code null} to skip buffer creation entirely
         */
        public NewOptimizedModel(ResourceLocation texture, VertexFormat.Mode drawMode, @Nullable Consumer<VertexConsumer> callback) {
                this(texture, drawMode, callback, null);
        }

        public NewOptimizedModel(ResourceLocation texture, VertexFormat.Mode drawMode, @Nullable Consumer<VertexConsumer> callback, @Nullable NativeModel nativeModel) {
                this(texture, drawMode, callback, nativeModel, -1);
        }

        public NewOptimizedModel(ResourceLocation texture, VertexFormat.Mode drawMode, @Nullable Consumer<VertexConsumer> callback, @Nullable NativeModel nativeModel, int vertexCount) {
                final boolean capture = org.mtr.client.VulkanCompat.isRadianceActive();
                this.vertexBuffer = callback == null || capture ? null : createVertexBuffer(drawMode, DefaultVertexFormat.NEW_ENTITY, callback);
                // The emitter is retained on the GL path too (not just under Radiance): the
                // group's stored vertex data already outlives this model, so this only pins
                // the closure, and it lets the render drain replay MANY instances of one
                // small mesh through a single shared buffer (MainRenderer#renderModel's
                // multi-instance batching) instead of paying one draw call per instance —
                // hundreds of repeated rail segments and per-car vehicle parts otherwise
                // turn into hundreds of setupRenderState + drawWithShader rounds per frame,
                // which shader pipelines (Iris + a pack) make painfully expensive.
                this.capturedVertices = callback;
                this.drawMode = drawMode;
                this.texture = texture;
                this.nativeModel = nativeModel;
                this.vertexCount = callback == null ? -1 : vertexCount;
                if (vertexBuffer != null) {
                        synchronized (LIVE_GL_MODELS) {
                                LIVE_GL_MODELS.add(this);
                        }
                }
        }

        /** The native mesh handle, or null when this model renders through MC's GL path. */
        @Nullable
        public NativeModel getNativeModel() {
                return nativeModel;
        }

        /**
         * Whether this mesh MUST render through the captured (vertex-replay) path —
         * i.e. it has retained vertices but no GL buffer (the Radiance build mode).
         * Models that own a GL buffer but also retain their emitter answer
         * {@code false} here; they render per-instance VBO draws unless the drain's
         * multi-instance batching chooses to replay them instead.
         */
        public boolean usesCapturedRendering() {
                return vertexBuffer == null && capturedVertices != null;
        }

        /**
         * Whether this mesh can replay its source vertices into a shared buffer —
         * the prerequisite for multi-instance batching (see
         * {@code MainRenderer#renderModel}).
         */
        public boolean canAppendVertices() {
                return capturedVertices != null;
        }

        /**
         * The primitive mode the emitter's vertex stream is structured in ({@code QUADS}
         * for Blockbench models — 4 vertices per face, {@code TRIANGLES} for OBJ —
         * 3 vertices per face). The multi-instance batching replay must respect this:
         * the shared scheduled buffers are quad-based, so a triangle stream is replayed
         * through {@link #appendCapturedAsQuads} as degenerate quads
         * (v0, v1, v2, v2) — the zero-area second triangle is discarded by the
         * rasterizer and the geometry stays exact.
         */
        public VertexFormat.Mode getDrawMode() {
                return drawMode;
        }

        /**
         * Source vertex count for the batching heuristic, or {@code -1} when the
         * emitter was not retained / the count was not supplied at build time.
         */
        public int getVertexCount() {
                return vertexCount;
        }

        /**
         * Radiance captures RenderType.draw(MeshData), not direct VBO draws. One
         * mesh per model/stage batch gives it all instances with camera-relative
         * positions and transformed normals already in the vertex stream.
         * Ownership of the built mesh passes to RenderType/Radiance.
         */
        public void renderCaptured(RenderType layer, Consumer<VertexConsumer> instances) {
                final BufferBuilder builder = Tesselator.getInstance().begin(drawMode, DefaultVertexFormat.NEW_ENTITY);
                instances.accept(builder);
                final MeshData mesh = builder.build();
                if (mesh != null) {
                        layer.draw(mesh);
                }
        }

        public void appendCaptured(VertexConsumer target, PoseStack.Pose pose, int light) {
                if (capturedVertices != null) {
                        CAPTURED_CONSUMER.target = target;
                        CAPTURED_CONSUMER.pose = pose;
                        CAPTURED_CONSUMER.light = light;
                        capturedVertices.accept(CAPTURED_CONSUMER);
                }
        }

        /**
         * Multi-instance batching replay for {@code TRIANGLES}-mode meshes (OBJ models).
         *
         * <p>The shared scheduled buffers the batching replays into are quad-based (see
         * {@code MainRenderer#isQuadBasedQueuedLayer}), so a 3-vertices-per-triangle
         * stream cannot be appended directly — the builder would regroup it into
         * 4-vertex chunks and mangle everything after the first triangle. That was the
         * yanyang.19 rail-model regression: OBJ rails (including the default 3D rail)
         * on a long siding queued hundreds of instances, tripped the batching
         * threshold and rendered as sheared fragments on both the OpenGL and the
         * VulkanMod+Beryl (Java-renderer) paths.</p>
         *
         * <p>Fix: the emitter is recorded once (lazily, on the first batched replay)
         * and replayed as <b>degenerate quads</b> — every triangle (v0, v1, v2) is
         * emitted as (v0, v1, v2, v2). The duplicated vertex forms a zero-area second
         * triangle that rasterizers discard, so the rendered geometry is exact while
         * the vertex stream stays 4-per-primitive for the quad-based buffer. The
         * per-instance light overrides the recorded lightmap exactly like
         * {@link #appendCaptured}.</p>
         */
        public void appendCapturedAsQuads(VertexConsumer target, PoseStack.Pose pose, int light) {
                if (capturedVertices == null) {
                        return;
                }
                if (quadReplay == null) {
                        final QuadReplayRecorder recorder = new QuadReplayRecorder();
                        capturedVertices.accept(recorder);
                        recorder.finish();
                        quadReplay = recorder;
                }
                CAPTURED_CONSUMER.target = target;
                CAPTURED_CONSUMER.pose = pose;
                CAPTURED_CONSUMER.light = light;
                quadReplay.replayAsDegenerateQuads(CAPTURED_CONSUMER);
        }

        /** Lazily-recorded triangle stream of {@link #capturedVertices}, for {@link #appendCapturedAsQuads}. */
        @Nullable
        private QuadReplayRecorder quadReplay;

        /**
         * Close the GL vertex buffer owned by this model and forget it in the live registry.
         * Idempotent: the registry removal happens exactly once per instance, so a later close
         * (for example one racing the reload's {@link #releaseAll()}) is a no-op. The native
         * mesh, when present, is owned and freed by the native runtime instead.
         */
        public void close() {
                synchronized (LIVE_GL_MODELS) {
                        if (!LIVE_GL_MODELS.remove(this)) {
                                return;
                        }
                }
                if (vertexBuffer != null) {
                        vertexBuffer.close();
                }
        }

        /**
         * Close every live model's GL vertex buffer and clear the registry. Called from
         * {@code CustomResourceLoader#reload()} on the render thread right before the model
         * caches are cleared — the GL-path counterpart of the native pipeline's
         * {@code freeAllModelsForReload} leak safety. Models rebuilt later in the same reload
         * register fresh buffers.
         */
        public static void releaseAll() {
                synchronized (LIVE_GL_MODELS) {
                        final Iterator<NewOptimizedModel> iterator = LIVE_GL_MODELS.iterator();
                        while (iterator.hasNext()) {
                                final NewOptimizedModel model = iterator.next();
                                iterator.remove();
                                if (model.vertexBuffer != null) {
                                        model.vertexBuffer.close();
                                }
                        }
                }
        }

        /**
         * Issue the draw call for this mesh.
         *
         * @param matrix4f        the per-instance model matrix (the pose, without the camera view)
         * @param lightMultiplier brightness scale in {@code [0, 1]}; {@code 1} for full-bright stages
         * @param shaderProgram   the active shader (set by the render layer's state setup), may be {@code null} during reload
         */
//? if >= 1.21.4 {
        public void render(Matrix4f matrix4f, float lightMultiplier, @Nullable CompiledShaderProgram shaderProgram) {
//? } else {
        /*public void render(Matrix4f matrix4f, float lightMultiplier, @Nullable ShaderInstance shaderProgram) {
//
*///? }
                if (vertexBuffer != null && shaderProgram != null) {
                        // Full per-instance model-view: camera view (RenderSystem snapshot) * pose.
                        MODEL_VIEW_SCRATCH.set(RenderSystem.getModelViewMatrix()).mul(matrix4f);
                        final float[] colorModulator = colorModulatorFor(lightMultiplier);
                        // The light multiplier travels through the shader colour so every backend picks
                        // it up: pure GL reads COLOR_MODULATOR via setDefaultUniforms inside
                        // drawWithShader, and VulkanMod's translated pipelines plus Beryl's redirected
                        // pipelines read the VRenderSystem colour that RenderSystem.setShaderColor
                        // keeps in sync. The colour is restored afterwards so later vanilla batches
                        // are not tinted by this instance.
                        final float[] currentColor = RenderSystem.getShaderColor();
                        System.arraycopy(currentColor, 0, SAVED_COLOR_SCRATCH, 0, 4);
                        RenderSystem.setShaderColor(colorModulator[0], colorModulator[1], colorModulator[2], colorModulator[3]);
                        // drawWithShader — never the raw draw() — is the same contract every vanilla
                        // batch uses: it applies setDefaultUniforms (the per-instance MODEL_VIEW,
                        // COLOR_MODULATOR and fog uniforms) and, under VulkanMod, additionally binds
                        // the shader's CURRENT pipeline (VulkanMod-translated, or Beryl-redirected),
                        // syncs the per-draw MVP into VRenderSystem, binds the shader textures and
                        // uploads the UBOs, then restores the frame MVP. The previous raw draw()
                        // relied on whichever pipeline the previous unrelated draw left bound: under
                        // VulkanMod/Beryl that drew NEW_ENTITY data through stale terrain or entity
                        // pipelines — stretched rectangles, wrong-blend translucent stages
                        // (see-through ceilings and windows) and non-additive light stages
                        // (misplaced colour blocks).
                        try {
                                vertexBuffer.bind();
                                vertexBuffer.drawWithShader(MODEL_VIEW_SCRATCH, RenderSystem.getProjectionMatrix(), shaderProgram);
                        } finally {
                                VertexBuffer.unbind();
                                RenderSystem.setShaderColor(SAVED_COLOR_SCRATCH[0], SAVED_COLOR_SCRATCH[1], SAVED_COLOR_SCRATCH[2], SAVED_COLOR_SCRATCH[3]);
                        }
                }
        }

        public static VertexBuffer createVertexBuffer(VertexFormat.Mode drawMode, VertexFormat vertexFormat, Consumer<VertexConsumer> callback) {
//? if >= 1.21.4 {
                return VertexBuffer.uploadStatic(drawMode, vertexFormat, callback);
//? } else {
                /*final BufferBuilder builder = Tesselator.getInstance().begin(drawMode, vertexFormat);
                callback.accept(builder);
                final MeshData renderedBuffer = builder.build();
                final VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vertexBuffer.bind();
                vertexBuffer.upload(renderedBuffer);
                return vertexBuffer;
*///? }
        }

        private static float[] colorModulatorFor(float lightMultiplier) {
                // Map [0, 1] → 16-step quantised table when possible; otherwise allocate.
                if (lightMultiplier >= 1f) {
                        return COLOR_MODULATORS[16];
                }
                if (lightMultiplier <= 0f) {
                        return COLOR_MODULATORS[0];
                }
                final int index = Math.round(lightMultiplier * 15f);
                final float quantised = index / 15f;
                // Only return the cached array when the requested value rounds cleanly — otherwise
                // allocate to preserve exact appearance for callers passing arbitrary floats.
                //noinspection FloatingPointEquality
                if (quantised == lightMultiplier) {
                        return COLOR_MODULATORS[index];
                }
                return new float[]{lightMultiplier, lightMultiplier, lightMultiplier, 1f};
        }

        /** Render-thread scratch adapter; source geometry is retained without a redundant GL upload. */
        private static final class CapturedVertexConsumer implements VertexConsumer {
                private VertexConsumer target;
                private PoseStack.Pose pose;
                private int light;
                private final Vector3f normal = new Vector3f();

                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                        target.addVertex(pose.pose(), x, y, z);
                        return this;
                }

                @Override
                public VertexConsumer setColor(int red, int green, int blue, int alpha) {
                        target.setColor(red, green, blue, alpha);
                        return this;
                }

                @Override
                public VertexConsumer setUv(float u, float v) {
                        target.setUv(u, v);
                        return this;
                }

                @Override
                public VertexConsumer setUv1(int u, int v) {
                        target.setUv1(u, v);
                        return this;
                }

                @Override
                public VertexConsumer setUv2(int u, int v) {
                        target.setLight(light);
                        return this;
                }

                @Override
                public VertexConsumer setNormal(float x, float y, float z) {
                        pose.normal().transform(x, y, z, normal);
                        if (normal.lengthSquared() > 0) {
                                normal.normalize();
                        }
                        target.setNormal(normal.x, normal.y, normal.z);
                        return this;
                }
        }

        /**
         * Records one {@link #capturedVertices} emitter run as a flat per-vertex list,
         * then replays it as degenerate quads for the multi-instance batching
         * (see {@link #appendCapturedAsQuads}). Render-thread only; built once per
         * model and reused every frame.
         *
         * <p>The emitter drives the 1.21 vertex-consumer chain (addVertex first, then
         * the per-vertex setters), so the recorder buffers one vertex's attributes at
         * a time and commits it when the next {@code addVertex} arrives (or on
         * {@link #finish()}). The recorded lightmap is deliberately dropped — the
         * replay supplies the per-instance light instead, exactly like
         * {@link CapturedVertexConsumer#setUv2}.</p>
         */
        private static final class QuadReplayRecorder implements VertexConsumer {

                /** x, y, z, u, v, nx, ny, nz per recorded vertex. */
                private final ObjectArrayList<float[]> vertices = new ObjectArrayList<>();
                private final ObjectArrayList<Integer> colors = new ObjectArrayList<>();
                private final ObjectArrayList<Integer> overlays = new ObjectArrayList<>();

                private final float[] current = new float[8];
                private int currentColor = 0xFFFFFFFF;
                private int currentOverlay = OverlayTexture.NO_OVERLAY;
                private boolean vertexStarted;

                @Override
                public VertexConsumer addVertex(float x, float y, float z) {
                        commit();
                        current[0] = x;
                        current[1] = y;
                        current[2] = z;
                        vertexStarted = true;
                        return this;
                }

                @Override
                public VertexConsumer setColor(int red, int green, int blue, int alpha) {
                        // Pack into the standard 0xAARRGGBB form so the replay can use the
                        // single-argument setColor(int) default, which unpacks it symmetrically.
                        currentColor = (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF);
                        return this;
                }

                @Override
                public VertexConsumer setUv(float u, float v) {
                        current[3] = u;
                        current[4] = v;
                        return this;
                }

                @Override
                public VertexConsumer setUv1(int u, int v) {
                        currentOverlay = (u & 0xFFFF) | (v & 0xFFFF) << 16;
                        return this;
                }

                @Override
                public VertexConsumer setUv2(int u, int v) {
                        // The per-instance light replaces the baked lightmap at replay time.
                        return this;
                }

                @Override
                public VertexConsumer setNormal(float x, float y, float z) {
                        current[5] = x;
                        current[6] = y;
                        current[7] = z;
                        return this;
                }

                private void commit() {
                        if (vertexStarted) {
                                vertices.add(current.clone());
                                colors.add(currentColor);
                                overlays.add(currentOverlay);
                        }
                        vertexStarted = false;
                }

                /** Commit the trailing vertex after the emitter run completes. */
                void finish() {
                        commit();
                }

                /**
                 * Replay the recorded triangle stream (3 vertices per triangle) as degenerate
                 * quads (v0, v1, v2, v2) into the given downstream consumer.
                 */
                void replayAsDegenerateQuads(VertexConsumer out) {
                        final int count = vertices.size();
                        for (int i = 0; i + 2 < count; i += 3) {
                                emit(out, i);
                                emit(out, i + 1);
                                emit(out, i + 2);
                                // Duplicate the closing vertex: quad (v0, v1, v2, v2) keeps the
                                // triangle exact and adds one zero-area triangle the rasterizer drops.
                                emit(out, i + 2);
                        }
                        // A trailing partial group (fewer than 3 vertices, or a stream that is not a
                        // whole number of triangles) cannot be replayed quad-safely; skip it —
                        // it renders nothing, exactly like an unindexed partial primitive.
                }

                private void emit(VertexConsumer out, int index) {
                        final float[] vertex = vertices.get(index);
                        final int color = colors.get(index);
                        final int overlay = overlays.get(index);
                        out.addVertex(vertex[0], vertex[1], vertex[2]);
                        out.setColor(color);
                        out.setUv(vertex[3], vertex[4]);
                        out.setUv1(overlay & 0xFFFF, overlay >>> 16);
                        out.setUv2(0, 0);
                        out.setNormal(vertex[5], vertex[6], vertex[7]);
                }
        }

}
