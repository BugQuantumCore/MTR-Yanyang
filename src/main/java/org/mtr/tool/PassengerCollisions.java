package org.mtr.tool;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side collision registry for AI passengers.
 *
 * <p>AI passengers are purely rendered (analytical positions, no real entities), so by default
 * players walk straight through them. This registry closes that gap: every frame
 * {@link org.mtr.render.RenderPassengers} publishes the rendered position of each on-foot
 * passenger, and the {@code EntityGetter#getEntityCollisions} default method (the real
 * movement-collision funnel — {@code Level} inherits it through {@code CommonLevelAccessor}'s
 * delegating default; wired up by {@code LevelEntityCollisionsMixin}) appends a solid,
 * player-sized box ({@code 0.6 x 1.8}) for each nearby passenger — the same mechanism that
 * makes boats and shulkers impassable. Passengers on vehicles deliberately do not register
 * (their boxes would sweep through platforms at train speed).</p>
 *
 * <p><b>Server safety</b> — this class lives in the shared {@code tool} package and imports only
 * common (non-client) Minecraft classes, so a dedicated server can load it safely; nothing ever
 * populates it there and the empty fast-path makes the injected collision query a no-op.
 * On the integrated server the dimension and {@code instanceof Player} + {@code isClientSide}
 * filters keep mob pathfinding and cross-dimension queries away from the client-only boxes:
 * only the local player's own movement collision sees them.</p>
 *
 * <p><b>Threading</b> — updates arrive on the client main thread (render pass); the integrated
 * server thread reads through an immutable snapshot published via a volatile field, so readers
 * always see a consistent list without locks.</p>
 */
public final class PassengerCollisions {

        /**
         * Boxes older than this are pruned — a passenger that stops being rendered (unloaded
         * platform, dimension change, cull) must not leave a permanent invisible wall behind.
         */
        private static final long STALE_MILLIS = 500;

        /** Upper bound on simultaneously registered boxes; the nearest crowds stay far below this. */
        private static final int LIMIT = 512;

        /** Player collision dimensions: 0.6 wide, 1.8 tall (half-width 0.3). */
        private static final double HALF_WIDTH = 0.3;
        private static final double HEIGHT = 1.8;

        private static final Long2ObjectLinkedOpenHashMap<Entry> ENTRIES = new Long2ObjectLinkedOpenHashMap<>();
        private static volatile List<Shape> SNAPSHOT = List.of();
        private static boolean snapshotDirty;

        private PassengerCollisions() {
        }

        /**
         * Publishes (or refreshes) the collision box for one passenger. Called per frame from the
         * client render thread for every on-foot passenger within collision range.
         *
         * @param passengerId the passenger id (also the box key)
         * @param dimension   the dimension the passenger currently renders in
         * @param x           rendered feet position X
         * @param y           rendered feet position Y (bottom of the box)
         * @param z           rendered feet position Z
         */
        public static void update(long passengerId, ResourceKey<Level> dimension, double x, double y, double z) {
                final long now = System.nanoTime();
                final Entry previous = ENTRIES.getAndMoveToFirst(passengerId);
                if (previous != null && previous.shape.dimension == dimension &&
                                previous.shape.box.minX == x - HALF_WIDTH && previous.shape.box.minY == y && previous.shape.box.minZ == z - HALF_WIDTH) {
                        previous.time = now;
                        return;
                }
                if (previous == null && ENTRIES.size() >= LIMIT) {
                        return;
                }
                ENTRIES.putAndMoveToFirst(passengerId, new Entry(new Shape(
                                new AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH), dimension), now));
                snapshotDirty = true;
        }

        /**
         * Ends the frame: prunes stale boxes and publishes a fresh immutable snapshot. Called once
         * after the per-frame passenger update pass.
         */
        public static void endFrame() {
                final long now = System.nanoTime();
                final var iterator = ENTRIES.long2ObjectEntrySet().iterator();
                while (iterator.hasNext()) {
                        final var entry = iterator.next();
                        if (now - entry.getValue().time > STALE_MILLIS * 1_000_000) {
                                iterator.remove();
                                snapshotDirty = true;
                        }
                }
                publish();
        }

        /**
         * Drops every box immediately (dimension leave, disconnect).
         */
        public static void clear() {
                ENTRIES.clear();
                SNAPSHOT = List.of();
                snapshotDirty = false;
        }

        /**
         * Collects the raw world-space passenger boxes intersecting a query box — the
         * depenetration fallback's view of the registry (see {@link FakeEntityCollisions}).
         * Same dimension filter as {@link #collect}; no entity filter, the caller does that once.
         *
         * @param level    the level whose dimension the boxes must belong to
         * @param queryBox the box to test intersection against
         * @return the intersecting passenger boxes; empty when the registry holds nothing nearby
         */
        public static List<AABB> collectBoxes(Level level, AABB queryBox) {
                return collectBoxes(level.dimension(), queryBox);
        }

        static List<AABB> collectBoxes(ResourceKey<Level> dimension, AABB queryBox) {
                final List<Shape> snapshot = SNAPSHOT;
                final List<AABB> result = new ArrayList<>();
                for (final Shape shape : snapshot) {
                        if (shape.dimension == dimension && shape.box.intersects(queryBox)) {
                                result.add(shape.box);
                        }
                }
                return result;
        }

        /**
         * Collects the passenger collision shapes intersecting a movement-collision query.
         *
         * @param levelObject the level the query runs in (the {@code EntityGetter} instance —
         *                    always a {@code Level} in vanilla, pattern-matched defensively so a
         *                    foreign implementer can never crash the injected handler)
         * @param entity      the moving entity the query is for ({@code null} for entity-agnostic queries)
         * @param queryBox    the swept movement box to test against
         * @return the intersecting passenger shapes; empty when there is nothing to add (the common
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
                                result.add(shape.voxelShape());
                        }
                }
                return result == null ? List.of() : result;
        }

        private static void publish() {
                if (snapshotDirty) {
                        final List<Shape> snapshot = new ArrayList<>(ENTRIES.size());
                        for (final Entry entry : ENTRIES.values()) {
                                snapshot.add(entry.shape);
                        }
                        SNAPSHOT = List.copyOf(snapshot);
                        snapshotDirty = false;
                }
        }

        private static final class Entry {
                private final Shape shape;
                private long time;

                private Entry(Shape shape, long time) {
                        this.shape = shape;
                        this.time = time;
                }
        }

        private static final class Shape {
                private final AABB box;
                private final ResourceKey<Level> dimension;
                @Nullable
                private VoxelShape voxelShape;

                private Shape(AABB box, ResourceKey<Level> dimension) {
                        this.box = box;
                        this.dimension = dimension;
                }

                // Only client movement queries reach this method; create shapes on demand,
                // and reuse them until the corresponding passenger actually moves.
                private VoxelShape voxelShape() {
                        if (voxelShape == null) {
                                voxelShape = Shapes.create(box);
                        }
                        return voxelShape;
                }
        }
}
