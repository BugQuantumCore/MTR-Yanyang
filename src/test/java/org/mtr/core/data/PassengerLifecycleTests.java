package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class PassengerLifecycleTests {

	@TempDir
	Path directory;

	@Test
	void boardingPreferenceIncludesTheFirstCarAndExactBoundaries() {
		final ObjectImmutableList<VehicleCar> cars = ObjectImmutableList.of(car("first", 0), car("second", 0), car("third", 0));
		for (int position = 0; position < 30; position++) {
			assertEquals(position / 10, Passenger.getBoardingCar(position, 30, cars));
		}
		assertEquals(2, Passenger.getBoardingCar(-1, 30, cars));
		assertEquals(0, Passenger.getBoardingCar(30, 30, cars));
	}

	@Test
	void boardingPreferenceIncludesCouplingLengthsAndRejectsDegenerateConsists() {
		final ObjectImmutableList<VehicleCar> cars = ObjectImmutableList.of(car("first", 1), car("second", 1), car("third", 1));
		assertEquals(0, Passenger.getBoardingCar(10, 34, cars));
		assertEquals(1, Passenger.getBoardingCar(11, 34, cars));
		assertEquals(1, Passenger.getBoardingCar(22, 34, cars));
		assertEquals(2, Passenger.getBoardingCar(23, 34, cars));
		assertEquals(-1, Passenger.getBoardingCar(0, 0, cars));
		assertEquals(-1, Passenger.getBoardingCar(0, 0.1, cars));
		assertEquals(-1, Passenger.getBoardingCar(0, Double.NaN, cars));
		assertEquals(-1, Passenger.getBoardingCar(0, 10, ObjectImmutableList.of()));
	}

	@Test
	void deletedVehicleIsNotKeptAliveByThePassengerCache() throws ReflectiveOperationException {
		final Fixture fixture = fixture();
		vehicleMap(fixture.siding).remove(fixture.vehicle.getId());
		fixture.passenger.clearVehicleReferences(fixture.simulator);
		assertEquals(0, fixture.passenger.getVehicleId());
		assertEquals(-1, fixture.passenger.getVehicleCarNumber());
		assertTrue(fixture.vehicle.vehicleExtraData.passengers.getFirst().isEmpty());
	}

	@Test
	void continuingOnTheSameRouteStillSynchronizesTheConsumedLeg() throws ReflectiveOperationException {
		final Fixture fixture = fixture();
		final Platform start = new Platform(new Position(0, 0, 0), new Position(10, 0, 0), TransportMode.TRAIN, fixture.simulator);
		final Platform end = new Platform(new Position(20, 0, 0), new Position(30, 0, 0), TransportMode.TRAIN, fixture.simulator);
		final Route route = new Route(TransportMode.TRAIN, fixture.simulator);
		fixture.simulator.platformIdMap.put(start.getId(), start);
		fixture.simulator.platformIdMap.put(end.getId(), end);
		fixture.simulator.routeIdMap.put(route.getId(), route);
		fixture.passenger.getDirections().add(new PassengerDirection(route.getId(), start.getId(), end.getId(), 0, Long.MAX_VALUE));
		fixture.passenger.getDirections().add(new PassengerDirection(route.getId(), end.getId(), start.getId(), 0, Long.MAX_VALUE));
		setPassengerField(fixture.passenger, "dirtySync", false);
		assertTrue(fixture.passenger.tick(new Home(fixture.simulator), fixture.simulator));
		assertEquals(1, fixture.passenger.getDirections().size());
		assertEquals(fixture.vehicle.getId(), fixture.passenger.getVehicleId());
	}

	private Fixture fixture() throws ReflectiveOperationException {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, directory, false);
		final Siding siding = new Siding(new Position(0, 0, 0), new Position(10, 0, 0), 10, TransportMode.TRAIN, simulator);
		simulator.sidings.add(siding);
		simulator.sidingIdMap.put(siding.getId(), siding);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(car("first", 0));
		final ObjectArrayList<PathData> path = new ObjectArrayList<>();
		final VehicleExtraData extra = VehicleExtraData.create(0, siding.getId(), 10, cars, path, path, path,
				new PathData(new JsonReader(new JsonObject())), false, 0.1, 0.1, false, 0, 0);
		final Vehicle vehicle = new Vehicle(extra, siding, TransportMode.TRAIN, simulator);
		vehicleMap(siding).put(vehicle.getId(), vehicle);
		final JsonObject data = new JsonObject();
		data.addProperty("vehicleId", vehicle.getId());
		data.addProperty("sidingId", siding.getId());
		data.addProperty("vehicleCarNumber", 0);
		final Passenger passenger = new Passenger(new JsonReader(data), simulator);
		setPassengerField(passenger, "currentSiding", siding);
		setPassengerField(passenger, "currentVehicle", vehicle);
		extra.passengers.getFirst().add(passenger);
		return new Fixture(simulator, siding, vehicle, passenger);
	}

	private static VehicleCar car(String name, double padding) {
		return new VehicleCar(name, 10, 2, 100, 0, 5, padding, padding);
	}

	private static void setPassengerField(Passenger passenger, String name, Object value) throws ReflectiveOperationException {
		final Field field = Passenger.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(passenger, value);
	}

	@SuppressWarnings("unchecked")
	private static Long2ObjectOpenHashMap<Vehicle> vehicleMap(Siding siding) throws ReflectiveOperationException {
		final Field field = Siding.class.getDeclaredField("vehicleIdMap");
		field.setAccessible(true);
		return (Long2ObjectOpenHashMap<Vehicle>) field.get(siding);
	}

	private record Fixture(Simulator simulator, Siding siding, Vehicle vehicle, Passenger passenger) {
	}
}
