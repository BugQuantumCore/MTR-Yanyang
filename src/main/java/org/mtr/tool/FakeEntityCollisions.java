package org.mtr.tool;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The lithium-proof half of the fake-entity collision system (为AI乘客和列车提供锂兼容的碰撞回退).
 *
 * <p><b>Why this exists.</b> The primary funnel — the {@code EntityGetter#getEntityCollisions}
 * mixin ({@code LevelEntityCollisionsMixin}) — appends the passenger and train-car boxes to the
 * entity movement-collision list, which makes them perfectly solid on a vanilla installation.
 * <b>Lithium</b>, however, rewrites {@code Entity#collide} from the inside: its
 * {@code entity.collisions.movement} module wraps the inner {@code Level#getEntityCollisions}
 * call site with a "postponed entity collection" that returns an empty list immediately and
 * resolves the sweep in its own static {@code LithiumEntityCollisions#collideMovement} — the
 * patched default-method body is never entered, so on lithium installs the funnel is silently a
 * no-op: no errors, no log lines, and players walk straight through crowds and standing trains
 * (exactly the reported symptom).</p>
 *
 * <p><b>The fix.</b> This fallback hooks {@code Entity#move} <em>after the fact</em>
 * ({@code EntityMoveMixin}, injected at the method tail — a method lithium never touches: it
 * rewires the internals of {@code collide}, but the public {@code move} that calls it runs
 * untouched). Whenever the mover is a client-side player whose post-movement bounding box
 * overlaps one of the registered fake-entity boxes, the player is pushed back out of it. On
 * lithium the funnel contributes nothing and this fallback does all the work; without lithium
 * the funnel blocks the movement before any overlap can form and this fallback sees nothing to
 * fix. The two mechanisms compose without conflict.</p>
 *
 * <p><b>Landings versus side hits (yanyang.16).</b> The pre-fix depenetration resolved every
 * overlap along the axis of minimum penetration, decided by centre comparison — which cannot
 * tell <em>how</em> the overlap came to be. A player falling onto a stationary train's roof
 * penetrates deep in one tick (up to terminal velocity, ~3.9 blocks); once the feet pass the
 * hull box's centre the up direction disqualified itself, the sideways axis won, and the player
 * was ejected off the train while still falling — reported as "落到车顶上不是踩上去，而是被撞飞".
 * The depenetration now receives the player's bounding box from <em>before</em> the move
 * (captured by the mixin's head injector) and classifies the entry first: when the feet were at
 * or above a box's top surface before the move (within one vanilla step height, so walking
 * between cars' roofs on a gradient also resolves up), the overlap is the tail of a fall and
 * resolves <em>straight up onto the surface</em> — the player is snapped to the roof, the fall
 * velocity is cancelled, the fall distance reset and ground contact flagged, so the player
 * actually stands on the roof (jumping, friction and step-up all behave). Everything entered
 * from the side — a wall, a crowd, a train sweeping into the player — keeps the bounded
 * minimum-axis eviction below.</p>
 *
 * <p><b>Why the push is bounded and directional.</b> The correction is capped at
 * {@link #MAX_CORRECTION} per move so a pathological overlap can never teleport the player
 * across the world, and the down direction is excluded for side entries: pushing the player
 * <em>down</em> would only shove them into the track underneath a train's hull instead of out
 * of it, so a below-box overlap resolves sideways. The landing branch is exempt from the cap on
 * purpose: its correction is bounded by however deep this single move fell into the box, i.e.
 * it returns the player to the surface they crossed a moment ago — visually the same frame, so
 * it reads as a landing, never as a teleport.</p>
 *
 * <p><b>Server safety.</b> Exactly like the registries it reads, this class lives in the shared
 * {@code tool} package and imports only common (non-client) Minecraft classes, so the dedicated
 * server can load it safely: the registries are never populated there, the fast-path returns
 * before any work happens, and the movement of every server-side entity is untouched. On the
 * integrated server only the client-side movement pass sees the boxes — the server player's own
 * collision resolution is not modified (single-player movement is client-authoritative, so the
 * corrected client position is what the game uses).</p>
 */
public final class FakeEntityCollisions {

        /**
         * Upper bound on the positional correction applied in one move — a passing train can
         * sweep its hull over a standing player by more than a block per tick, and the push
         * should evict them in a single correction rather than nudging; anything beyond this
         * would read as a teleport. (Landing corrections are not capped — see the class doc.)
         */
        private static final double MAX_CORRECTION = 2.0;

        /** Overlaps smaller than this are ignored — they cannot be resolved reliably anyway. */
        private static final double MIN_OVERLAP = 0.001;

        /**
         * How far below a box's top surface the player's feet may have been before the move for
         * the overlap to still count as a landing — the vanilla step height, so walking from one
         * car's roof onto a marginally higher one steps up instead of shoving the player
         * sideways. Taller height differences (a platform stander brushing a car's side, the
         * crowd at platform level) never qualify; they keep the side eviction.
         */
        private static final double STEP_UP = 0.6;

        private FakeEntityCollisions() {
        }

        /**
         * Called from {@code EntityMoveMixin} around {@code Entity#move}: pushes a client-side
         * player out of any registered AI-passenger or train-car collision box that the finished
         * movement left them overlapping, classifying the entry direction from the pre-move box
         * (feet at/above a box top before the move = landing; everything else = side eviction).
         *
         * @param entity     the entity that just moved
         * @param beforeMove the entity's bounding box captured before the movement was applied;
         *                   {@code null} defensively (no entry direction can then be inferred and
         *                   only the side eviction runs, the pre-yanyang.16 behaviour)
         */
        public static void depenetrate(Entity entity, @Nullable AABB beforeMove) {
                if (!(entity instanceof Player) || entity.isSpectator()) {
                        return;
                }

                final Level level = entity.level();
                if (!level.isClientSide()) {
                        return;
                }

                final AABB boundingBox = entity.getBoundingBox();
                // Include neighbours a correction can reach, not only the original overlap.
                final AABB queryBox = boundingBox.inflate(MAX_CORRECTION);
                final List<AABB> boxes = PassengerCollisions.collectBoxes(level, queryBox);
                boxes.addAll(VehicleCollisions.collectBoxes(level, queryBox));
                if (boxes.isEmpty()) {
                        return;
                }

                final AABB before = beforeMove == null ? boundingBox : beforeMove;

                double moveX = 0;
                double moveY = 0;
                double moveZ = 0;
                AABB current = boundingBox;
                boolean landed = false;
                double remainingCorrection = MAX_CORRECTION;

                // Up to four eviction passes — a landing can consume one, and resolving one
                // overlap can slide the player into a neighbouring box (a crowd, a two-track
                // train shed); a fifth would mean the geometry is genuinely enclosing the
                // player.
                for (int pass = 0; pass < 4; pass++) {
                        AABB landing = null;
                        AABB hit = null;
                        int axis = -1;
                        int direction = 0;
                        double depth = Double.POSITIVE_INFINITY;

                        for (final AABB box : boxes) {
                                if (!current.intersects(box)) {
                                        continue;
                                }

                                // Landing / step-up: the feet were at or above this box's top
                                // surface before the move (within the step height). The overlap
                                // is then the tail of a fall onto the surface — resolve it
                                // vertically below, never sideways: the min-axis heuristic
                                // picking a horizontal axis here is exactly how "fall onto a
                                // parked train" turned into "flung off sideways".
                                if (before.minY >= box.maxY - STEP_UP) {
                                        if (box.maxY - current.minY > MIN_OVERLAP && (landing == null || box.maxY > landing.maxY)) {
                                                landing = box;
                                        }
                                        continue;
                                }

                                final double overlapX = Math.min(current.maxX, box.maxX) - Math.max(current.minX, box.minX);
                                final double overlapY = Math.min(current.maxY, box.maxY) - Math.max(current.minY, box.minY);
                                final double overlapZ = Math.min(current.maxZ, box.maxZ) - Math.max(current.minZ, box.minZ);
                                if (overlapX < MIN_OVERLAP || overlapY < MIN_OVERLAP || overlapZ < MIN_OVERLAP) {
                                        continue;
                                }

                                // Use the distance to the exterior face. Intersection width is
                                // not that distance when a player is wholly inside a train box.
                                final double escapeX = horizontalSeparation(current.minX, current.maxX, box.minX, box.maxX);
                                final double escapeZ = horizontalSeparation(current.minZ, current.maxZ, box.minZ, box.maxZ);
                                final double escapeUp = box.maxY - current.minY;
                                if (Math.abs(escapeX) < depth) {
                                        axis = 0;
                                        direction = escapeX < 0 ? -1 : 1;
                                        depth = Math.abs(escapeX);
                                        hit = box;
                                }
                                if (escapeUp < depth && (current.minY + current.maxY) >= (box.minY + box.maxY)) {
                                        axis = 1;
                                        direction = 1;
                                        depth = escapeUp;
                                        hit = box;
                                }
                                if (Math.abs(escapeZ) < depth) {
                                        axis = 2;
                                        direction = escapeZ < 0 ? -1 : 1;
                                        depth = Math.abs(escapeZ);
                                        hit = box;
                                }
                        }

                        if (landing != null) {
                                // Snap onto the highest landing surface among the candidates.
                                // The step is bounded by how deep this single move fell into
                                // the box (the feet were above the surface before it), so it can
                                // never read as a teleport.
                                final double step = landing.maxY - current.minY;
                                moveY += step;
                                current = current.move(0, step, 0);
                                landed = true;
                                continue;
                        }

                        if (hit == null || remainingCorrection <= MIN_OVERLAP) {
                                break;
                        }
                        depth = Math.min(depth, remainingCorrection);
                        remainingCorrection -= depth;

                        switch (axis) {
                                case 0 -> {
                                        moveX += direction * depth;
                                        current = current.move(direction * depth, 0, 0);
                                }
                                case 1 -> {
                                        moveY += direction * depth;
                                        current = current.move(0, direction * depth, 0);
                                }
                                default -> {
                                        moveZ += direction * depth;
                                        current = current.move(0, 0, direction * depth);
                                }
                        }
                }

                if (moveX != 0 || moveY != 0 || moveZ != 0) {
                        entity.setPos(entity.getX() + moveX, entity.getY() + moveY, entity.getZ() + moveZ);
                }
                if (landed) {
                        // Cancel the fall and report ground contact. Without this the fall
                        // velocity kept accumulating while the player stood on the roof, until
                        // the feet sank past the box centre and the sideways eviction took
                        // over. With the velocity zeroed and onGround flagged, jumping,
                        // walking friction, step-up and fall-distance all behave on the roof.
                        final Vec3 velocity = entity.getDeltaMovement();
                        if (velocity.y < 0) {
                                entity.setDeltaMovement(velocity.x, 0, velocity.z);
                        }
                        entity.fallDistance = 0;
                        entity.setOnGround(true);
                }
        }

        /** Signed shortest translation out of an overlapping one-dimensional interval. */
        static double horizontalSeparation(double min, double max, double obstacleMin, double obstacleMax) {
                final double negative = obstacleMin - max;
                final double positive = obstacleMax - min;
                return -negative < positive ? negative : positive;
        }
}
