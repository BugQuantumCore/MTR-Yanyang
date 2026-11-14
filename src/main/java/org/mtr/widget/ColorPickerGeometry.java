package org.mtr.widget;

import java.awt.Color;

/**
 * Constant-size geometry for the HSB plane using vertex interpolation and source-alpha blending.
 * The target's color space and blend implementation still determine the rendered colors.
 */
final class ColorPickerGeometry {

	private static final int[] HUE_COLORS = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

	private ColorPickerGeometry() {
	}

	static void hue(float left, float top, float right, float bottom, QuadConsumer consumer) {
		if (right <= left || bottom <= top) {
			return;
		}
		for (int index = 0; index < 6; index++) {
			consumer.accept(left, top + (bottom - top) * index / 6, right, top + (bottom - top) * (index + 1) / 6,
				HUE_COLORS[index], HUE_COLORS[index + 1], HUE_COLORS[index + 1], HUE_COLORS[index]);
		}
	}

	/** Horizontal saturation uses brightness increasing upwards; otherwise brightness increases rightwards. */
	static void saturationBrightness(float hue, float left, float top, float right, float bottom, boolean horizontalSaturation, QuadConsumer consumer) {
		if (right <= left || bottom <= top) {
			return;
		}
		final int hueColor = Color.HSBtoRGB(hue, 1, 1);
		// HSB RGB channel = brightness * ((1 - saturation) + saturation * hueChannel).
		// Both layers are one-dimensional gradients, avoiding a diagonal interpolation seam.
		if (horizontalSaturation) {
			consumer.accept(left, top, right, bottom, 0xFFFFFFFF, 0xFFFFFFFF, hueColor, hueColor);
			consumer.accept(left, top, right, bottom, 0x00000000, 0xFF000000, 0xFF000000, 0x00000000);
		} else {
			consumer.accept(left, top, right, bottom, 0xFFFFFFFF, hueColor, hueColor, 0xFFFFFFFF);
			consumer.accept(left, top, right, bottom, 0xFF000000, 0xFF000000, 0x00000000, 0x00000000);
		}
	}

	@FunctionalInterface
	interface QuadConsumer {
		void accept(float left, float top, float right, float bottom, int topLeft, int bottomLeft, int bottomRight, int topRight);
	}
}
