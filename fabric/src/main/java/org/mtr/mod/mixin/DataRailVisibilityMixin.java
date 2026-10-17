package org.mtr.mod.mixin;

import org.mtr.core.data.Data;
import org.mtr.mod.data.ServerRailVisibility;
import org.mtr.mod.data.RailSignalComponents;
import org.mtr.mod.data.VehicleOccupancyBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Data.class, remap = false)
public abstract class DataRailVisibilityMixin {

	@Inject(method = "sync()V", at = @At("HEAD"), remap = false)
	private void mtr$invalidateRailVisibility(CallbackInfo callbackInfo) {
		RailSignalComponents.invalidateAll();
		if ((Object) this instanceof VehicleOccupancyBuffers.Owner) {
			((VehicleOccupancyBuffers.Owner) this).mtr$invalidateVehicleOccupancy();
		}
		if ((Object) this instanceof ServerRailVisibility.Owner) {
			final ServerRailVisibility visibility = ((ServerRailVisibility.Owner) this).mtr$getRailVisibility();
			if (visibility != null) {
				visibility.invalidate();
			}
		}
	}
}
