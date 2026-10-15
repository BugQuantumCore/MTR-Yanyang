package org.mtr.mod.mixin;

import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.mod.data.RailSignalBuffers;
import org.mtr.mod.data.ServerRailVisibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Rail.class, remap = false)
public abstract class RailTickMixin {
	@Shadow @Final @Mutable private Long2LongAVLTreeMap preBlockedVehicleIds;
	@Shadow @Final @Mutable private Long2LongAVLTreeMap currentlyBlockedVehicleIds;
	@Shadow @Final @Mutable private Long2LongAVLTreeMap preBlockedVehicleIdsOld;
	@Shadow @Final @Mutable private Long2LongAVLTreeMap currentlyBlockedVehicleIdsOld;

	@Inject(method = "tick1(Lorg/mtr/core/simulation/Simulator;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtr$reuseSignalWindows(Simulator simulator, CallbackInfo callbackInfo) {
		final boolean needsUpdate =
				!RailSignalBuffers.sameKeys(preBlockedVehicleIds, preBlockedVehicleIdsOld) ||
						!RailSignalBuffers.sameKeys(currentlyBlockedVehicleIds, currentlyBlockedVehicleIdsOld);

		// Preserve the original notification order and notify while the four windows still hold
		// the state that Rail.tick1 would have exposed to each client.
		ServerRailVisibility.updateClients(simulator, (Rail) (Object) this, needsUpdate);

		final Long2LongAVLTreeMap previousPre = preBlockedVehicleIds;
		preBlockedVehicleIds = RailSignalBuffers.recycle(preBlockedVehicleIdsOld);
		preBlockedVehicleIdsOld = previousPre;

		final Long2LongAVLTreeMap previousCurrent = currentlyBlockedVehicleIds;
		currentlyBlockedVehicleIds = RailSignalBuffers.recycle(currentlyBlockedVehicleIdsOld);
		currentlyBlockedVehicleIdsOld = previousCurrent;

		callbackInfo.cancel();
	}
}
