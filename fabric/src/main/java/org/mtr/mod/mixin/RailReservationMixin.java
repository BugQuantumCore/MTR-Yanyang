package org.mtr.mod.mixin;

import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mod.data.RailSignalComponents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces repeated old-core signal DFS walks with a shared same-color topology component. */
@Mixin(value = Rail.class, remap = false)
public abstract class RailReservationMixin implements RailSignalComponents.RailAccess,
		RailSignalComponents.RailGraphAccessor, RailSignalComponents.RailCacheOwner,
		RailSignalComponents.ReservationMapAccessor {

	@Shadow @Final private ObjectOpenHashSet<Rail> connectedRails1;
	@Shadow @Final private ObjectOpenHashSet<Rail> connectedRails2;
	@Shadow @Final private Long2LongAVLTreeMap preBlockedVehicleIds;
	@Shadow @Final private Long2LongAVLTreeMap currentlyBlockedVehicleIds;

	@Unique private volatile RailSignalComponents.RailCache mtr$railSignalCache;
	@Unique private static final ThreadLocal<ObjectOpenHashSet<Rail>> MTR$VISITED_RAILS = ThreadLocal.withInitial(ObjectOpenHashSet::new);

	@Invoker(value = "reserveRail", remap = false)
	private static void mtr$invokeOriginalReserveRail(long owner, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
		throw new AssertionError("Mixin invoker was not transformed");
	}

	@Override
	public boolean hasSignalColor(Rail rail, long color) {
		return ((RailSignalComponents.SignalColorAccessor) (Object) rail).mtr$hasSignalColor(color);
	}

	@Override
	public Iterable<Rail> connectedRails1(Rail rail) {
		return ((RailSignalComponents.RailGraphAccessor) (Object) rail).mtr$connectedRails1();
	}

	@Override
	public Iterable<Rail> connectedRails2(Rail rail) {
		return ((RailSignalComponents.RailGraphAccessor) (Object) rail).mtr$connectedRails2();
	}

	@Override
	public RailSignalComponents.RailCache cacheFor(Rail rail) {
		return ((RailSignalComponents.RailCacheOwner) (Object) rail).mtr$getRailSignalCache();
	}

	@Override
	public void writeReservation(Rail rail, long color, long owner, boolean currentlyBlocked) {
		((RailSignalComponents.ReservationMapAccessor) (Object) rail).mtr$writeRailReservation(color, owner, currentlyBlocked);
	}

	@Override
	public Iterable<Rail> mtr$connectedRails1() {
		return connectedRails1;
	}

	@Override
	public Iterable<Rail> mtr$connectedRails2() {
		return connectedRails2;
	}

	@Override
	public RailSignalComponents.RailCache mtr$getRailSignalCache() {
		RailSignalComponents.RailCache cache = mtr$railSignalCache;
		if (cache == null) {
			synchronized (this) {
				cache = mtr$railSignalCache;
				if (cache == null) {
					cache = new RailSignalComponents.RailCache();
					mtr$railSignalCache = cache;
				}
			}
		}
		return cache;
	}

	@Override
	public void mtr$writeRailReservation(long color, long owner, boolean currentlyBlocked) {
		(currentlyBlocked ? currentlyBlockedVehicleIds : preBlockedVehicleIds).put(color, owner);
	}

	@Inject(method = "applyModification(Lorg/mtr/core/data/SignalModification;)V", at = @At("RETURN"), remap = false, require = 1)
	private void mtr$invalidateAfterSignalModification(SignalModification modification, CallbackInfo callbackInfo) {
		RailSignalComponents.invalidateAll();
	}

	@Inject(method = "copySignalColors(Lorg/mtr/core/data/Rail;)V", at = @At("RETURN"), remap = false, require = 1)
	private void mtr$invalidateAfterCopySignalColors(Rail rail, CallbackInfo callbackInfo) {
		RailSignalComponents.invalidateAll();
	}

	@Inject(method = "writeConnectedRailsCacheFromMap(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;)V", at = @At("RETURN"), remap = false, require = 1)
	private void mtr$invalidateAfterConnectedRailsUpdate(Object2ObjectOpenHashMap<?, ?> positionsToRail, CallbackInfo callbackInfo) {
		RailSignalComponents.invalidateAll();
	}

	@Redirect(method = "lambda$isBlocked$5(JLorg/mtr/core/data/Rail$BlockReservation;J)V",
		at = @At(value = "NEW", target = "org/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectOpenHashSet"), remap = false, require = 1)
	private ObjectOpenHashSet<Rail> mtr$reuseIsBlockedVisitedSet() {
		final ObjectOpenHashSet<Rail> visitedRails = MTR$VISITED_RAILS.get();
		visitedRails.clear();
		return visitedRails;
	}

	@Redirect(method = "lambda$isBlocked$5(JLorg/mtr/core/data/Rail$BlockReservation;J)V",
		at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Rail;reserveRail(JJLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Lorg/mtr/core/data/Rail;Z)V"), remap = false, require = 1)
	private void mtr$reserveBlockedSignalComponent(long owner, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
		mtr$reserveOrFallback(owner, color, visitedRails, rail, currentlyBlocked);
	}

	@Redirect(method = "lambda$tick2$1(J)V",
		at = @At(value = "NEW", target = "org/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectOpenHashSet"), remap = false, require = 1)
	private ObjectOpenHashSet<Rail> mtr$reuseTick2VisitedSet() {
		final ObjectOpenHashSet<Rail> visitedRails = MTR$VISITED_RAILS.get();
		visitedRails.clear();
		return visitedRails;
	}

	@Redirect(method = "lambda$tick2$1(J)V",
		at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Rail;reserveRail(JJLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectOpenHashSet;Lorg/mtr/core/data/Rail;Z)V"), remap = false, require = 1)
	private void mtr$reserveManualBlockComponent(long owner, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
		mtr$reserveOrFallback(owner, color, visitedRails, rail, currentlyBlocked);
	}

	@Unique
	private static void mtr$reserveOrFallback(long owner, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
		try {
			if (!RailSignalComponents.reserveComponent(rail, color, owner, currentlyBlocked)) {
				visitedRails.clear();
				mtr$invokeOriginalReserveRail(owner, color, visitedRails, rail, currentlyBlocked);
			}
		} finally {
			visitedRails.clear();
		}
	}
}
