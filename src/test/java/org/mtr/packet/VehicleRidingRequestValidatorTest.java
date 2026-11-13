package org.mtr.packet;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class VehicleRidingRequestValidatorTest {

	private static final UUID SENDER = UUID.fromString("12345678-1234-5678-9abc-123456789abc");

	@Test
	void acceptsOnlyConsistentSinglePlayerUpdates() {
		assertTrue(VehicleRidingRequestValidator.isValid(request(0), SENDER, false));
		assertTrue(VehicleRidingRequestValidator.isValid(request(7), SENDER, false));
		assertTrue(VehicleRidingRequestValidator.isValid(request(-1), SENDER, true));
		assertFalse(VehicleRidingRequestValidator.isValid(request(0), SENDER, true));
		assertFalse(VehicleRidingRequestValidator.isValid(request(-1), SENDER, false));
		assertFalse(VehicleRidingRequestValidator.isValid(request(-2), SENDER, true));
	}

	@Test
	void rejectsOtherPlayersAndMultipleRiderEntries() {
		final JsonObject request = request(0);
		rider(request).addProperty("clientId", UUID.fromString("ffffffff-1234-5678-9abc-123456789abc").toString());
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		rider(request).addProperty("clientId", SENDER.toString());
		request.getAsJsonArray("ridingEntities").add(rider(request).deepCopy());
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
	}

	@Test
	void rejectsMissingOrMalformedIdentityAndArray() {
		assertFalse(VehicleRidingRequestValidator.isValid(new JsonObject(), SENDER, false));
		final JsonObject request = request(0);
		rider(request).remove("clientId");
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		rider(request).addProperty("clientId", "not-a-uuid");
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		request.add("ridingEntities", new JsonArray());
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		request.getAsJsonArray("ridingEntities").add("not-an-object");
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		request.addProperty("ridingEntities", "not-an-array");
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
	}

	@Test
	void rejectsFractionalAndOverflowingCarNumbers() {
		final JsonObject request = request(0);
		rider(request).addProperty("ridingCar", -1.5);
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, true));
		rider(request).add("ridingCar", JsonParser.parseString("9223372036854775808"));
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		rider(request).addProperty("ridingCar", "0");
		assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
	}

	@Test
	void rejectsNonFiniteMissingAndNonNumericCoordinates() {
		for (final String coordinate : new String[]{"x", "y", "z"}) {
			for (final double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
				final JsonObject request = request(0);
				rider(request).addProperty(coordinate, invalid);
				assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
			}
			final JsonObject request = request(0);
			rider(request).remove(coordinate);
			assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
			rider(request).addProperty(coordinate, "NaN");
			assertFalse(VehicleRidingRequestValidator.isValid(request, SENDER, false));
		}
	}

	private static JsonObject request(long car) {
		final JsonObject request = new JsonObject();
		request.addProperty("sidingId", 1);
		request.addProperty("vehicleId", 2);
		final JsonObject rider = new JsonObject();
		rider.addProperty("clientId", SENDER.toString());
		rider.addProperty("ridingCar", car);
		rider.addProperty("x", 0.25);
		rider.addProperty("y", 1);
		rider.addProperty("z", -2.5);
		final JsonArray entries = new JsonArray();
		entries.add(rider);
		request.add("ridingEntities", entries);
		return request;
	}

	private static JsonObject rider(JsonObject request) {
		return request.getAsJsonArray("ridingEntities").get(0).getAsJsonObject();
	}
}
