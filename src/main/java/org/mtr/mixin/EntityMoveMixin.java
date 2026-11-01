package org.mtr.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.mtr.tool.FakeEntityCollisions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the lithium-proof fake-entity collision fallback at the tail of {@link Entity#move}.
 *
 * <p><b>Why {@code move} and not the entity-collision funnel.</b> The primary funnel
 * ({@link LevelEntityCollisionsMixin} into {@code EntityGetter#getEntityCollisions}) makes the
 * AI-passenger and train-car boxes solid for any installation where vanilla runs the movement
 * collision itself. Lithium — present in a large share of real modpacks, including the ones this
 * build was reported from — rewrites the internals of {@code Entity#collide}: its
 * {@code entity.collisions.movement} module wraps the inner {@code getEntityCollisions} call
 * site with a postponed-collection handler that returns an empty list and resolves the sweep in
 * its own static implementation, so the patched default-method body is never entered and the
 * funnel contributes nothing (no crash, no log — the boxes just do not exist). The public
 * {@code Entity#move}, on the other hand, is left untouched by lithium — it still runs, start
 * to finish, on every installation — which makes its tail the one reliable observation point
 * for "where did the movement actually put the player". After vanilla (or lithium's rewrite of
 * its internals — either way) has applied the movement, {@link FakeEntityCollisions#depenetrate}
 * pushes the mover out of any registered fake-entity box the finished position overlaps.</p>
 *
 * <p><b>Cost and safety.</b> The handler is a tail injector on a hot path (every entity, every
 * tick), so the fast path is a type check and two flag reads — on servers and for non-player
 * movers it returns before touching any registry. On the integrated server the fallback only
 * ever acts on client-side movement (single-player movement is client-authoritative, so the
 * corrected position is the one the game keeps); on dedicated servers the registries are always
 * empty and the handler is a no-op.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMoveMixin {

        @Inject(method = "move", at = @At("TAIL"))
        private void mtr$depenetrateFakeEntities(MoverType moverType, Vec3 movement, CallbackInfo callbackInfo) {
                FakeEntityCollisions.depenetrate((Entity) (Object) this);
        }
}
