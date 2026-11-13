package org.mtr.tool;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

final class CollisionSnapshotTest {

	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("mtr", "collision_test"));
	private static final ResourceKey<Level> OTHER_DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("mtr", "other_collision_test"));
	private static final AABB QUERY = new AABB(-100, -100, -100, 100, 100, 100);

	@AfterEach
	void clear() {
		PassengerCollisions.clear();
		VehicleCollisions.clear();
	}

	@Test
	void depenetrationUsesExteriorFacesForContainedPlayers() {
		Assertions.assertEquals(2.3, FakeEntityCollisions.horizontalSeparation(-0.3, 0.3, -2, 2), 1E-10);
		Assertions.assertEquals(-0.2, FakeEntityCollisions.horizontalSeparation(-2.4, -1.8, -2, 2), 1E-10);
		Assertions.assertEquals(0.2, FakeEntityCollisions.horizontalSeparation(1.8, 2.4, -2, 2), 1E-10);
	}

	@Test
	void publishesMovedAndNewPassengersWithoutWaitingForExpiry() {
		PassengerCollisions.update(1, DIMENSION, 0, 0, 0);
		PassengerCollisions.endFrame();
		Assertions.assertEquals(-0.3, PassengerCollisions.collectBoxes(DIMENSION, QUERY).getFirst().minX);

		PassengerCollisions.update(1, DIMENSION, 10, 0, 0);
		PassengerCollisions.update(2, DIMENSION, 20, 0, 0);
		PassengerCollisions.endFrame();
		final List<AABB> boxes = PassengerCollisions.collectBoxes(DIMENSION, QUERY);
		Assertions.assertEquals(2, boxes.size());
		Assertions.assertTrue(boxes.stream().anyMatch(box -> box.minX == 9.7));
		Assertions.assertTrue(boxes.stream().anyMatch(box -> box.minX == 19.7));
		Assertions.assertTrue(PassengerCollisions.collectBoxes(OTHER_DIMENSION, QUERY).isEmpty());
	}

	@Test
	void publishesMovingCarsAndDoesNotTruncateVehicleIds() {
		final long firstVehicle = 1;
		final long secondVehicle = 1 + (1L << 48);
		VehicleCollisions.updateCar(firstVehicle, 0, DIMENSION, List.of(new AABB(0, 0, 0, 2, 2, 2)), 1);
		VehicleCollisions.endFrame();
		VehicleCollisions.updateCar(firstVehicle, 0, DIMENSION, List.of(new AABB(10, 0, 0, 12, 2, 2)), 1);
		VehicleCollisions.updateCar(secondVehicle, 0, DIMENSION, List.of(new AABB(20, 0, 0, 22, 2, 2)), 1);
		VehicleCollisions.endFrame();
		final List<AABB> boxes = VehicleCollisions.collectBoxes(DIMENSION, QUERY);
		Assertions.assertEquals(2, boxes.size());
		Assertions.assertTrue(boxes.stream().anyMatch(box -> box.minX == 10));
		Assertions.assertTrue(boxes.stream().anyMatch(box -> box.minX == 20));

		VehicleCollisions.removeVehicle(firstVehicle);
		VehicleCollisions.endFrame();
		Assertions.assertEquals(20, VehicleCollisions.collectBoxes(DIMENSION, QUERY).getFirst().minX);
		VehicleCollisions.updateCar(secondVehicle, 0, DIMENSION, List.of(), 1);
		VehicleCollisions.endFrame();
		Assertions.assertTrue(VehicleCollisions.collectBoxes(DIMENSION, QUERY).isEmpty());
	}
}
