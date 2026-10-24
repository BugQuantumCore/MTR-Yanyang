package org.mtr.nativeobj;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The per-frame native draw list writer — protocol v2, a thin appender.
 * During the model drain (which runs exactly where the GL path issues its
 * draws — inside the world render phase, with VulkanMod's command buffer
 * recording), native items are appended into the frame slot and one
 * submission per drain pass records them on the C++ side.
 *
 * <p><b>v2 semantics ("zero-Java model rendering"):</b> the draw items carry
 * the RAW pose matrix straight out of the {@code PoseStack}, the RAW packed
 * lightmap int and the native texture-pool key — nothing is pre-multiplied,
 * inverted or colour-computed in Java. The frame header carries the camera
 * view and projection matrices (one 16-float copy each, per frame, not per
 * item); the C++ recorder derives {@code MV = view × pose}, the
 * inverse-transpose normal matrix and the {@code Kd × brightness} colour at
 * record time. Static textures live in the native pool
 * ({@code MSG_UPLOAD_TEXTURE}); only animated (mcmeta) textures travel per
 * frame, as a small external-handle override table resolved through
 * VulkanMod.</p>
 *
 * <p>Hot-path discipline ({@code docs/PERFORMANCE.md} §3.2): the item store
 * is a static grow-only array bank reused across frames — the steady state
 * allocates <b>nothing</b> per item, where the first revision allocated six
 * objects per queued draw. The drain runs only on the render thread, which
 * is what makes the shared scratch safe.</p>
 *
 * @see NativeTextureManager
 * @see WrxRegion (and native/include/wrx/wrx.hpp, docs/NATIVE_VULKAN.md, for the contract)
 */
public final class NativeFrameRecorder {

        // Grow-only parallel item store; the initial slice covers typical
        // frames and grows to the frame budget at most once per session.
        private static final int INITIAL_ITEMS = 4096;
        private static int[] itemMeshIds = new int[INITIAL_ITEMS];
        private static int[] itemTextureKeys = new int[INITIAL_ITEMS];
        private static int[] itemStage = new int[INITIAL_ITEMS];
        private static int[] itemLight = new int[INITIAL_ITEMS];
        private static float[] itemPose = new float[INITIAL_ITEMS * 16];

        /** Single scratch for the JOML {@code get(float[])} copies (column-major). */
        private static final float[] MATRIX_16_SCRATCH = new float[16];

        /**
         * Per-frame external (animated) textures, deduplicated, capped at
         * {@link WrxRegion#FRAME_MAX_EXTERNAL}. Cleared with the item store
         * on every submit.
         */
        private static final ObjectArrayList<ResourceLocation> frameExternals = new ObjectArrayList<>();

        private static int itemCount;
        private static boolean capacityWarned;
        private static boolean externalCapacityWarned;

        private final NativeRuntime runtime;

        public NativeFrameRecorder(NativeRuntime runtime) {
                this.runtime = runtime;
        }

        /**
         * Queue one native model draw. {@code pose} is the raw transformed
         * pose at the point the GL path would call
         * {@code NewOptimizedModel.render(pose, lightMultiplier, shader)},
         * and {@code light} is the RAW packed lightmap int — no model-view
         * multiply, no normal matrix, no brightness scaling; all of that is
         * native math under protocol v2. The only per-item work here is one
         * 16-float copy.
         */
        public void queue(NativeModel model, Matrix4f pose, int light) {
                if (model.renderStage == null) {
                        return;
                }
                if (itemCount >= WrxRegion.FRAME_MAX_ITEMS) {
                        if (!capacityWarned) {
                                capacityWarned = true;
                                org.mtr.MTR.LOGGER.warn("[wrvk] frame item budget ({} items) reached; extra native draws this frame were dropped", WrxRegion.FRAME_MAX_ITEMS);
                        }
                        return;
                }
                if (itemCount == itemMeshIds.length && itemCount < WrxRegion.FRAME_MAX_ITEMS) {
                        grow();
                }
                pose.get(MATRIX_16_SCRATCH);

                final int base = itemCount;
                itemMeshIds[base] = model.meshId;
                itemTextureKeys[base] = model.textureKey;
                itemStage[base] = model.renderStage.ordinal();
                itemLight[base] = light;
                System.arraycopy(MATRIX_16_SCRATCH, 0, itemPose, base * 16, 16);
                itemCount++;

                // animated (mcmeta) textures stay external: VulkanMod owns
                // their images, so they travel as per-frame handle overrides
                final ResourceLocation texture = model.texture;
                if (texture != null && NativeTextureManager.INSTANCE.isExternal(texture) && !frameExternals.contains(texture)) {
                        if (frameExternals.size() >= WrxRegion.FRAME_MAX_EXTERNAL) {
                                if (!externalCapacityWarned) {
                                        externalCapacityWarned = true;
                                        org.mtr.MTR.LOGGER.warn("[wrvk] frame external texture budget ({} textures) reached; extra animated textures this frame were dropped", WrxRegion.FRAME_MAX_EXTERNAL);
                                }
                        } else {
                                frameExternals.add(texture);
                        }
                }
        }

