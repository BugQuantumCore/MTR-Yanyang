package org.mtr.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.mtr.tool.FakeEntityCollisions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the lithium-proof fake-entity collision fallback around {@link Entity#move}.
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
 * <p><b>Why the pre-move box is captured.</b> Resolving an overlap needs to know which side the
 * player entered the box from, and the post-move position alone cannot tell a landing apart
 * from a side hit — the root cause of the "fall onto a stationary train's roof and get flung
 * off sideways" bug (yanyang.15). The head injector snapshots the bounding box before the
 * movement is applied; the tail injector hands both boxes to the depenetration, which classifies
 * the entry direction from them: feet at or above a box's top surface before the move is a
 * <em>landing</em> (resolve straight up onto the surface, cancel the fall velocity, flag
 * onGround), everything else keeps the bounded minimum-axis eviction. The field is cleared
 * after use; a {@code null} box (defensive, e.g. another mixin cancelled {@code move} between
 * the two injectors) falls back to the post-move box, i.e. the pre-fix behaviour.</p>
 *
 * <p><b>Cost and safety.</b> The head handler is a field store; the tail's fast path is a type
 * check and two flag reads — on servers and for non-player movers it returns before touching
 * any registry. On the integrated server the fallback only ever acts on client-side movement
 * (single-player movement is client-authoritative, so the corrected position is the one the
 * game keeps); on dedicated servers the registries are always empty and the handler is a
 * no-op.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMoveMixin {

        @Unique
        private AABB mtr$boxBeforeMove;

        @Inject(method = "move", at = @At("HEAD"))
        private void mtr$captureBoxBeforeMove(MoverType moverType, Vec3 movement, CallbackInfo callbackInfo) {
                this.mtr$boxBeforeMove = ((Entity) (Object) this).getBoundingBox();
        }

        @Inject(method = "move", at = @At("TAIL"))
        private void mtr$depenetrateFakeEntities(MoverType moverType, Vec3 movement, CallbackInfo callbackInfo) {
                FakeEntityCollisions.depenetrate((Entity) (Object) this, this.mtr$boxBeforeMove);
                this.mtr$boxBeforeMove = null;
        }
}
