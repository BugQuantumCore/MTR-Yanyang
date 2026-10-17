package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailSignalBuffersTest {
	@Test
	void comparesExactPrimitiveKeysAndIgnoresVehicleOwnerValues() {
		final Long2LongAVLTreeMap first = map(Long.MIN_VALUE, 11, -1, 22, 0, 33, Long.MAX_VALUE, 44);
		final Long2LongAVLTreeMap sameKeysDifferentValues = map(Long.MAX_VALUE, 900, 0, 800, -1, 700, Long.MIN_VALUE, 600);

		assertTrue(RailSignalBuffers.sameKeys(first, sameKeysDifferentValues));
		assertTrue(RailSignalBuffers.sameKeys(new Long2LongAVLTreeMap(), new Long2LongAVLTreeMap()));
		assertFalse(RailSignalBuffers.sameKeys(first, map(Long.MIN_VALUE, 11, -1, 22, 0, 33)));
		assertFalse(RailSignalBuffers.sameKeys(first, map(Long.MIN_VALUE, 11, -1, 22, 0, 33, Long.MIN_VALUE + 1, 44)));
	}

	@Test
	void recycleClearsOnlyPopulatedMapsAndReturnsTheSameReference() {
		final CountingMap populated = new CountingMap();
		populated.put(5, 55);

		assertSame(populated, RailSignalBuffers.recycle(populated));
		assertEquals(1, populated.clearCalls);
		assertTrue(populated.isEmpty());

		final CountingMap empty = new CountingMap();
		assertSame(empty, RailSignalBuffers.recycle(empty));
		assertEquals(0, empty.clearCalls);
	}

	private static Long2LongAVLTreeMap map(long... keyValuePairs) {
		final Long2LongAVLTreeMap map = new Long2LongAVLTreeMap();
		for (int i = 0; i < keyValuePairs.length; i += 2) {
			map.put(keyValuePairs[i], keyValuePairs[i + 1]);
		}
		return map;
	}

	private static final class CountingMap extends Long2LongAVLTreeMap {
		private int clearCalls;

		@Override
		public void clear() {
			clearCalls++;
			super.clear();
		}
	}
}
