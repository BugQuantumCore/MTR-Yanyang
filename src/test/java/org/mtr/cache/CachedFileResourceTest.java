package org.mtr.cache;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.bytes.ByteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CachedFileResourceTest {

	@Test
	void argbRoundTripPreservesEveryChannel() {
		for (final int color : new int[]{0, -1, 0x12345678, 0x8000FF7F, 0x00FFFFFF}) {
			final ByteArrayList bytes = new ByteArrayList();
			CachedFileResource.writeInt(bytes, color);
			assertEquals(color, CachedFileResource.getInt(bytes.toByteArray(), 0));
		}
	}
}
