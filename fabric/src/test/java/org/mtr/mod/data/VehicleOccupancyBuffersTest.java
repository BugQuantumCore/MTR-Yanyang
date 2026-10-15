package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleOccupancyBuffersTest {
	private static final Position FIRST = new Position(0, 64, 0);
	private static final Position SECOND = new Position(100, 64, 0);

	@Test
	void preservesThePreviousWindowAndClearsOnlyTheRetiredWindow() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers = new ObjectArrayList<>();
		buffers.add(new Object2ObjectAVLTreeMap<>());
		buffers.add(new Object2ObjectAVLTreeMap<>());
		VehicleOccupancyBuffers.rotate(buffers);
		VehicleOccupancyBuffers.rotate(buffers);
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> retired = buffers.get(0);
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> previous = buffers.get(1);
		write(retired, 10, 20, 1);
		write(previous, 30, 40, 2);

		VehicleOccupancyBuffers.rotate(buffers);
		assertSame(previous, buffers.get(0));
		assertSame(retired, buffers.get(1));
		assertEquals(30, Data.tryGet(buffers.get(0), FIRST, SECOND).getClosestOverlap(0, 100, false, 9));
		assertTrue(buffers.get(1).isEmpty());
		write(buffers.get(1), 50, 60, 3);
		assertEquals(30, Data.tryGet(buffers.get(0), FIRST, SECOND).getClosestOverlap(0, 100, false, 9));
		assertEquals(50, Data.tryGet(buffers.get(1), FIRST, SECOND).getClosestOverlap(0, 100, false, 9));
	}

	@Test
	void doesNotClearARetiredMapStillAliasedByThePreviousWindow() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers = new ObjectArrayList<>();
		VehicleOccupancyBuffers.rotate(buffers);
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> shared = buffers.get(0);
		write(shared, 10, 20, 1);
		buffers.add(shared);
		VehicleOccupancyBuffers.rotate(buffers);
		assertSame(shared, buffers.get(0));
		assertNotSame(shared, buffers.get(1));
		assertEquals(10, Data.tryGet(buffers.get(0), FIRST, SECOND).getClosestOverlap(0, 100, false, 9));
		assertTrue(buffers.get(1).isEmpty());
	}

	@Test
	void emptyInputRetainsTheOriginalOneWindowBehavior() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers = new ObjectArrayList<>();
		VehicleOccupancyBuffers.rotate(buffers);
		assertEquals(1, buffers.size());
		assertTrue(buffers.get(0).isEmpty());
		VehicleOccupancyBuffers.rotate(buffers);
		assertEquals(1, buffers.size());
		assertTrue(buffers.get(0).isEmpty());
	}

	private static void write(Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> map, double start, double end, long id) {
		Data.put(map, FIRST, SECOND, old -> {
			final VehiclePosition value = old == null ? new VehiclePosition() : old;
			value.addSegment(start, end, id);
			return value;
		}, Object2ObjectAVLTreeMap::new);
	}
}
