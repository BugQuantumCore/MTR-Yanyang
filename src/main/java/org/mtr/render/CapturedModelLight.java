package org.mtr.render;

/** Adapts the legacy model brightness parameter to the captured vertex lightmap. */
final class CapturedModelLight {

	private CapturedModelLight() {
	}

	/**
	 * Vehicles, custom rails and ordinary eye candy supply a raw 0-15 brightness.
	 * The legacy VBO path uses it as a colour multiplier, whereas captured vertices
	 * require the standard packed block/sky light coordinates. Preserve the scalar
	 * brightness by using it for both components; their separate source values are
	 * not available at this boundary.
	 *
	 * <p>Standard nonzero packed light values are at least 16, because their light
	 * nibbles start at bits 4 and 20. Therefore only 1-15 need conversion, zero is
	 * dark in either convention, and already packed/full-bright inputs stay intact.</p>
	 */
	static int toPackedLight(int brightnessOrPackedLight) {
		return brightnessOrPackedLight >= 0 && brightnessOrPackedLight <= 15
			? brightnessOrPackedLight << 4 | brightnessOrPackedLight << 20
			: brightnessOrPackedLight;
	}
}
