package org.mtr.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.WindowScreen;
import gg.essential.universal.UKeyboard;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UMinecraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.mtr.MTR;
import org.mtr.client.VulkanCompat;
import org.mtr.registry.UConverters;

public abstract class WindowBase extends WindowScreen {

	private boolean closeScreen = false;

	/**
	 * One-shot log for the pre-0.1.6 Radiance guard: the guard itself fires every frame while
	 * any MTR screen is open, so the explanation is logged once per session.
	 */
	private static boolean legacyRadianceNoticeLogged;

	/** The lines of the renderer-unsupported notice — static so the per-frame draw allocates nothing. */
	private static final Component[] LEGACY_RADIANCE_NOTICE_LINES = {
			Component.literal("MTR-Yanyang: this screen needs Radiance 0.1.6+"),
			Component.literal("MTR-Yanyang: 此界面需要 Radiance 0.1.6 或更高版本"),
			Component.literal("Older Radiance builds have no OpenGL context — please update Radiance"),
	};

	@Nullable
	private final WindowScreen previousScreen;
	@Nullable
	private final Screen previousScreenLegacy;

	public WindowBase(@Nullable WindowScreen previousScreen) {
		super(ElementaVersion.V11);
		this.previousScreen = previousScreen;
		previousScreenLegacy = null;
	}

	@Deprecated
	public WindowBase(@Nullable Screen previousScreenLegacy) {
		super(ElementaVersion.V10);
		previousScreen = null;
		this.previousScreenLegacy = previousScreenLegacy;
	}

	public WindowBase() {
		this(null);
	}

	@Override
	public void onDrawScreen(UMatrixStack matrixStack, int mouseX, int mouseY, float partialTicks) {
		// Radiance before 0.1.6 has no OpenGL context at all (GLFW_NO_API window, cancelled
		// makeContextCurrent/createCapabilities — see VulkanCompat#isUniversalCraftRenderingUnsupported),
		// and unlike VulkanMod it does not virtualize raw LWJGL GL calls either. Elementa draws
		// through UniversalCraft's URenderPass, whose glGet*/glDraw* family would crash the JVM
		// inside the driver — the same crash class the UniversalCraftManagedGlStateMixin guards
		// against under VulkanMod, but with no fabrication possible for the draw calls themselves.
		// Every MTR Elementa screen funnels through this override (WindowBase is the only
		// WindowScreen subclass in the mod), so replacing the Elementa draw with a vanilla-font
		// notice here is the complete fix: the notice goes through Minecraft's own font/buffer
		// path, which Radiance intercepts and renders safely.
		if (VulkanCompat.isUniversalCraftRenderingUnsupported()) {
			drawLegacyRadianceNotice(matrixStack);
			return;
		}
		super.onDrawScreen(matrixStack, mouseX, mouseY, partialTicks);
	}

	/**
	 * Draws the "renderer unsupported" notice using only vanilla rendering (Font#drawInBatch
	 * into the shared buffer source, flushed immediately — the same pattern
	 * SignPreviewComponent uses to draw vanilla sign models inside Elementa screens), so it
	 * stays safe under every renderer MTR supports.
	 */
	private void drawLegacyRadianceNotice(UMatrixStack matrixStack) {
		if (!legacyRadianceNoticeLogged) {
			legacyRadianceNoticeLogged = true;
			MTR.LOGGER.info("[mtr] Elementa rendering skipped: Radiance 0.1.6+ is required for MTR screens (older Radiance builds provide no OpenGL context); update Radiance to use this screen");
		}
		final Minecraft minecraft = Minecraft.getInstance();
		final Font font = minecraft.font;
		final PoseStack poseStack = UConverters.convert(matrixStack);
		final MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
		final int centerX = width / 2;
		int y = height / 2 - LEGACY_RADIANCE_NOTICE_LINES.length * (font.lineHeight + 2) / 2;
		for (final Component line : LEGACY_RADIANCE_NOTICE_LINES) {
			font.drawInBatch(
					line, centerX - font.width(line) / 2F, y, 0xFFFFFF, true,
					poseStack.last().pose(), bufferSource, Font.DisplayMode.NORMAL, 0xAA000000, 0xF000F0
			);
			y += font.lineHeight + 2;
		}
		bufferSource.endBatch();
	}

	@Override
	public void onKeyPressed(int keyCode, char typedChar, UKeyboard.@Nullable Modifiers modifiers) {
		if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
			super.onKeyPressed(keyCode, typedChar, modifiers);
		}
	}

	@Override
	public void onKeyReleased(int keyCode, char typedChar, UKeyboard.@Nullable Modifiers modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			closeScreen = true;
		} else {
			super.onKeyReleased(keyCode, typedChar, modifiers);
		}
	}

	@Override
	public void onTick() {
		super.onTick();
		if (closeScreen) {
			if (previousScreen == null) {
				Minecraft.getInstance().setScreen(previousScreenLegacy);
			} else {
				UMinecraft.setCurrentScreenObj(previousScreen);
			}
		}
	}

	@Override
	public final boolean isPauseScreen() {
		return false;
	}

	public final void markScreenForClose() {
		closeScreen = true;
	}
}
