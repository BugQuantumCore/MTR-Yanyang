package org.mtr.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.mtr.tool.PassengerCollisions;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends the AI-passenger collision boxes to entity movement collision queries.
 *
 * <p>The real entity-collision funnel is the {@code EntityGetter#getEntityCollisions} default
 * method (verified against the 1.21.1 and 1.21.4 bytecode): {@code Level} itself does not
 * declare the method — calls dispatch through {@code CommonLevelAccessor}'s delegating
 * default into this implementation, which is the same list that makes boats and shulkers
 * solid. {@code PathNavigationRegion} overrides the method with its own empty version, so
 * mob pathfinding never sees the injected boxes either.</p>
 *
 * <p><b>The mixin must be an interface.</b> Sponge Mixin's subtype check rejects a standard
 * (class) mixin whose target is an interface with {@code @Mixin target type mismatch:
 * net.minecraft.class_1924 is an interface} — exactly the crash the first yanyang.13 build
 * shipped (interface target, class mixin). With the mixin declared as an interface the
 * subtype flips to {@code SubType$Interface} and injectors are supported natively:
 * {@code Feature.INJECTORS_IN_INTERFACE_MIXINS} is enabled whenever the compatibility level
 * supports methods in interfaces, which {@code JAVA_21} does, so {@code MixinApplicatorInterface}
 * routes the injector through the standard machinery. The handler itself must carry a body,
 * hence {@code default} instead of the {@code private} a class mixin would use.</p>
 *
 * <p>The injected tail adds the player-sized boxes that {@link PassengerCollisions} publishes
 * for on-foot AI passengers, so the local player can no longer walk through the platform
 * crowds. {@link PassengerCollisions#collect} filters to client-side player movers in the
 * matching dimension and returns an empty list everywhere else (dedicated servers, mob
 * pathfinding on the integrated server, non-level implementers) — the injected body then
 * falls straight through, leaving the original return value untouched.</p>
 */
@Mixin(EntityGetter.class)
public interface LevelEntityCollisionsMixin {

	@Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
	default void mtr$addPassengerCollisions(Entity entity, AABB collisionBox, CallbackInfoReturnable<List<VoxelShape>> cir) {
		final List<VoxelShape> passengerShapes = PassengerCollisions.collect(this, entity, collisionBox);
		if (passengerShapes.isEmpty()) {
			return;
		}
		final List<VoxelShape> combined = new ArrayList<>(cir.getReturnValue());
		combined.addAll(passengerShapes);
		cir.setReturnValue(combined);
	}
}
