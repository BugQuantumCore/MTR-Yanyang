package org.mtr.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArrivalsCacheTest {

	@Test
	void firstResultCannotMutateCachedSelection() {
		final ArrivalsCache cache = new ArrivalsCache(1000) {
			@Override
			public long getMillisOffset() {
				return 0;
			}

			@Override
			protected void requestArrivalsFromServer(LongAVLTreeSet platformIds, Consumer<ObjectList<ArrivalResponse>> callback) {
				callback.accept(new ObjectArrayList<>());
			}
		};
		final LongArrayList platforms = new LongArrayList(new long[]{1});
		cache.requestArrivals(platforms).add(null);
		assertTrue(cache.requestArrivals(platforms).isEmpty());
	}
}
