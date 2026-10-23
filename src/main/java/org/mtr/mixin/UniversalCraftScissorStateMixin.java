package org.mtr.mixin;

import gg.essential.universal.render.ScissorState;
import org.mtr.client.VulkanCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes UniversalCraft's scissor-state query safe under VulkanMod.
 *
 * <p>{@code ScissorState.active()} queries the scissor box with raw
 * {@code GL11.glGetIntegerv(GL_SCISSOR_BOX, ...)} and the test-enable flag with
 * {@code GL11.glGetBoolean(GL_SCISSOR_TEST)} — neither variant is intercepted by VulkanMod, so
 * the call crashes without an OpenGL context. When VulkanMod is detected, this mixin cancels the
 * query and reports the scissor test as disabled. Restoring that fabricated state is harmless:
 * VulkanMod stubs the raw {@code glScissor}/{@code glEnable}/{@code glDisable} calls used by
 * {@code ScissorState.activate}, so no clipping occurs either way; when VulkanMod is absent the
 * original OpenGL query runs unchanged.</p>
 */
@Mixin(value = ScissorState.Companion.class, remap = false)
public abstract class UniversalCraftScissorStateMixin {

	@Inject(method = "active", at = @At("HEAD"), cancellable = true)
	private void mtr$vulkanmod$fabricateScissorState(CallbackInfoReturnable<ScissorState> cir) {
		if (!VulkanCompat.isVulkanModActive()) {
			return;
		}
		cir.setReturnValue(ScissorState.Companion.getDISABLED());
	}
}
