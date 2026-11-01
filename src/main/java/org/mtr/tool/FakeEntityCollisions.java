package org.mtr.tool;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The lithium-proof half of the fake-entity collision system (为AI乘客和列车提供锂兼容的碰撞回退).
 *
 * <p><b>Why this exists.</b> The primary funnel — the {@code EntityGetter#getEntityCollisions}
 * mixin ({@code LevelEntityCollisionsMixin}) — appends the passenger and train-car boxes to the
 * entity movement-collision list, which makes them perfectly solid on a vanilla installation.
 * <b>Lithium</b>, however, rewrites {@code Entity#collide} from the inside: its
 * {@code entity.collisions.movement} module wraps the inner
 * {@code Level#getEntityCollisions} call site with a "postponed entity collection" that returns an
 * empty list immediately and resolves the sweep in its own static
 * {@code LithiumEntityCollisions#collideMovement} — the patched default-method body is never
 * entered, so on lithium installs the funnel is silently a no-op: no errors, no log lines, and
 * players walk straight through crowds and standing trains (exactly the reported symptom).</p>
 *
 * <p><b>The fix.</b> This fallback hooks {@code Entity#move} <em>after the fact</em>
 * ({@code EntityMoveMixin}, injected at the method tail — a method lithium never touches: it
 * rewires the internals of {@code collide}, but the public {@code move} that calls it runs
 * untouched). Whenever the mover is a client-side player whose post-movement bounding box
 * overlaps one of the registered fake-entity boxes, the player is pushed back out along the axis
 * of minimum penetration — the classic depenetration used by every "solid fake entity"
 * implementation. On lithium the funnel contributes nothing and this fallback does all the
 * work; without lithium the funnel blocks the movement before any overlap can form and this
 * fallback sees nothing to fix. The two mechanisms compose without conflict.</p>
 *
 * <p><b>Why the push is bounded and directional.</b> The correction is capped at
 * {@link #MAX_CORRECTION} per move so a pathological overlap can never teleport the player
 * across the world, and the down direction is excluded: pushing the player <em>down</em> would
 * only shove them into the track underneath a train's hull instead of out of it, so a below-box
 * overlap resolves sideways (or, for a platform-level player whose feet clip a car roof,
 * upward onto the roof — standing on a train is legal).</p>
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
         * would read as a teleport.
         */
        private static final double MAX_CORRECTION = 2.0;

        /** Overlaps smaller than this are ignored — they cannot be resolved reliably anyway. */
        private static final double MIN_OVERLAP = 0.001;

        private FakeEntityCollisions() {
        }

        /**
         * Called from {@code EntityMoveMixin} at the tail of {@code Entity#move}: pushes a
         * client-side player out of any registered AI-passenger or train-car collision box that
         * the finished movement left them overlapping.
         *
         * @param entity the entity that just moved
         */
        public static void depenetrate(Entity entity) {
                if (!(entity instanceof Player) || entity.isSpectator()) {
                        return;
                }

                final Level level = entity.level();
                if (!level.isClientSide()) {
                        return;
                }

                final AABB boundingBox = entity.getBoundingBox();
                final List<AABB> boxes = PassengerCollisions.collectBoxes(level, boundingBox);
                boxes.addAll(VehicleCollisions.collectBoxes(level, boundingBox));
                if (boxes.isEmpty()) {
                        return;
                }

                double moveX = 0;
                double moveY = 0;
                double moveZ = 0;
                AABB current = boundingBox;

                // Up to three eviction passes — resolving one overlap can slide the player into
                // a neighbouring box (a crowd, a two-track train shed), and a fourth would mean
                // the geometry is genuinely enclosing the player.
                for (int pass = 0; pass < 3; pass++) {
                        AABB hit = null;
                        int axis = -1;
                        int direction = 0;
                        double depth = MAX_CORRECTION;

                        for (final AABB box : boxes) {
                                if (!current.intersects(box)) {
                                        continue;
                                }
                                final double overlapX = Math.min(current.maxX, box.maxX) - Math.max(current.minX, box.minX);
                                final double overlapY = Math.min(current.maxY, box.maxY) - Math.max(current.minY, box.minY);
                                final double overlapZ = Math.min(current.maxZ, box.maxZ) - Math.max(current.minZ, box.minZ);
                                if (overlapX < MIN_OVERLAP || overlapY < MIN_OVERLAP || overlapZ < MIN_OVERLAP) {
                                        continue;
                                }

                                // Push out along the shallowest axis, toward whichever side of the
                                // box the player's centre already favours. The down direction is
                                // excluded: shoving the player into the ground under a train is
                                // never the intent — such overlaps resolve sideways instead.
                                if (overlapX < depth) {
                                        final boolean left = (current.minX + current.maxX) < (box.minX + box.maxX);
                                        axis = 0;
                                        direction = left ? -1 : 1;
                                        depth = overlapX;
                                        hit = box;
                                }
                                if (overlapY < depth && (current.minY + current.maxY) >= (box.minY + box.maxY)) {
                                        axis = 1;
                                        direction = 1;
                                        depth = overlapY;
                                        hit = box;
                                }
                                if (overlapZ < depth) {
                                        final boolean towardNegativeZ = (current.minZ + current.maxZ) < (box.minZ + box.maxZ);
                                        axis = 2;
                                        direction = towardNegativeZ ? -1 : 1;
                                        depth = overlapZ;
                                        hit = box;
                                }
                        }

                        if (hit == null) {
                                break;
                        }

                        switch (axis) {
                                case 0 -> {
                                        moveX += direction * depth;
                                        current = new AABB(current.minX + direction * depth, current.minY, current.minZ, current.maxX + direction * depth, current.maxY, current.maxZ);
                                }
                                case 1 -> {
                                        moveY += direction * depth;
                                        current = new AABB(current.minX, current.minY + direction * depth, current.minZ, current.maxX, current.maxY + direction * depth, current.maxZ);
                                }
                                default -> {
                                        moveZ += direction * depth;
                                        current = new AABB(current.minX, current.minY, current.minZ + direction * depth, current.maxX, current.maxY, current.maxZ + direction * depth);
                                }
                        }
                }

                if (moveX != 0 || moveY != 0 || moveZ != 0) {
                        entity.setPos(entity.getX() + moveX, entity.getY() + moveY, entity.getZ() + moveZ);
                }
        }
}
