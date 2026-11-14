package org.mtr.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CapturedModelLightTest {

	@Test
	void zeroBrightnessRemainsDarkInEitherConvention() {
		assertEquals(0, CapturedModelLight.toPackedLight(0));
	}

	@Test
	void maximumRawBrightnessBecomesFullBrightPackedLight() {
		assertEquals(0xF000F0, CapturedModelLight.toPackedLight(15));
	}

	@Test
	void intermediateBrightnessPopulatesBothLightmapComponents() {
		assertEquals(0x100010, CapturedModelLight.toPackedLight(1));
		assertEquals(0x700070, CapturedModelLight.toPackedLight(7));
		assertEquals(0xE000E0, CapturedModelLight.toPackedLight(14));
	}

	@Test
	void alreadyPackedAndFullBrightValuesKeepTheirExactBits() {
		assertEquals(0xB00030, CapturedModelLight.toPackedLight(0xB00030));
		assertEquals(0x30, CapturedModelLight.toPackedLight(0x30));
		assertEquals(0xB00000, CapturedModelLight.toPackedLight(0xB00000));
		assertEquals(0xF000F0, CapturedModelLight.toPackedLight(0xF000F0));
	}

	@Test
	void repeatedConversionDoesNotScaleBrightnessAgain() {
		for (int brightness = 0; brightness <= 15; brightness++) {
			final int packed = CapturedModelLight.toPackedLight(brightness);
			assertEquals(packed, CapturedModelLight.toPackedLight(packed));
		}
	}
}