        private static void grow() {
                final int newCapacity = Math.min(itemMeshIds.length * 2, WrxRegion.FRAME_MAX_ITEMS);
                itemMeshIds = java.util.Arrays.copyOf(itemMeshIds, newCapacity);
                itemTextureKeys = java.util.Arrays.copyOf(itemTextureKeys, newCapacity);
                itemStage = java.util.Arrays.copyOf(itemStage, newCapacity);
                itemLight = java.util.Arrays.copyOf(itemLight, newCapacity);
                itemPose = java.util.Arrays.copyOf(itemPose, newCapacity * 16);
        }

        /** Clear the per-frame stores; texture-key bookkeeping is owned by {@link NativeTextureManager}. */
        private static void reset() {
                itemCount = 0;
                frameExternals.clear();
        }

        /** The payload written into the frame slot: header + externals + items. */
        void writeTo(ByteBuffer frame) {
                frame.order(ByteOrder.LITTLE_ENDIAN);
                // FrameHeader (after the 8-byte seq word handled by the region)
                frame.position(8);
                frame.putInt(WrxRegion.FRAME_MAGIC);
                frame.putInt(itemCount);
                frame.putInt(frameExternals.size());
                frame.putInt(0); // reserved0
                // view + projection: the ONLY per-frame matrix copies — the
                // native side multiplies them into every item's raw pose at
                // record time
                RenderSystem.getModelViewMatrix().get(MATRIX_16_SCRATCH);
                for (int i = 0; i < 16; i++) {
                        frame.putFloat(MATRIX_16_SCRATCH[i]);
                }
                RenderSystem.getProjectionMatrix().get(MATRIX_16_SCRATCH);
                for (int i = 0; i < 16; i++) {
                        frame.putFloat(MATRIX_16_SCRATCH[i]);
                }
                final float[] fogColor = new float[4];
                final float fogStart;
                final float fogEnd;
//? if >= 1.21.4 {
                final var fog = RenderSystem.getShaderFog();
                fogColor[0] = fog.red();
                fogColor[1] = fog.green();
                fogColor[2] = fog.blue();
                fogColor[3] = fog.alpha();
                fogStart = fog.start();
                fogEnd = fog.end();
//? } else {
                /*final float[] fog = RenderSystem.getShaderFogColor();
                fogColor[0] = fog[0];
                fogColor[1] = fog[1];
                fogColor[2] = fog[2];
                fogColor[3] = fog[3];
                fogStart = RenderSystem.getShaderFogStart();
                fogEnd = RenderSystem.getShaderFogEnd();
*///? }
                for (int i = 0; i < 4; i++) {
                        frame.putFloat(fogColor[i]);
                }
                frame.putFloat(fogStart);
                frame.putFloat(fogEnd);
                for (final float value : runtime.host().lightDirection0()) {
                        frame.putFloat(value);
                }
                for (final float value : runtime.host().lightDirection1()) {
                        frame.putFloat(value);
                }
                frame.putInt(Minecraft.getInstance().getWindow().getWidth());
                frame.putInt(Minecraft.getInstance().getWindow().getHeight());
                frame.putInt(0); // reserved1a (v1 carried the informational depth format here)
                frame.putInt(0); // reserved1b
                frame.putLong(runtime.host().currentCommandBuffer());
                frame.putLong(runtime.host().boundRenderPass());
                frame.putLong(0); // reserved2
                frame.putLong(0); // reserved3
                // externals (FrameExternal, 32 bytes each): animated textures
                // resolved through VulkanMod for this frame only; items
                // reference them by key
                for (int i = 0; i < frameExternals.size(); i++) {
                        final ResourceLocation texture = frameExternals.get(i);
                        final long[] handles = runtime.host().textureHandles(texture);
                        frame.putInt(NativeTextureManager.INSTANCE.keyOfExisting(texture));
                        frame.putInt(0); // reserved
                        frame.putLong(handles[0]);
                        frame.putLong(handles[1]);
                        frame.putLong(handles[2]);
                }
                // items (FrameItem, 80 bytes each): mesh + pool key + stage +
                // raw light + the 16-float raw pose
                for (int i = 0; i < itemCount; i++) {
                        frame.putInt(itemMeshIds[i]);
                        frame.putInt(itemTextureKeys[i]);
                        frame.putInt(itemStage[i]);
                        frame.putInt(itemLight[i]);
                        final int pose = i * 16;
                        for (int j = 0; j < 16; j++) {
                                frame.putFloat(itemPose[pose + j]);
                        }
                }
        }

        /** Data holder for the submission entry point. */
        record FrameData(NativeFrameRecorder recorder) {
                void writeTo(ByteBuffer frame) {
                        recorder.writeTo(frame);
                }
        }

        /**
         * Submit everything queued so far. Called once per drained bucket at
         * the end of {@code MainRenderer.renderModel(matrixStack, modelRenders, offset)}.
         */
        public void submitAndClear() {
                if (itemCount > 0) {
                        runtime.submitFrame(new FrameData(this));
                }
                reset();
        }

        /** Bounded micro-wait used by the shared-memory transport's ack polls. */
        static void spin(int micros) {
                final long deadline = System.nanoTime() + micros * 1000L;
                while (System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                }
        }
}
