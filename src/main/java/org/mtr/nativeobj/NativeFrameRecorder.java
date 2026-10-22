package org.mtr.nativeobj;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * The per-frame native draw list writer. During the model drain (which runs
 * exactly where the GL path issues its draws — inside the world render phase,
 * with VulkanMod's command buffer recording), native items are appended into
 * the frame slot and one submission per drain pass records them on the C++
 * side.
 *
 * <p>Matrix semantics mirror {@code NewOptimizedModel.render} exactly:
 * {@code MV = RenderSystem.getModelViewMatrix() * pose}, column-major via
 * {@link Matrix4f#get(float[])}; the normal matrix is the inverse transpose's
 * 3×3, written as three vec4-padded columns to match the SPIR-V layout.</p>
 *
 * <p>Hot-path discipline ({@code docs/PERFORMANCE.md} §3.2): the item store
 * and the scratch matrices are static grow-only arrays reused across frames —
 * the steady state allocates <b>nothing</b> per item, where the first revision
 * allocated six objects per queued draw. The drain runs only on the render
 * thread, which is what makes the shared scratch safe.</p>
 */
public final class NativeFrameRecorder {

        // Grow-only parallel item store; the initial slice covers typical
        // frames and grows to the frame budget at most once per session.
        private static final int INITIAL_ITEMS = 4096;
        private static int[] itemMeshIds = new int[INITIAL_ITEMS];
        private static int[] itemTextureIndex = new int[INITIAL_ITEMS];
        private static int[] itemStage = new int[INITIAL_ITEMS];
        private static int[] itemColor = new int[INITIAL_ITEMS];
        private static float[] itemModelView = new float[INITIAL_ITEMS * 16];
        private static float[] itemNormalMatrix = new float[INITIAL_ITEMS * 12];

        private static final Matrix4f MODEL_VIEW_SCRATCH = new Matrix4f();
        private static final Matrix3f NORMAL_SCRATCH = new Matrix3f();
        private static final float[] MATRIX_16_SCRATCH = new float[16];
        private static final float[] MATRIX_9_SCRATCH = new float[9];

        private static final Map<net.minecraft.resources.ResourceLocation, Integer> TEXTURE_INDEX_MAP = new HashMap<>();
        private static final Map<net.minecraft.resources.ResourceLocation, long[]> TEXTURE_HANDLES = new HashMap<>();

        private static int itemCount;
        private static boolean capacityWarned;

        private final NativeRuntime runtime;

        public NativeFrameRecorder(NativeRuntime runtime) {
                this.runtime = runtime;
        }

        /**
         * Queue one native model draw. {@code pose} is the transformed pose at the
         * point the GL path would call
         * {@code NewOptimizedModel.render(pose, lightMultiplier, shader)}.
         */
        public void queue(NativeModel model, Matrix4f pose, float lightMultiplier) {
                if (model.renderStage == null || itemCount >= WrxRegion.FRAME_MAX_ITEMS) {
                        if (!capacityWarned && itemCount >= WrxRegion.FRAME_MAX_ITEMS) {
                                capacityWarned = true;
                                org.mtr.MTR.LOGGER.warn("[wrvk] frame item budget ({} items) reached; extra native draws this frame were dropped", WrxRegion.FRAME_MAX_ITEMS);
                        }
                        return;
                }
                if (itemCount == itemMeshIds.length && itemCount < WrxRegion.FRAME_MAX_ITEMS) {
                        grow();
                }
                final Matrix4f modelView = MODEL_VIEW_SCRATCH.set(RenderSystem.getModelViewMatrix()).mul(pose);
                modelView.get(MATRIX_16_SCRATCH);
                final Matrix3f normal = NORMAL_SCRATCH.set(modelView).invert().transpose();
                normal.get(MATRIX_9_SCRATCH);

                final int textureIndex = textureIndexFor(model.texture);
                if (textureIndex < 0) {
                        return; // texture budget exhausted; already logged
                }

                final int base = itemCount;
                itemMeshIds[base] = model.meshId;
                itemTextureIndex[base] = textureIndex;
                itemStage[base] = model.renderStage.ordinal();
                itemColor[base] = model.colorFor(lightMultiplier);
                System.arraycopy(MATRIX_16_SCRATCH, 0, itemModelView, base * 16, 16);
                System.arraycopy(MATRIX_9_SCRATCH, 0, itemNormalMatrix, base * 12, 3);
                System.arraycopy(MATRIX_9_SCRATCH, 3, itemNormalMatrix, base * 12 + 4, 3);
                System.arraycopy(MATRIX_9_SCRATCH, 6, itemNormalMatrix, base * 12 + 8, 3);
                itemCount++;
        }

        private int textureIndexFor(net.minecraft.resources.ResourceLocation texture) {
                Integer index = TEXTURE_INDEX_MAP.get(texture);
                if (index != null) {
                        return index;
                }
                if (TEXTURE_INDEX_MAP.size() >= WrxRegion.FRAME_MAX_TEXTURES) {
                        if (!capacityWarned) {
                                capacityWarned = true;
                                org.mtr.MTR.LOGGER.warn("[wrvk] frame texture budget ({} textures) reached; extra native textures this frame were dropped", WrxRegion.FRAME_MAX_TEXTURES);
                        }
                        return -1;
                }
                index = TEXTURE_INDEX_MAP.size();
                TEXTURE_INDEX_MAP.put(texture, index);
                TEXTURE_HANDLES.put(texture, runtime.host().textureHandles(texture));
                return index;
        }

        private static void grow() {
                final int newCapacity = Math.min(itemMeshIds.length * 2, WrxRegion.FRAME_MAX_ITEMS);
                itemMeshIds = java.util.Arrays.copyOf(itemMeshIds, newCapacity);
                itemTextureIndex = java.util.Arrays.copyOf(itemTextureIndex, newCapacity);
                itemStage = java.util.Arrays.copyOf(itemStage, newCapacity);
                itemColor = java.util.Arrays.copyOf(itemColor, newCapacity);
                itemModelView = java.util.Arrays.copyOf(itemModelView, newCapacity * 16);
                itemNormalMatrix = java.util.Arrays.copyOf(itemNormalMatrix, newCapacity * 12);
        }

        private static void reset() {
                TEXTURE_INDEX_MAP.clear();
                TEXTURE_HANDLES.clear();
                itemCount = 0;
        }

        /** The payload written into the frame slot: header + textures + items. */
        void writeTo(ByteBuffer frame) {
                frame.order(ByteOrder.LITTLE_ENDIAN);
                // FrameHeader (after the 8-byte seq word handled by the region)
                frame.position(8);
                frame.putInt(WrxRegion.FRAME_MAGIC);
                frame.putInt(itemCount);
                frame.putInt(TEXTURE_HANDLES.size());
                frame.putInt(0); // reserved
                final float[] projection = new float[16];
                RenderSystem.getProjectionMatrix().get(projection);
                for (final float value : projection) {
                        frame.putFloat(value);
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
                frame.putInt(130); // depth format (informational)
                frame.putInt(0);    // reserved1
                frame.putLong(runtime.host().currentCommandBuffer());
                frame.putLong(runtime.host().boundRenderPass());
                frame.putLong(0);   // reserved2
                frame.putLong(0);   // reserved3
                // texture table (index order; FRAME_TEXTURE_BYTES = 40 each)
                for (final Map.Entry<net.minecraft.resources.ResourceLocation, Integer> entry : TEXTURE_INDEX_MAP.entrySet()) {
                        final long[] handles = TEXTURE_HANDLES.getOrDefault(entry.getKey(), new long[3]);
                        frame.putLong(handles[0]);
                        frame.putLong(handles[1]);
                        frame.putLong(handles[2]);
                        frame.putInt(43);  // R8G8B8A8_UNORM, VulkanMod's texture format
                        frame.putInt(1);    // mip levels (informational)
                        frame.putInt(5);    // SHADER_READ_ONLY_OPTIMAL
                        frame.putInt(0);    // reserved
                }
                // items (FRAME_ITEM_BYTES = 128 each)
                for (int i = 0; i < itemCount; i++) {
                        frame.putInt(itemMeshIds[i]);
                        frame.putInt(itemTextureIndex[i]);
                        frame.putInt(itemStage[i]);
                        frame.putInt(itemColor[i]);
                        final int mv = i * 16;
                        for (int j = 0; j < 16; j++) {
                                frame.putFloat(itemModelView[mv + j]);
                        }
                        final int nm = i * 12;
                        for (int j = 0; j < 12; j++) {
                                frame.putFloat(itemNormalMatrix[nm + j]);
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
         * Submit everything queued so far. Called once per drained bucket at the
         * end of {@code MainRenderer.renderModel(matrixStack, modelRenders, offset)}.
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
