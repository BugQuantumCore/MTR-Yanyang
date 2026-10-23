package org.mtr.mixin;

import gg.essential.universal.render.ManagedGlState;
import gg.essential.universal.shader.BlendState;
import org.lwjgl.opengl.GL11;
import org.mtr.client.VulkanCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Makes UniversalCraft's GL-state snapshot safe when VulkanMod is driving the renderer.
 *
 * <p>{@code ManagedGlState.active()} queries the OpenGL state machine with raw LWJGL calls
 * ({@code GL11.glGetBoolean}, {@code GL11.glGetFloat}, {@code GL11.glGetBooleanv}). VulkanMod
 * mixins most of {@code GL11}, but not the {@code glGet*} family — so with no GL context current
 * these calls crash the JVM inside the vendor driver (the exact crash observed in
 * {@code hs_err_pid48832.log}: {@code URenderPass.<init> -> ManagedGlState.active ->
 * GL11C.nglGetBooleanv -> EXCEPTION_ACCESS_VIOLATION}).</p>
 *
 * <p>When VulkanMod is detected, this mixin cancels the query and fabricates a snapshot that
 * mirrors the state Minecraft's GUI rendering normally runs with (depth test on / LEQUAL, no
 * culling, alpha blending, all masks enabled). Every state <em>application</em> performed by
 * {@code ManagedGlState.activate} goes through {@code RenderSystem}/{@code GlStateManager},
 * which VulkanMod fully implements, so restoring the fabricated snapshot is safe. When VulkanMod
 * is absent the original OpenGL query runs unchanged.</p>
 */
@Mixin(value = ManagedGlState.Companion.class, remap = false)
public abstract class UniversalCraftManagedGlStateMixin {

	@Inject(method = "active", at = @At("HEAD"), cancellable = true)
	private void mtr$vulkanmod$fabricateGlState(CallbackInfoReturnable<ManagedGlState> cir) {
		if (!VulkanCompat.isVulkanModActive()) {
			return;
		}
		cir.setReturnValue(new ManagedGlState(
				// depthTest / depthFunc: vanilla GUI keeps depth testing on with LEQUAL
				true, GL11.GL_LEQUAL,
				// culling: disabled while drawing GUI geometry
				false,
				// colorLogicOp / mode: never used by the GUI
				false, GL11.GL_COPY,
				// blendState: Minecraft's defaultBlendFunc (src-alpha / one-minus-src-alpha,
				// alpha: one / one-minus-src-alpha), enabled
				new BlendState(
						BlendState.Equation.ADD,
						BlendState.Param.SRC_ALPHA, BlendState.Param.ONE_MINUS_SRC_ALPHA,
						BlendState.Param.ONE, BlendState.Param.ONE_MINUS_SRC_ALPHA,
						true
				),
				// colorMask / depthMask: fully writable
				Arrays.asList(true, true, true, true), true,
				// polygonOffset: disabled
				false, 0F, 0F,
				// shadeModel / alphaTest / color: compiled out on 1.17+, values are irrelevant
				GL11.GL_SMOOTH, true, GL11.GL_ALWAYS, 0F,
				1F, 1F, 1F, 1F,
				// texture2DStates: unused on 1.17+
				new ArrayList<>()
		));
	}
}
