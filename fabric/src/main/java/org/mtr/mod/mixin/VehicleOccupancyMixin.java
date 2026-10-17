package org.mtr.mod.mixin;

import org.mtr.core.data.Position;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehiclePosition;
import org.mtr.mod.data.VehicleOccupancyMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Routes the old core's nested AVL occupancy writes and reads through the compact per-layer index. */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleOccupancyMixin {

	@Redirect(
			method = "writeVehiclePositions",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/core/data/Data;put(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/Function;Ljava/util/function/Supplier;)V"
			),
			remap = false
	)
	@SuppressWarnings("rawtypes")
	private void mtr$recordVehiclePosition(Map positions, Object firstPosition, Object secondPosition,
			Function<VehiclePosition, VehiclePosition> putValue,
			Supplier<Object2ObjectAVLTreeMap<Position, VehiclePosition>> innerMapSupplier) {
		VehicleOccupancyMap.record(positions, firstPosition, secondPosition, putValue, innerMapSupplier);
	}

	@Redirect(
			method = "railBlockedDistance",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/core/data/Data;tryGet(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
			),
			remap = false
	)
	@SuppressWarnings("rawtypes")
	private Object mtr$getVehiclePosition(Map positions, Object firstPosition, Object secondPosition) {
		return VehicleOccupancyMap.get(positions, firstPosition, secondPosition);
	}
}
