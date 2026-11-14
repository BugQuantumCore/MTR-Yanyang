package org.mtr.widget;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ColorPickerGeometryTest {

	@Test
	void emitsConstantGeometryAndSkipsEmptyBounds() {
		final List<Quad> hue = new ArrayList<>();
		ColorPickerGeometry.hue(2, 3, 12, 63, collect(hue));
		assertEquals(6, hue.size());
		assertEquals(0xFFFF0000, hue.getFirst().topLeft);
		assertEquals(0xFFFF0000, hue.getLast().bottomLeft);
		assertEquals(3, hue.getFirst().top);
		assertEquals(63, hue.getLast().bottom);
		for (int index = 1; index < hue.size(); index++) {
			assertEquals(hue.get(index - 1).bottom, hue.get(index).top);
			assertEquals(hue.get(index - 1).bottomLeft, hue.get(index).topLeft);
		}

		final List<Quad> plane = new ArrayList<>();
		ColorPickerGeometry.saturationBrightness(0.5F, 2, 3, 102, 203, true, collect(plane));
		assertEquals(2, plane.size());
		ColorPickerGeometry.hue(2, 3, 2, 63, collect(plane));
		ColorPickerGeometry.hue(2, 3, 12, 2, collect(plane));
		ColorPickerGeometry.saturationBrightness(0, 2, 3, 1, 63, false, collect(plane));
		ColorPickerGeometry.saturationBrightness(0, 2, 3, 12, 3, true, collect(plane));
		assertEquals(2, plane.size());
	}

	@Test
	void retainsBothWidgetsAxisDirectionsAndOverlayAlpha() {
		final List<Quad> horizontal = new ArrayList<>();
		ColorPickerGeometry.saturationBrightness(0, 0, 0, 100, 100, true, collect(horizontal));
		assertEquals(0xFFFFFFFF, horizontal.getFirst().topLeft);
		assertEquals(0xFFFF0000, horizontal.getFirst().topRight);
		assertEquals(0x00000000, horizontal.getLast().topLeft);
		assertEquals(0xFF000000, horizontal.getLast().bottomLeft);

		final List<Quad> vertical = new ArrayList<>();
		ColorPickerGeometry.saturationBrightness(0, 0, 0, 100, 100, false, collect(vertical));
		assertEquals(0xFFFFFFFF, vertical.getFirst().topLeft);
		assertEquals(0xFFFF0000, vertical.getFirst().bottomLeft);
		assertEquals(0xFF000000, vertical.getLast().topLeft);
		assertEquals(0x00000000, vertical.getLast().topRight);
	}

	@Test
	void standardSourceAlphaChannelMathMatchesHsbWithinRounding() {
		// This checks the geometry's algebra, not a GPU target's gamma or blend state.
		for (final boolean horizontalSaturation : new boolean[]{false, true}) {
			for (final float hue : new float[]{0, 0.05F, 0.25F, 0.5F, 0.75F, 0.95F, 1}) {
				final List<Quad> quads = new ArrayList<>();
				ColorPickerGeometry.saturationBrightness(hue, 0, 0, 1, 1, horizontalSaturation, collect(quads));
				for (final float saturation : new float[]{0, 0.125F, 0.5F, 0.875F, 1}) {
					for (final float brightness : new float[]{0, 0.125F, 0.5F, 0.875F, 1}) {
						final float x = horizontalSaturation ? saturation : brightness;
						final float y = horizontalSaturation ? 1 - brightness : saturation;
						final int expected = Color.HSBtoRGB(hue, saturation, brightness);
						final double overlayAlpha = interpolate(quads.getLast(), x, y, 24) / 255;
						for (int shift = 0; shift <= 16; shift += 8) {
							final double actual = interpolate(quads.getFirst(), x, y, shift) * (1 - overlayAlpha);
							assertEquals(expected >>> shift & 0xFF, actual, 1);
						}
					}
				}
			}
		}
	}

	private static double interpolate(Quad quad, float x, float y, int shift) {
		final double top = (quad.topLeft >>> shift & 0xFF) * (1 - x) + (quad.topRight >>> shift & 0xFF) * x;
		final double bottom = (quad.bottomLeft >>> shift & 0xFF) * (1 - x) + (quad.bottomRight >>> shift & 0xFF) * x;
		return top * (1 - y) + bottom * y;
	}

	private static ColorPickerGeometry.QuadConsumer collect(List<Quad> quads) {
		return (left, top, right, bottom, topLeft, bottomLeft, bottomRight, topRight) -> quads.add(new Quad(left, top, right, bottom, topLeft, bottomLeft, bottomRight, topRight));
	}

	private record Quad(float left, float top, float right, float bottom, int topLeft, int bottomLeft, int bottomRight, int topRight) {
	}
}
