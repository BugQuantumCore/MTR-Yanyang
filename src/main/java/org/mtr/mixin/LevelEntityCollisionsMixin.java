package org.mtr.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
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
 * <p>{@code Level#getEntityCollisions} is the single funnel every entity movement collision
 * goes through (the same list that makes boats and shulkers solid via
 * {@code Entity#canBeCollidedWith}). The injected tail adds the player-sized boxes that
 * {@link PassengerCollisions} publishes for on-foot AI passengers, so the local player can no
 * longer walk through the platform crowds.</p>
 *
 * <p>{@link PassengerCollisions#collect} filters to client-side player movers in the matching
 * dimension, and returns an empty list everywhere else (dedicated servers, mob pathfinding on
 * the integrated server) — the injected body then falls straight through, leaving the original
 * return value untouched.</p>
 */
@Mixin(Level.class)
public abstract class LevelEntityCollisionsMixin {

	@Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
	private void mtr$addPassengerCollisions(Entity entity, AABB collisionBox, CallbackInfoReturnable<List<VoxelShape>> cir) {
		final List<VoxelShape> passengerShapes = PassengerCollisions.collect((Level) (Object) this, entity, collisionBox);
		if (passengerShapes.isEmpty()) {
			return;
		}
		final List<VoxelShape> combined = new ArrayList<>(cir.getReturnValue());
		combined.addAll(passengerShapes);
		cir.setReturnValue(combined);
	}
}
