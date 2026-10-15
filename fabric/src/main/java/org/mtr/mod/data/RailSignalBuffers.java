package org.mtr.mod.data;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongIterator;

/** Primitive key comparison and map reuse for Rail's four signal reservation windows. */
public final class RailSignalBuffers {
	private RailSignalBuffers() {
	}

	/**
	 * Compares reservation IDs only. The map values are vehicle owners and do not affect the
	 * signal-change notification contract used by Rail.tick1.
	 */
	public static boolean sameKeys(Long2LongAVLTreeMap first, Long2LongAVLTreeMap second) {
		if (first == second) {
			return true;
		}
		final int size = first.size();
		if (size != second.size()) {
			return false;
		}
		if (size == 0) {
			return true;
		}

		final LongIterator firstKeys = first.keySet().iterator();
		final LongIterator secondKeys = second.keySet().iterator();
		while (firstKeys.hasNext()) {
			if (firstKeys.nextLong() != secondKeys.nextLong()) {
				return false;
			}
		}
		return true;
	}

	/** Clears a non-empty buffer and returns the same map for reuse. */
	public static Long2LongAVLTreeMap recycle(Long2LongAVLTreeMap map) {
		if (!map.isEmpty()) {
			map.clear();
		}
		return map;
	}
}
