package org.mtr.packet;

import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.UUID;

/** Pure validation for the client-to-server riding update; no Minecraft state is accessed. */
final class VehicleRidingRequestValidator {

	private VehicleRidingRequestValidator() {
	}

	static boolean isValid(JsonObject request, UUID senderId, boolean dismount) {
		try {
			final JsonElement entries = request.get("ridingEntities");
			if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().size() != 1) {
				return false;
			}
			final JsonElement entry = entries.getAsJsonArray().get(0);
			if (!entry.isJsonObject()) {
				return false;
			}
			final JsonObject rider = entry.getAsJsonObject();
			final JsonElement clientId = rider.get("clientId");
			if (clientId == null || !clientId.isJsonPrimitive() || !clientId.getAsJsonPrimitive().isString() ||
					!senderId.equals(UUID.fromString(clientId.getAsString()))) {
				return false;
			}
			final JsonElement car = rider.get("ridingCar");
			if (car == null || !car.isJsonPrimitive() || !car.getAsJsonPrimitive().isNumber()) {
				return false;
			}
			// Do not allow a fractional or overflowing JSON number to be truncated into
			// a valid car index by the schema reader's getAsLong conversion.
			final long ridingCar = car.getAsBigDecimal().longValueExact();
			return ridingCar >= -1 && dismount == (ridingCar < 0) &&
					isFiniteCoordinate(rider.get("x")) && isFiniteCoordinate(rider.get("y")) && isFiniteCoordinate(rider.get("z"));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean isFiniteCoordinate(JsonElement value) {
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble());
	}
}
