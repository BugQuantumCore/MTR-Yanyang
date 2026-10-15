package org.mtr.mod.mixin;

import org.mtr.core.generated.data.RailSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.mod.data.RailSignalComponents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes inherited color data and invalidates when a serialized RailSchema is updated. */
@Mixin(value = RailSchema.class, remap = false)
public abstract class RailSignalSchemaMixin implements RailSignalComponents.SignalColorAccessor {
	@Shadow @Final protected LongArrayList signalColors;

	@Override
	public boolean mtr$hasSignalColor(long color) {
		return signalColors.contains(color);
	}

	@Inject(method = "updateData(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("RETURN"), remap = false, require = 1)
	private void mtr$invalidateAfterSerializedColorsChange(ReaderBase readerBase, CallbackInfo callbackInfo) {
		RailSignalComponents.invalidateAll();
	}
}
