package org.mtr.tool;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side collision registry for train cars (为列车添加完整碰撞箱).
 *
 * <p>MTR vehicles are simulated analytically — no real entities — so a train is completely
 * intangible: players stand on the track and get passed through. This registry closes that
 * gap. Every frame {@link org.mtr.render.RenderVehicles} publishes one world-space
 * {@link AABB} per rendered car (built from the car's floor/doorway hull, open doorways
 * carved out so boarding still works, rotated into world space with the car's full
 * yaw/pitch/roll), and the {@code EntityGetter#getEntityCollisions} mixin — the same funnel
 * wired up for {@link PassengerCollisions} — appends the intersecting boxes to the local
 * player's movement-collision queries. The train body is therefore solid: a player can no
 * longer stand inside a passing train.</p>
 *
 * <p><b>Knockback (撞飞)</b> — a solid box alone only blocks the <em>player's own</em>
 * movement; a train sweeping into a stationary player would still tunnel through them.
 * {@link #applyKnockback} therefore runs once per rendered frame: when a car box moving
 * faster than {@link #KNOCKBACK_MIN_SPEED} starts overlapping the local player, the player
 * receives a velocity impulse along the car's travel direction (plus an upward component) —
 * the train visibly <em>hits</em> and sends the player flying. A per-vehicle cooldown stops
 * the impulse from re-firing every frame while the overlap persists.</p>
 *
 * <p><b>Boarding safety</b> — the boxes of the vehicle the player is currently riding are
 * never registered (the rider walks freely inside the car, exactly as before), and cars
 * standing at a platform with open doors register the hull <em>minus</em> the open doorway
 * corridors, so stepping through an open door to board is never blocked. The knockback check
 * is skipped entirely while the player rides any vehicle.</p>
 *
 * <p><b>Server safety</b> — exactly like {@link PassengerCollisions}: this class lives in
 * the shared {@code tool} package and imports only common (non-client) Minecraft classes,
 * so the dedicated-server mixin path loads it as an empty registry. The {@code collect}
 * fast-path (empty snapshot, non-{@link Player} movers, non-client levels) keeps every
 * other query untouched.</p>
 *
 * <p><b>Threading</b> — updates arrive on the client render thread; readers (the integrated
 * server's movement-collision queries) go through an immutable snapshot published via a
 * volatile field. Per-car velocity is derived from consecutive frame positions, so it stays
 * correct regardless of the simulation's internal speed units.</p>
 */
public final class VehicleCollisions {

        /**
         * Boxes older than this are pruned — a car that stops being iterated (vehicle
         * despawned, dimension change) must not leave a permanent invisible wall behind.
         */
        private static final long STALE_MILLIS = 500;

        /** Upper bound on simultaneously registered car boxes (bounded by the car count in range). */
        private static final int LIMIT = 256;

        /** A car must be moving at least this fast (blocks/second) before it can knock a player flying. */
        private static final double KNOCKBACK_MIN_SPEED = 1.5;

        /** Minimum milliseconds between two knockback impulses from the same vehicle. */
        private static final long KNOCKBACK_COOLDOWN_MILLIS = 800;

        private static final double KNOCKBACK_BASE = 0.5;
        private static final double KNOCKBACK_PER_SPEED = 0.12;
        private static final double KNOCKBACK_MAX = 1.7;
        private static final double KNOCKBACK_UP = 0.65;

        private static final Long2ObjectLinkedOpenHashMap<Entry> ENTRIES = new Long2ObjectLinkedOpenHashMap<>();
        private static final Long2LongLinkedOpenHashMap KNOCKBACK_COOLDOWNS = new Long2LongLinkedOpenHashMap<>();
        private static volatile List<Shape> SNAPSHOT = List.of();

        private VehicleCollisions() {
        }

        /**
         * Publishes (or refreshes) the collision boxes of one car. Called once per frame from
         * the client render thread for every car whose vehicle the local player is not riding;
         * a car standing at a platform with open doors registers several boxes (the hull split
         * around the carved doorway corridors). The per-car velocity used for the knockback
         * impulse is derived from the movement of the union box centre between consecutive
         * frames — one entry per car, never per segment, so segment splits never contaminate
         * the velocity.
         *
         * @param vehicleId the vehicle (train) the car belongs to (drives the knockback cooldown key)
         * @param carNumber the car index within the vehicle (keys the registry entry)
         * @param dimension the dimension the car currently renders in
         * @param boxes     the world-space collision boxes of the car (one for a closed car,
         *                  several when open doorway corridors are carved out)
         * @param floorY    the world-space Y of the car's floor surface — the knockback only
         *                  fires for players standing clearly below it (on the track), never for
         *                  players standing on a platform (whose surface is level with the floor)
         */
        public static void updateCar(long vehicleId, int carNumber, ResourceKey<Level> dimension, List<AABB> boxes, double floorY) {
                if (boxes.isEmpty()) {
                        return;
                }

                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double minZ = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;
                double maxZ = Double.NEGATIVE_INFINITY;
                for (final AABB box : boxes) {
                        minX = Math.min(minX, box.minX);
                        minY = Math.min(minY, box.minY);
                        minZ = Math.min(minZ, box.minZ);
                        maxX = Math.max(maxX, box.maxX);
                        maxY = Math.max(maxY, box.maxY);
                        maxZ = Math.max(maxZ, box.maxZ);
                }
                final double centerX = (minX + maxX) / 2;
                final double centerY = (minY + maxY) / 2;
                final double centerZ = (minZ + maxZ) / 2;

                final long key = key(vehicleId, carNumber);
                final Entry previous = ENTRIES.get(key);
                final long now = System.currentTimeMillis();

                final Entry entry;
                if (previous == null) {
                        if (ENTRIES.size() >= LIMIT) {
                                return;
                        }
                        entry = new Entry(vehicleId, List.copyOf(boxes), dimension, floorY, 0, 0, 0, now);
                } else {
                        final double deltaTime = (now - previous.time) / 1000.0;
                        double velocityX = previous.velocityX;
                        double velocityY = previous.velocityY;
                        double velocityZ = previous.velocityZ;
                        if (deltaTime > 0.02 && deltaTime < 0.5) {
                                // Smoothed (0.5 blend) so a single lumpy frame never spikes the impulse.
                                velocityX = previous.velocityX * 0.5 + (centerX - previous.centerX) / deltaTime * 0.5;
                                velocityY = previous.velocityY * 0.5 + (centerY - previous.centerY) / deltaTime * 0.5;
                                velocityZ = previous.velocityZ * 0.5 + (centerZ - previous.centerZ) / deltaTime * 0.5;
                        }
                        entry = new Entry(vehicleId, List.copyOf(boxes), dimension, floorY, velocityX, velocityY, velocityZ, now);
                }

                ENTRIES.putAndMoveToFirst(key, entry);
                while (ENTRIES.size() > LIMIT) {
                        ENTRIES.removeLast();
                }
        }

        /**
         * Ends the frame: prunes stale boxes and publishes a fresh immutable snapshot. Called
         * once after the per-frame car registration pass.
         */
        public static void endFrame() {
                final long now = System.currentTimeMillis();
                boolean dirty = false;
                final var iterator = ENTRIES.long2ObjectEntrySet().iterator();
                while (iterator.hasNext()) {
                        final var entry = iterator.next();
                        if (now - entry.getValue().time > STALE_MILLIS) {
                                iterator.remove();
                                dirty = true;
                        }
                }
                publish(dirty);
        }

        /**
         * Drops every box immediately (dimension leave, disconnect).
         */
        public static void clear() {
                ENTRIES.clear();
                KNOCKBACK_COOLDOWNS.clear();
                publish(true);
        }

        /**
         * Collects the train collision shapes intersecting a movement-collision query.
         *
         * @param levelObject the level the query runs in (the {@code EntityGetter} instance —
         *                    always a {@code Level} in vanilla, pattern-matched defensively)
         * @param entity      the moving entity the query is for ({@code null} for entity-agnostic queries)
         * @param queryBox    the swept movement box to test against
         * @return the intersecting car shapes; empty when there is nothing to add (the common
         *         case on servers and for non-player movers)
         */
        public static List<VoxelShape> collect(Object levelObject, @Nullable Entity entity, AABB queryBox) {
                final List<Shape> snapshot = SNAPSHOT;
                if (snapshot.isEmpty() || entity == null || !(entity instanceof Player) || !(levelObject instanceof Level level) || !level.isClientSide()) {
                        return List.of();
                }

                final ResourceKey<Level> dimension = level.dimension();
                List<VoxelShape> result = null;
                for (final Shape shape : snapshot) {
                        if (shape.dimension == dimension && shape.box.intersects(queryBox)) {
                                if (result == null) {
                                        result = new ArrayList<>();
                                }
                                result.add(Shapes.create(shape.box));
                        }
                }
                return result == null ? List.of() : result;
        }

        /**
         * Applies the "knocked flying by a passing train" impulse to the local player. Runs once
         * per rendered frame (render thread) — never while the player is riding a vehicle (the
         * riding system owns the player then) and never for spectators.
         *
         * @param level  the client level (used only for the dimension filter)
         * @param player the local player
         */
        public static void applyKnockback(Level level, Player player) {
                final List<Shape> snapshot = SNAPSHOT;
                if (snapshot.isEmpty() || !level.isClientSide()) {
                        return;
                }

                final ResourceKey<Level> dimension = level.dimension();
                final AABB playerBox = player.getBoundingBox();
                final long now = System.currentTimeMillis();

                for (final Shape shape : snapshot) {
                        if (shape.dimension != dimension || !shape.box.intersects(playerBox)) {
                                continue;
                        }
                        // Platform guard: a player standing ON a platform (floor level or above) is never
                        // sent flying — their surface is level with the car floor, so only track-level
                        // standers (feet clearly below the floor) count as "in front of the train".
                        if (player.getY() > shape.floorY - 0.3) {
                                continue;
                        }
                        final double speed = Math.sqrt(shape.velocityX * shape.velocityX + shape.velocityZ * shape.velocityZ);
                        if (speed < KNOCKBACK_MIN_SPEED) {
                                continue;
                        }

                        final long lastKnockback = KNOCKBACK_COOLDOWNS.get(shape.vehicleId);
                        if (now - lastKnockback < KNOCKBACK_COOLDOWN_MILLIS) {
                                continue;
                        }
                        KNOCKBACK_COOLDOWNS.putAndMoveToFirst(shape.vehicleId, now);
                        while (KNOCKBACK_COOLDOWNS.size() > LIMIT) {
                                KNOCKBACK_COOLDOWNS.removeLast();
                        }

                        // The impulse pushes the player along the train's travel direction (the front
                        // of the train clips them) plus a fixed upward component — the "hit and sent
                        // flying" read. Clamped so even a 30 m/s express only shoves so hard.
                        final double horizontal = Math.min(KNOCKBACK_BASE + speed * KNOCKBACK_PER_SPEED, KNOCKBACK_MAX);
                        player.push(shape.velocityX / speed * horizontal, KNOCKBACK_UP, shape.velocityZ / speed * horizontal);
                        return; // one impulse per frame is plenty
                }
        }

        private static void publish(boolean dirty) {
                if (dirty || SNAPSHOT.isEmpty()) {
                        List<Shape> snapshot = null;
                        for (final Entry entry : ENTRIES.values()) {
                                for (final AABB box : entry.boxes) {
                                        if (snapshot == null) {
                                                snapshot = new ArrayList<>(ENTRIES.size());
                                        }
                                        snapshot.add(new Shape(box, entry.dimension, entry.floorY, entry.velocityX, entry.velocityY, entry.velocityZ, entry.vehicleId));
                                }
                        }
                        SNAPSHOT = snapshot == null ? List.of() : List.copyOf(snapshot);
                }
        }

        private static long key(long vehicleId, int carNumber) {
                return vehicleId << 16 | carNumber & 0xFFFF;
        }

        private static final class Entry {
                private final List<AABB> boxes;
                private final ResourceKey<Level> dimension;
                private final double floorY;
                private final double velocityX;
                private final double velocityY;
                private final double velocityZ;
                private final long vehicleId;
                private final double centerX;
                private final double centerY;
                private final double centerZ;
                private final long time;

                private Entry(long vehicleId, List<AABB> boxes, ResourceKey<Level> dimension, double floorY, double velocityX, double velocityY, double velocityZ, long time) {
                        this.boxes = boxes;
                        this.dimension = dimension;
                        this.floorY = floorY;
                        this.velocityX = velocityX;
                        this.velocityY = velocityY;
                        this.velocityZ = velocityZ;
                        this.vehicleId = vehicleId;
                        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
                        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                        for (final AABB box : boxes) {
                                minX = Math.min(minX, box.minX);
                                minY = Math.min(minY, box.minY);
                                minZ = Math.min(minZ, box.minZ);
                                maxX = Math.max(maxX, box.maxX);
                                maxY = Math.max(maxY, box.maxY);
                                maxZ = Math.max(maxZ, box.maxZ);
                        }
                        this.centerX = (minX + maxX) / 2;
                        this.centerY = (minY + maxY) / 2;
                        this.centerZ = (minZ + maxZ) / 2;
                        this.time = time;
                }
        }

        private record Shape(AABB box, ResourceKey<Level> dimension, double floorY, double velocityX, double velocityY, double velocityZ, long vehicleId) {
        }
}
