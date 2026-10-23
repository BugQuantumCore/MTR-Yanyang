package org.mtr.mixin;

import gg.essential.universal.shader.BlendState;
import org.mtr.client.VulkanCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes UniversalCraft's blend-state query safe under VulkanMod.
 *
 * <p>{@code BlendState.active()} ends with a raw {@code GL11.glGetBoolean(GL_BLEND)} call, which
 * is not intercepted by VulkanMod and crashes without an OpenGL context. The surrounding
 * {@code glGetInteger} calls are intercepted (VulkanMod returns 0), but the boolean query alone
 * is enough to take down the JVM. When VulkanMod is detected, this mixin cancels the query and
 * returns a blend state matching Minecraft's {@code RenderSystem.defaultBlendFunc()}; when
 * VulkanMod is absent the original OpenGL query runs unchanged.</p>
 */
@Mixin(value = BlendState.Companion.class, remap = false)
public abstract class UniversalCraftBlendStateMixin {

	@Inject(method = "active", at = @At("HEAD"), cancellable = true)
	private void mtr$vulkanmod$fabricateBlendState(CallbackInfoReturnable<BlendState> cir) {
		if (!VulkanCompat.isVulkanModActive()) {
			return;
		}
		cir.setReturnValue(new BlendState(
				BlendState.Equation.ADD,
				BlendState.Param.SRC_ALPHA, BlendState.Param.ONE_MINUS_SRC_ALPHA,
				BlendState.Param.ONE, BlendState.Param.ONE_MINUS_SRC_ALPHA,
				true
		));
	}
}
