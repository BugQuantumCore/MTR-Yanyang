package org.mtr.resource;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

import static org.junit.jupiter.api.Assertions.*;

final class VehicleResourceDataTest {

	@Test
	void fallbackBoundsReachTheReturnedCacheAndPassengerAreaDistribution() {
		final ObjectArrayList<AABB> floors = new ObjectArrayList<>();
		final ObjectArrayList<AABB> doorways = new ObjectArrayList<>();
		VehicleResource.addFallbackBounds(floors, doorways, 2, 10, 0.5);
		final VehicleResourceCache cache = new VehicleResourceCache(new ObjectArrayList<>(), new ObjectArrayList<>(), new ObjectArrayList<>(),
			new ObjectImmutableList<>(floors), new ObjectImmutableList<>(doorways));

		assertEquals(1, cache.floors.size());
		assertEquals(20, cache.doorways.size());
		assertEquals(1, cache.floorsWithNormalizedArea.size());
		final AABB floor = cache.floors.getFirst();
		assertEquals(-1.25, floor.minX);
		assertEquals(1.25, floor.maxX);
		assertEquals(1.5, floor.minY);
		assertEquals(1.5, floor.maxY);
		assertEquals(-4.5, floor.minZ);
		assertEquals(4.5, floor.maxZ);
		assertSame(floor, cache.floorsWithNormalizedArea.getFirst().floor);
	}

	@Test
	void emptyModelResourceReturnsAnEmptyCacheWithoutInventingCollisionGeometry() {
		final VehicleResourceCache cache = resource(137).getCachedVehicleResource(0, 1);
		assertNotNull(cache);
		assertTrue(cache.floors.isEmpty());
		assertTrue(cache.doorways.isEmpty());
		assertTrue(cache.floorsWithNormalizedArea.isEmpty());
	}

	@Test
	void capacitySurvivesTheEditorWrapperAndJsonRoundTrip() {
		final VehicleResource original = resource(137);
		final VehicleResourceWrapper wrapper = original.toVehicleResourceWrapper();
		final JsonObject serialized = Utilities.getJsonObjectFromData(wrapper);
		assertEquals(137, serialized.get("capacity").getAsLong());
		final VehicleResourceWrapper decoded = new VehicleResourceWrapper(new JsonReader(serialized));
		final VehicleResource restored = decoded.toVehicleResource(identifier -> "", null, null);
		assertEquals(original.getCapacity(), restored.getCapacity());
	}

	private static VehicleResource resource(long capacity) {
		final JsonObject json = new JsonObject();
		json.addProperty("id", "test_empty");
		json.addProperty("name", "Test vehicle");
		json.addProperty("transportMode", "TRAIN");
		json.addProperty("capacity", capacity);
		json.addProperty("width", 2);
		json.addProperty("length", 10);
		return new VehicleResource(new JsonReader(json), identifier -> "");
	}
}
