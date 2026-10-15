package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleOccupancyMapTest {
	private static final Position RAIL_START = new Position(0, 64, 0);
	private static final Position RAIL_END = new Position(100, 64, 0);

	@Test
	void rangeQueriesMatchVehiclePositionAcrossDirectionAndOwnerFiltering() {
		final VehicleOccupancyMap compact = new VehicleOccupancyMap();
		final VehiclePosition oldCoreOracle = new VehiclePosition();
		add(compact, RAIL_START, RAIL_END, 15, 35, 10);
		add(compact, RAIL_START, RAIL_END, 30, 34, 20);
		add(compact, RAIL_START, RAIL_END, 22, 30, 30);
		add(compact, RAIL_START, RAIL_END, 45, 55, 40);
		oldCoreOracle.addSegment(15, 35, 10);
		oldCoreOracle.addSegment(30, 34, 20);
		oldCoreOracle.addSegment(22, 30, 30);
		oldCoreOracle.addSegment(45, 55, 40);

		final VehiclePosition indexed = VehicleOccupancyMap.get(compact, new Position(0, 64, 0), new Position(100, 64, 0));
		assertNotNull(indexed);
		assertEquals(oldCoreOracle.getClosestOverlap(10, 40, false, 10), indexed.getClosestOverlap(10, 40, false, 10));
		assertEquals(oldCoreOracle.getClosestOverlap(10, 40, true, 10), indexed.getClosestOverlap(10, 40, true, 10));
		assertEquals(12, indexed.getClosestOverlap(10, 40, false, 10));
		assertEquals(6, indexed.getClosestOverlap(10, 40, true, 10));
		assertEquals(oldCoreOracle.getClosestOverlap(10, 40, false, 20), indexed.getClosestOverlap(10, 40, false, 20));
		assertEquals(5, indexed.getClosestOverlap(10, 40, false, 20));

		final VehiclePosition onlySelfOracle = new VehiclePosition();
		onlySelfOracle.addSegment(12, 34, 20);
		final VehicleOccupancyMap onlySelf = new VehicleOccupancyMap();
		add(onlySelf, RAIL_START, RAIL_END, 12, 34, 20);
		assertEquals(onlySelfOracle.getClosestOverlap(0, 50, false, 20), closest(onlySelf, 0, 50, false, 20));
		assertEquals(-1, closest(onlySelf, 0, 50, false, 20));
	}

	@Test
	void longVehicleIdsAndFloatingPointEdgesMatchPinnedCore() {
		final VehicleOccupancyMap compact = new VehicleOccupancyMap();
		final VehiclePosition oldCoreOracle = new VehiclePosition();
		final long[] vehicleIds = {Long.MIN_VALUE, 9_007_199_254_740_993L, Long.MAX_VALUE, 0};
		final double[][] intervals = {
				{-20, -5}, {-2, 0}, {0, 4}, {3, 10}
		};
		for (int index = 0; index < vehicleIds.length; index++) {
			add(compact, RAIL_START, RAIL_END, intervals[index][0], intervals[index][1], vehicleIds[index]);
			oldCoreOracle.addSegment(intervals[index][0], intervals[index][1], vehicleIds[index]);
		}

		final VehiclePosition indexed = VehicleOccupancyMap.get(compact, RAIL_START, RAIL_END);
		final double[][] queries = {
				{-30, 20}, {-5, -1}, {10, 0}, {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY},
				{Double.NaN, 1}, {1, Double.NaN}, {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY}
		};
		for (long vehicleId : vehicleIds) {
			for (double[] query : queries) {
				for (boolean reverse : new boolean[]{false, true}) {
					final double expected = oldCoreOracle.getClosestOverlap(query[0], query[1], reverse, vehicleId);
					final double actual = indexed.getClosestOverlap(query[0], query[1], reverse, vehicleId);
					assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
							"query " + query[0] + ".." + query[1] + ", reverse=" + reverse + ", id=" + vehicleId);
				}
			}
		}
	}

	@Test
	void deterministicRandomRangesMatchPinnedCoreAcrossMultipleQueries() {
		final VehicleOccupancyMap compact = new VehicleOccupancyMap();
		final VehiclePosition oldCoreOracle = new VehiclePosition();
		final Random random = new Random(0x4D54524CL);
		for (int index = 0; index < 48; index++) {
			final double first = random.nextInt(240) - 120;
			final double second = first + random.nextInt(30);
			final long vehicleId = index % 4 == 0 ? 9_007_199_254_740_993L + index : index % 3;
			add(compact, RAIL_START, RAIL_END, first, second, vehicleId);
			oldCoreOracle.addSegment(first, second, vehicleId);
		}

		final VehiclePosition indexed = VehicleOccupancyMap.get(compact, RAIL_START, RAIL_END);
		for (int queryIndex = 0; queryIndex < 64; queryIndex++) {
			final double start = random.nextInt(260) - 130;
			final double end = start + random.nextInt(60);
			final long vehicleId = queryIndex % 4 == 0 ? 9_007_199_254_740_993L + queryIndex : queryIndex % 3;
			for (boolean reverse : new boolean[]{false, true}) {
				final double expected = oldCoreOracle.getClosestOverlap(start, end, reverse, vehicleId);
				final double actual = indexed.getClosestOverlap(start, end, reverse, vehicleId);
				assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
			}
		}
	}

	@Test
	void railRegistryUsesFullPositionCoordinatesBeyondLegacyHashPeriod() {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		final int railCount = 512;
		for (int index = 0; index < railCount; index++) {
			final Position first = new Position((long) index * 4096, (long) (index % 7) * 256, 0);
			final Position second = new Position((long) (index + 1) * 4096, (long) ((index + 1) % 7) * 256, 0);
			assertEquals(0, first.hashCode(), "fixture should exercise Position's truncated hash");
			assertEquals(0, second.hashCode(), "fixture should exercise Position's truncated hash");
			add(map, first, second, index * 2, index * 2 + 1, index);
		}

		assertFalse(map.isCompatibilityMode());
		assertEquals(railCount, map.compactIntervalCount());
		for (int index = 0; index < railCount; index++) {
			final Position first = new Position((long) index * 4096, (long) (index % 7) * 256, 0);
			final Position second = new Position((long) (index + 1) * 4096, (long) ((index + 1) % 7) * 256, 0);
			assertEquals(0, closest(map, first, second, index * 2, index * 2 + 1, false, -1));
		}
	}

	@Test
	void eachRollingTimeLayerOwnsItsIntervalsAndResetClearsOnlyTheRetiredLayer() {
		final VehicleOccupancyMap previousLayer = new VehicleOccupancyMap();
		final VehicleOccupancyMap currentLayer = new VehicleOccupancyMap();
		add(previousLayer, RAIL_START, RAIL_END, 0, 20, 1);
		add(currentLayer, RAIL_START, RAIL_END, 20, 40, 2);

		assertEquals(0, closest(previousLayer, 0, 50, false, 3));
		assertEquals(20, closest(currentLayer, 0, 50, false, 3));
		assertSame(previousLayer, previousLayer.resetForNextTick());
		assertNull(VehicleOccupancyMap.get(previousLayer, RAIL_START, RAIL_END));
		assertEquals(20, closest(currentLayer, 0, 50, false, 3));
	}

	@Test
	void topologyInvalidationPreservesLiveOccupancyUntilTheBufferIsRetired() {
		final VehicleOccupancyMap buffer = new VehicleOccupancyMap();
		add(buffer, RAIL_START, RAIL_END, 10, 25, 4);

		buffer.invalidateTopology();
		assertEquals(10, closest(buffer, 0, 50, false, 9));

		buffer.resetForNextTick();
		assertNull(VehicleOccupancyMap.get(buffer, RAIL_START, RAIL_END));
		add(buffer, new Position(100, 64, 0), new Position(200, 64, 0), 30, 45, 5);
		assertEquals(30, closest(buffer, new Position(100, 64, 0), new Position(200, 64, 0), 0, 50, false, 9));
	}

	@Test
	void firstLegacyMapReadMaterializesCompleteLiveViewsAndKeepsThemUpdated() {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		add(map, RAIL_START, RAIL_END, 10, 20, 7);

		final Object2ObjectAVLTreeMap<Position, VehiclePosition> firstEndpoint = map.get(RAIL_START);
		assertNotNull(firstEndpoint);
		final VehiclePosition legacyValue = firstEndpoint.get(RAIL_END);
		assertNotNull(legacyValue);
		assertEquals(1, map.size());
		assertFalse(map.isEmpty());
		assertTrue(map.containsKey(RAIL_START));
		assertTrue(map.containsValue(firstEndpoint));
		assertEquals(1, map.keySet().size());
		assertEquals(1, map.values().size());
		assertEquals(1, map.entrySet().size());
		assertEquals(1, map.object2ObjectEntrySet().size());
		assertSame(legacyValue, VehicleOccupancyMap.get(map, RAIL_START, RAIL_END));

		add(map, new Position(0, 64, 0), new Position(100, 64, 0), 30, 40, 8);
		assertSame(legacyValue, map.get(new Position(0, 64, 0)).get(new Position(100, 64, 0)));
		assertEquals(10, legacyValue.getClosestOverlap(0, 50, false, 9));
		assertEquals(1, map.size());

		map.resetForNextTick();
		assertTrue(map.isEmpty());
		assertNull(VehicleOccupancyMap.get(map, RAIL_START, RAIL_END));
	}

	@Test
	void exposedOuterViewsStayLiveAfterResetAndCompactCloneIsIndependent() {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		add(map, RAIL_START, RAIL_END, 10, 20, 1);
		final var keys = map.keySet();
		final var entries = map.object2ObjectEntrySet();
		assertEquals(1, keys.size());

		map.resetForNextTick();
		assertTrue(keys.isEmpty());
		assertTrue(entries.isEmpty());
		add(map, RAIL_START, RAIL_END, 30, 40, 2);
		assertEquals(1, keys.size());
		assertEquals(1, entries.size());

		final VehicleOccupancyMap clone = (VehicleOccupancyMap) map.clone();
		clone.resetForNextTick();
		assertEquals(30, closest(map, 0, 50, false, 9));
		assertNull(VehicleOccupancyMap.get(clone, RAIL_START, RAIL_END));
	}

	@Test
	void emptyCompactMapSerializesAsTheLegacyMapType() throws Exception {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(map);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			final Object restored = input.readObject();
			assertTrue(restored instanceof Object2ObjectAVLTreeMap);
			assertFalse(restored instanceof VehicleOccupancyMap);
			assertTrue(((Object2ObjectAVLTreeMap<?, ?>) restored).isEmpty());
		}
	}

	@Test
	void nonNullDefaultReturnValueUsesTheLegacyNestedPutBehavior() {
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> defaultInnerMap = new Object2ObjectAVLTreeMap<>();
		final VehicleOccupancyMap compact = new VehicleOccupancyMap();
		compact.defaultReturnValue(defaultInnerMap);
		add(compact, RAIL_START, RAIL_END, 10, 20, 7);

		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> legacy = new Object2ObjectAVLTreeMap<>();
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> legacyDefault = new Object2ObjectAVLTreeMap<>();
		legacy.defaultReturnValue(legacyDefault);
		Data.put(legacy, RAIL_START, RAIL_END, current -> {
			final VehiclePosition value = current == null ? new VehiclePosition() : current;
			value.addSegment(10, 20, 7);
			return value;
		}, Object2ObjectAVLTreeMap::new);

		assertNotNull(defaultInnerMap.get(RAIL_END));
		// Old Data.put writes into the default inner map without adding an outer key.
		assertFalse(compact.containsKey(RAIL_START));
		assertFalse(legacy.containsKey(RAIL_START));
		assertEquals(legacyDefault.get(RAIL_END).getClosestOverlap(0, 50, false, 9),
				defaultInnerMap.get(RAIL_END).getClosestOverlap(0, 50, false, 9));
	}

	@Test
	void settingDefaultReturnValueAfterCompactWritesMaterializesBeforeChangingLookupSemantics() {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		add(map, RAIL_START, RAIL_END, 10, 20, 7);
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> defaultInnerMap = new Object2ObjectAVLTreeMap<>();

		map.defaultReturnValue(defaultInnerMap);

		assertNotSame(defaultInnerMap, map.get(RAIL_START));
		assertEquals(10, closest(map, 0, 50, false, 9));
		assertSame(defaultInnerMap, map.get(new Position(300, 64, 0)));
	}

	@Test
	void clonePreservesDefaultReturnValueAndMaterializedOccupancy() {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		add(map, RAIL_START, RAIL_END, 10, 20, 7);
		final Object2ObjectAVLTreeMap<Position, VehiclePosition> defaultInnerMap = new Object2ObjectAVLTreeMap<>();
		map.defaultReturnValue(defaultInnerMap);

		final VehicleOccupancyMap clone = (VehicleOccupancyMap) map.clone();

		assertSame(defaultInnerMap, clone.defaultReturnValue());
		assertNotSame(defaultInnerMap, clone.get(RAIL_START));
		assertEquals(10, closest(clone, 0, 50, false, 9));
	}

	@Test
	void serializationPreservesOuterDefaultReturnValue() throws Exception {
		final VehicleOccupancyMap map = new VehicleOccupancyMap();
		map.defaultReturnValue(new Object2ObjectAVLTreeMap<>());
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(map);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			@SuppressWarnings("unchecked")
			final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> restored =
					(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>) input.readObject();
			assertNotNull(restored.defaultReturnValue());
			assertSame(restored.defaultReturnValue(), restored.get(RAIL_START));
		}
	}

	private static void add(VehicleOccupancyMap map, Position first, Position second, double start, double end, long vehicleId) {
		VehicleOccupancyMap.record(map, first, second, current -> {
			final VehiclePosition value = current == null ? new VehiclePosition() : current;
			value.addSegment(start, end, vehicleId);
			return value;
		}, Object2ObjectAVLTreeMap::new);
	}

	private static double closest(VehicleOccupancyMap map, double start, double end, boolean reverse, long vehicleId) {
		return closest(map, RAIL_START, RAIL_END, start, end, reverse, vehicleId);
	}

	private static double closest(VehicleOccupancyMap map, Position first, Position second, double start, double end, boolean reverse, long vehicleId) {
		final VehiclePosition positions = VehicleOccupancyMap.get(map, first, second);
		return positions == null ? -1 : positions.getClosestOverlap(start, end, reverse, vehicleId);
	}
}
