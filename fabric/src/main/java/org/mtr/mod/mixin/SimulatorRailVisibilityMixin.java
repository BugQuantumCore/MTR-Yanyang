package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.mod.data.ServerRailVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

@Mixin(value = Simulator.class, remap = false)
public abstract class SimulatorRailVisibilityMixin implements ServerRailVisibility.Owner {

	@Unique private ServerRailVisibility mtr$railVisibility;

	@Override
	public ServerRailVisibility mtr$getRailVisibility() {
		return mtr$railVisibility;
	}

	@WrapOperation(method = "tick(J)V", at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;forEach(Ljava/util/function/Consumer;)V", ordinal = 0), remap = false)
	private void mtr$prepareRailVisibility(ObjectArraySet<Rail> rails, Consumer<? super Rail> action, Operation<Void> original) {
		if (mtr$railVisibility == null) {
			final Simulator simulator = (Simulator) (Object) this;
			mtr$railVisibility = new ServerRailVisibility(simulator, simulator.clients);
		}
		try {
			mtr$railVisibility.beginRailTick();
			original.call(rails, action);
		} finally {
			mtr$railVisibility.endRailTick();
		}
	}
}
