package org.mtr.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.mtr.client.VulkanCompat;

import java.lang.reflect.Method;

/**
 * Bridges MTR's per-instance model-view matrix into VulkanMod's global
 * {@code VRenderSystem} state for the duration of one draw call.
 *
 * <p><b>Why this exists.</b> On the OpenGL path (vanilla client) MTR's optimised
 * models pass their per-instance transform through the active
 * {@code ShaderInstance.MODEL_VIEW_MATRIX} uniform — that is the contract the
 * vanilla {@code VertexBuffer.drawWithGlobalProgram} flow honours. VulkanMod's
 * translated legacy pipelines honour it too, because their uniform suppliers
 * wrap the live vanilla uniform buffers.</p>
 *
 * <p>Shader pipelines that <em>redirect</em> those vanilla shader instances to
 * their own Vulkan pipelines (Beryl does exactly this — every entity-family
 * {@code ShaderInstance} is swapped to Beryl's G-buffer pipeline while the
 * level renders) read {@code ModelViewMat} / {@code MVP} from
 * {@code net.vulkanmod.vulkan.VRenderSystem} instead. MTR's per-instance write
 * is then invisible: the model draws with the frame-global camera view only,
 * which pins it to the camera (it "follows the player" and never responds to
 * camera rotation). The fix mirrors what VulkanMod's own
 * {@code VBO.drawWithShader} does for every vanilla draw: point the
 * {@code VRenderSystem} globals at the per-draw model-view + projection,
 * draw, then restore the frame globals from the {@code RenderSystem}
 * snapshot.</p>
 *
 * <p>Everything here is reflection against {@code net.vulkanmod} and resolves
 * once. When VulkanMod is absent — or a build without these hooks is present —
 * the class turns into a no-op and the vanilla GL behaviour is completely
 * unchanged.</p>
 */
public final class VulkanFrameState {

        /**
         * Resolved once on first use. {@code null} entries mean "hook unavailable
         * in this VulkanMod build" and every method below turns into a no-op —
         * the vanilla-uniform channel keeps working in that case, so at worst we
         * are back to the pre-fix behaviour.
         */
        private static final Method APPLY_MODEL_VIEW_MATRIX;
        private static final Method APPLY_PROJECTION_MATRIX;
        private static final Method APPLY_MVP;
        private static final Method SET_SHADER_COLOR;
        private static final Method GET_SHADER_COLOR;
        private static final Method MAPPED_BUFFER_GET_FLOAT;
        private static final boolean ACTIVE;
        /**
         * The VRenderSystem colour captured by the latest {@link #beginInstance};
         * render-thread only (all draws happen on the render thread).
         */
        private static final float[] SAVED_COLOR = new float[4];

        static {
                Method applyModelViewMatrix = null;
                Method applyProjectionMatrix = null;
                Method applyMvp = null;
                Method setShaderColor = null;
                Method getShaderColor = null;
                Method mappedBufferGetFloat = null;
                boolean active = false;
                if (VulkanCompat.isVulkanModActive()) {
                        try {
                                final Class<?> vRenderSystem = Class.forName("net.vulkanmod.vulkan.VRenderSystem");
                                applyModelViewMatrix = vRenderSystem.getMethod("applyModelViewMatrix", Matrix4f.class);
                                applyProjectionMatrix = vRenderSystem.getMethod("applyProjectionMatrix", Matrix4f.class);
                                applyMvp = vRenderSystem.getMethod("applyMVP", Matrix4f.class, Matrix4f.class);
                                setShaderColor = vRenderSystem.getMethod("setShaderColor", float.class, float.class, float.class, float.class);
                                getShaderColor = vRenderSystem.getMethod("getShaderColor");
                                mappedBufferGetFloat = getShaderColor.getReturnType().getMethod("getFloat", int.class);
                                active = true;
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // A VulkanMod build without these hooks (or a fork) — the GL path
                                // keeps working through the vanilla uniform channel.
                                applyModelViewMatrix = null;
                                applyProjectionMatrix = null;
                                applyMvp = null;
                                setShaderColor = null;
                                getShaderColor = null;
                                mappedBufferGetFloat = null;
                                active = false;
                        }
                }
                APPLY_MODEL_VIEW_MATRIX = applyModelViewMatrix;
                APPLY_PROJECTION_MATRIX = applyProjectionMatrix;
                APPLY_MVP = applyMvp;
                SET_SHADER_COLOR = setShaderColor;
                GET_SHADER_COLOR = getShaderColor;
                MAPPED_BUFFER_GET_FLOAT = mappedBufferGetFloat;
                ACTIVE = active;
        }

        /**
         * @return {@code true} when the {@code VRenderSystem} bridge resolved and
         *         per-draw syncs should wrap optimised-model draws
         */
        public static boolean isActive() {
                return ACTIVE;
        }

        /**
         * Point VulkanMod's global matrix state at one instance's full
         * model-view ({@code RenderSystem.getModelViewMatrix() * pose}, the same
         * value written into the vanilla uniform) and the frame projection, and
         * push the light-multiplier colour. Must be called immediately before
         * the {@code VertexBuffer} draw, paired with {@link #endInstance()}.
         *
         * @param modelView the per-instance model-view, camera view included
         * @param red       red component of the colour modulator for this instance
         * @param green     green component of the colour modulator for this instance
         * @param blue      blue component of the colour modulator for this instance
         * @param alpha     alpha component of the colour modulator for this instance
         */
        public static void beginInstance(Matrix4f modelView, float red, float green, float blue, float alpha) {
                try {
                        // capture the live VRenderSystem colour — not the vanilla
                        // RenderSystem snapshot, which pipeline mods can bypass with
                        // direct VRenderSystem.setShaderColor calls — so the restore in
                        // endInstance is exact
                        final Object mappedBuffer = GET_SHADER_COLOR.invoke(null);
                        for (int i = 0; i < 4; i++) {
                                SAVED_COLOR[i] = (Float) MAPPED_BUFFER_GET_FLOAT.invoke(mappedBuffer, i * 4);
                        }
                        final Matrix4f projection = RenderSystem.getProjectionMatrix();
                        APPLY_MODEL_VIEW_MATRIX.invoke(null, modelView);
                        APPLY_PROJECTION_MATRIX.invoke(null, projection);
                        APPLY_MVP.invoke(null, modelView, projection);
                        SET_SHADER_COLOR.invoke(null, red, green, blue, alpha);
                } catch (Throwable ignored) {
                        // never let the bridge break rendering — worst case the draw
                        // uses the frame-global matrices like before the fix
                }
        }

        /**
         * Restore the frame-global matrices and colour after an instance draw,
         * exactly the way VulkanMod's {@code VBO.drawWithShader} epilogue restores
         * matrices — from the {@code RenderSystem} snapshot (which every VulkanMod
         * draw keeps in sync) — and the colour from the value captured in
         * {@link #beginInstance}. Subsequent vanilla and shader-pipeline draws are
         * unaffected.
         */
        public static void endInstance() {
                try {
                        final Matrix4f modelView = RenderSystem.getModelViewMatrix();
                        final Matrix4f projection = RenderSystem.getProjectionMatrix();
                        APPLY_MODEL_VIEW_MATRIX.invoke(null, modelView);
                        APPLY_PROJECTION_MATRIX.invoke(null, projection);
                        APPLY_MVP.invoke(null, modelView, projection);
                        SET_SHADER_COLOR.invoke(null, SAVED_COLOR[0], SAVED_COLOR[1], SAVED_COLOR[2], SAVED_COLOR[3]);
                } catch (Throwable ignored) {
                }
        }

        private VulkanFrameState() {
        }
}
