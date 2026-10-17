package org.mtr.mod.mixin;

import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.data.VehicleOccupancyBuffers;
import org.mtr.mod.data.VehicleOccupancyMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Simulator.class, remap = false)
public abstract class SimulatorVehicleOccupancyMixin implements VehicleOccupancyBuffers.Owner {

	@Shadow @Final private ObjectImmutableList<ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>> vehiclePositions;

	@Redirect(method = "<init>", at = @At(value = "NEW", target = "org/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectAVLTreeMap"), require = 2, remap = false)
	private Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> mtr$createOccupancyBuffer() {
		return new VehicleOccupancyMap();
	}

	@Inject(method = "lambda$tick$15(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private static void mtr$rotateOccupancyBuffers(ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers, CallbackInfo callbackInfo) {
		VehicleOccupancyBuffers.rotate(buffers);
		callbackInfo.cancel();
	}

	@Override
	public void mtr$invalidateVehicleOccupancy() {
		// Simulator calls Data.sync before constructing these windows during startup.
		if (vehiclePositions != null) {
			for (int mode = 0; mode < vehiclePositions.size(); mode++) {
				final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers = vehiclePositions.get(mode);
				for (int window = 0; window < buffers.size(); window++) {
					if (buffers.get(window) instanceof VehicleOccupancyMap) {
						((VehicleOccupancyMap) buffers.get(window)).invalidateTopology();
					}
				}
			}
		}
	}
}
