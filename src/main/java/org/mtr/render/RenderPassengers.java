package org.mtr.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.mtr.client.MinecraftClientData;
import org.mtr.core.data.*;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.tool.CullingHelper;
import org.mtr.tool.Drawing;
import org.mtr.tool.PassengerCollisions;

/**
 * Renders AI passengers that are not currently on a vehicle.
 *
 * <p>Rendering is split by the current leg type:</p>
 * <ul>
 *   <li><b>No legs</b> — the passenger is idle at their home or at a landmark; a deterministic
 *       wander around the area is rendered.</li>
 *   <li><b>Walking leg</b> ({@code routeId == 0}) — the passenger interpolates between the leg
 *       start and end positions. The start falls back to the passenger's home when no start
 *       platform is set (first leg); the end falls back to the destination landmark or home
 *       when no end platform is set (last leg).</li>
 *   <li><b>Vehicle leg</b> ({@code routeId != 0}) while not yet boarded — the passenger stands
 *       at a deterministic position on the boarding platform, instead of visually walking the
 *       whole inter-station leg.</li>
 * </ul>
 *
 * <p>Each rendered position also carries the current walking speed in blocks per second, which
 * drives the leg swing animation in {@link PassengerModelRenderer}; standing passengers (speed
 * 0) can take the phone pose.</p>
 *
 * <p>yanyang.13 additions: walking legs follow the actual standable ground instead of a straight
 * 3D lerp (which walked through terrain), the rendered position is smoothed per passenger
 * (anti-teleport blends, eased Y steps), and every on-foot passenger feeds a solid player-sized
 * collision box into {@link PassengerCollisions} so the local player can no longer walk through
 * the crowd.</p>
 */
public class RenderPassengers {

	private static final int MIN_MOVE_TIME = 10 * Utilities.MILLIS_PER_SECOND;
	private static final int MAX_MOVE_TIME = 40 * Utilities.MILLIS_PER_SECOND;

	private static final float MIN_WALKING_SPEED = 1F / Utilities.MILLIS_PER_SECOND; // 1 m/s
	private static final float MAX_WALKING_SPEED = 4F / Utilities.MILLIS_PER_SECOND; // 4 m/s

	/**
	 * Time (ms) an idle passenger takes to turn from their arrival heading to the heading of
	 * the next wander segment, after arriving at a wander destination.
	 */
	private static final double TURNING_TIME = 1000;

	private static final int RESOLUTION = 1000000;
	private static final long GOLDEN_RATIO_FRACTION = 0x9E3779B97F4A7C15L;

	/**
	 * Ground-height scan results, cached per block column and scan
	 * Y-range for {@link #GROUND_SCAN_CACHE_MILLIS} milliseconds. Previously every idle or
	 * walking passenger re-scanned up to 3 block states per candidate Y level EVERY frame
	 * (a platform with 50 passengers was thousands of block lookups per frame); the result
	 * is a pure function of the column and the scan range, so all passengers on the same
	 * platform share entries.
	 */
	private static final int GROUND_SCAN_CACHE_MILLIS = 500;
	private static final int GROUND_SCAN_CACHE_LIMIT = 4096;
	private static final Object2ObjectLinkedOpenHashMap<GroundColumn, CachedGroundY> GROUND_SCAN_CACHE = new Object2ObjectLinkedOpenHashMap<>();

	/**
	 * Rendered-position smoothing (anti-teleport). The analytical positions are already
	 * continuous across leg transitions (waiting, walking and arrival points on the same
	 * platform all come from the same deterministic function of the platform and passenger id),
	 * but re-planned legs, degenerate-geometry fallbacks and first sightings can still jump a
	 * visible distance. Small discontinuities blend over {@link #BLEND_MILLIS}; genuinely large
	 * jumps (a re-plan across the map) snap instead of superman-sliding.
	 */
	private static final int POSITION_CACHE_LIMIT = 4096;
	private static final double BLEND_TRIGGER_DISTANCE = 0.75;
	private static final double SNAP_DISTANCE = 3;
	private static final long BLEND_MILLIS = 250;
	private static final float Y_SMOOTHING_PER_SECOND = 10F;
	private static final Long2ObjectLinkedOpenHashMap<SmoothedPosition> SMOOTHED_POSITIONS = new Long2ObjectLinkedOpenHashMap<>();

	/** Only passengers within this distance of the camera feed the collision registry. */
	private static final double COLLISION_RANGE = 64;

	@org.jspecify.annotations.Nullable
	private static ClientLevel cachedCollisionLevel;

	public static void render() {
		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		final ObjectArrayList<Passenger> passengers = minecraftClientData.vehicleIdToPassengers.get(0);
		final ClientLevel clientWorld = Minecraft.getInstance().level;

		// Dropping the collision boxes of a previous world/session: boxes are dimension-keyed
		// but a same-dimension rejoin would otherwise resurrect the old crowd's walls.
		if (clientWorld != cachedCollisionLevel) {
			clear();
			cachedCollisionLevel = clientWorld;
		}

		if (passengers != null) {
			passengers.forEach(passenger -> {
				final ObjectArrayList<PassengerDirection> directions = passenger.getDirections();
				final long passengerId = passenger.getId();

				if (directions.isEmpty()) {
					final SimpleAreaBase area;
					if (passenger.getEndLandmarkId() == 0) {
						area = minecraftClientData.homeIdMap.get(passenger.getHomeId());
					} else {
						area = minecraftClientData.landmarkIdMap.get(passenger.getEndLandmarkId());
					}

					if (area != null) {
						render(passenger, getIdlePosition(area, passengerId));
					}
				} else if (directions.getFirst().getRouteId() == 0) {
					// Walking leg — interpolate between the leg start and end positions
					final PassengerDirection direction = directions.getFirst();
					final Platform platform1 = minecraftClientData.platformIdMap.get(direction.getStartPlatformId());
					final Platform platform2 = minecraftClientData.platformIdMap.get(direction.getEndPlatformId());

					final PositionAndYaw positionAndYaw1;
					if (platform1 == null) {
						final Home home = minecraftClientData.homeIdMap.get(passenger.getHomeId());
						positionAndYaw1 = home == null ? null : getIdlePosition(home, passengerId);
					} else {
						positionAndYaw1 = getIdlePosition(platform1, passengerId);
					}

					final PositionAndYaw positionAndYaw2;
					if (platform2 == null) {
						if (passenger.getEndLandmarkId() == 0) {
							// Final walking leg home — fall back to the passenger's home
							final Home home = minecraftClientData.homeIdMap.get(passenger.getHomeId());
							positionAndYaw2 = home == null ? null : getIdlePosition(home, passengerId);
						} else {
							final Landmark landmark = minecraftClientData.landmarkIdMap.get(passenger.getEndLandmarkId());
							positionAndYaw2 = landmark == null ? null : getIdlePosition(landmark, passengerId);
						}
					} else {
						positionAndYaw2 = getIdlePosition(platform2, passengerId);
					}

					if (positionAndYaw1 != null && positionAndYaw2 != null) {
						// Clamp to zero so a client clock behind the server doesn't extrapolate the
						// passenger behind the leg start position.
						final double progress = Math.max(0, System.currentTimeMillis() - direction.getStartTime());

						final double differenceX = positionAndYaw2.x - positionAndYaw1.x;
						final double differenceY = positionAndYaw2.y - positionAndYaw1.y;
						final double differenceZ = positionAndYaw2.z - positionAndYaw1.z;
						final double distance = Math.sqrt(differenceX * differenceX + differenceY * differenceY + differenceZ * differenceZ);

						final long duration = direction.getEndTime() - direction.getStartTime();
						final double requiredSpeed = duration <= 0 ? distance == 0 ? 0 : Double.POSITIVE_INFINITY : distance / duration;
						final double walkingSpeed = Math.max(getRandomValue(passengerId, MIN_WALKING_SPEED, MAX_WALKING_SPEED), requiredSpeed);
						final double walkingTime = distance / walkingSpeed;
						final double walkingTimeChange = walkingTime <= 0 ? 1 : Math.min(1, progress / walkingTime);
						final double x = positionAndYaw1.x + walkingTimeChange * differenceX;
						final double z = positionAndYaw1.z + walkingTimeChange * differenceZ;
						final double linearY = positionAndYaw1.y + walkingTimeChange * differenceY;
						// Ground-following Y: a straight 3D lerp between the two platform points walks
						// passengers through hills, fences and station walls. Instead, sample the actual
						// standable surface at the current (x, z) through the shared ground-scan cache
						// (scanning a window around the two platform heights) and keep the result within
						// 2 blocks of the lerp so a deep dip or bridge doesn't drop or float the passenger
						// out of the walkable band. Residual half-block steps are eased by the per-
						// passenger Y smoothing.
						final double y;
						if (clientWorld != null && walkingTimeChange < 1) {
							final double sampledY = findGroundY(clientWorld, x, z,
									(long) Math.floor(Math.min(positionAndYaw1.y, positionAndYaw2.y)) - 1,
									(long) Math.floor(Math.max(positionAndYaw1.y, positionAndYaw2.y)) + 1);
							y = Math.max(linearY - 2, Math.min(linearY + 2, sampledY));
						} else {
							y = linearY;
						}
						final double yaw = Math.atan2(differenceZ, differenceX);

						// Animation speed: the leg swing cycle runs while the walk is still in
						// progress; once arrived (waiting for the leg time to elapse) they stand.
						final double walkingSpeedBlocksPerSecond = walkingTimeChange < 1 ? walkingSpeed * Utilities.MILLIS_PER_SECOND : 0;
						render(passenger, new PositionAndYaw(x, y, z, yaw, walkingSpeedBlocksPerSecond));
					}
				} else {
					// Vehicle leg while not yet boarded — stand at the boarding platform
					final Platform platform1 = minecraftClientData.platformIdMap.get(directions.getFirst().getStartPlatformId());
					if (platform1 != null) {
						render(passenger, getIdlePosition(platform1, passengerId));
					}
				}
			});
		}

		// Publish the collision boxes gathered this frame (also prunes stale ones).
		PassengerCollisions.endFrame();
	}

	/** Releases client-world references even when disconnect stops all world rendering. */
	public static void clear() {
		cachedCollisionLevel = null;
		PassengerCollisions.clear();
		SMOOTHED_POSITIONS.clear();
		GROUND_SCAN_CACHE.clear();
		PassengerRenderCache.clear();
	}

	private static PositionAndYaw getIdlePosition(SimpleAreaBase area, long passengerId) {
		final ClientLevel clientWorld = Minecraft.getInstance().level;

		if (clientWorld == null) {
			return new PositionAndYaw(0, 0, 0, 0, 0);
		} else {
			final double walkingSpeed = getRandomValue(passengerId, MIN_WALKING_SPEED, MAX_WALKING_SPEED) / 2; // half walking speed when idling
			final double longestWalkingTime = (Math.abs(area.getMaxX() - area.getMinX()) + Math.abs(area.getMaxZ() - area.getMinZ())) / walkingSpeed;
			final long changePositionInterval = (long) Math.ceil(getRandomValue(passengerId, MIN_MOVE_TIME + longestWalkingTime, MAX_MOVE_TIME + longestWalkingTime));

			final long millis = System.currentTimeMillis();
			final long position2Snapshot = millis / changePositionInterval;
			final long position1Snapshot = position2Snapshot - 1;
			final long position3Snapshot = position2Snapshot + 1;
			final long progress = millis - position2Snapshot * changePositionInterval;

			final double x1 = getRandomValue(position1Snapshot * 3, area.getMinX(), area.getMaxX());
			final double x2 = getRandomValue(position2Snapshot * 3, area.getMinX(), area.getMaxX());
			final double x3 = getRandomValue(position3Snapshot * 3, area.getMinX(), area.getMaxX());
			final double differenceX = x2 - x1;

			final double z1 = getRandomValue(position1Snapshot * 5 + 1, area.getMinZ(), area.getMaxZ());
			final double z2 = getRandomValue(position2Snapshot * 5 + 1, area.getMinZ(), area.getMaxZ());
			final double z3 = getRandomValue(position3Snapshot * 5 + 1, area.getMinZ(), area.getMaxZ());
			final double differenceZ = z2 - z1;

			final double walkingTime = Math.sqrt(differenceX * differenceX + differenceZ * differenceZ) / walkingSpeed;
			final double walkingTimeChange = walkingTime == 0 ? 1 : Math.min(1, progress / walkingTime);
			final double x = x1 + walkingTimeChange * differenceX;
			final double z = z1 + walkingTimeChange * differenceZ;

			final double yaw1 = Math.atan2(differenceZ, differenceX);
			final double yaw2 = Math.atan2(z3 - z2, x3 - x2);
			// Interpolate the heading from the arrival heading to the next segment heading over
			// TURNING_TIME milliseconds after arriving; the factor is normalised to 0-1.
			final double turnProgress = Utilities.clampSafe((progress - walkingTime) / TURNING_TIME, 0, 1);
			final double yaw = yaw1 + turnProgress * Utilities.circularDifference(yaw2, yaw1, Math.PI * 2);

			final double animationSpeed = getWanderAnimationSpeed(walkingTime, progress, walkingSpeed);

			return new PositionAndYaw(x, findGroundY(clientWorld, x, z, area.getMinY(), area.getMaxY()), z, yaw, animationSpeed);
		}
	}

	/**
	 * Scans the candidate Y levels for the first stand-on-able block, with the shared
	 * time-bounded column cache. The heightmap fallback (no standable level found in the
	 * whole area Y-range) is rare and is cached with the same key so identical scans
	 * within the TTL short-circuit before re-scanning.
	 */
	private static long findGroundY(ClientLevel clientWorld, double x, double z, long minY, long maxY) {
		final int blockX = (int) Math.floor(x);
		final int blockZ = (int) Math.floor(z);
		// Block states are constant within a block column. Keep the complete coordinates
		// and range: the old 20-bit half-block packing aliased columns 524288 blocks apart.
		final GroundColumn cacheKey = new GroundColumn(blockX, blockZ, minY, maxY);
		final long now = System.nanoTime();

		final CachedGroundY cached = GROUND_SCAN_CACHE.getAndMoveToFirst(cacheKey);
		if (cached != null && now - cached.time() <= GROUND_SCAN_CACHE_MILLIS * 1_000_000L) {
			return cached.y();
		}

		final BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
		boolean belowAir = clientWorld.getBlockState(checkPos.set(blockX, (int) minY - 1, blockZ)).isAir();
		boolean feetAir = clientWorld.getBlockState(checkPos.set(blockX, (int) minY, blockZ)).isAir();
		for (long y = minY; y <= maxY; y++) {
			final boolean headAir = clientWorld.getBlockState(checkPos.set(blockX, (int) y + 1, blockZ)).isAir();
			if (!belowAir && feetAir && headAir) {
				cacheGroundY(cacheKey, y, now);
				return y;
			}
			belowAir = feetAir;
			feetAir = headAir;
		}

		final long fallbackY = clientWorld.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
		cacheGroundY(cacheKey, fallbackY, now);
		return fallbackY;
	}

	private static void cacheGroundY(GroundColumn cacheKey, long y, long now) {
		GROUND_SCAN_CACHE.putAndMoveToFirst(cacheKey, new CachedGroundY(y, now));
		while (GROUND_SCAN_CACHE.size() > GROUND_SCAN_CACHE_LIMIT) {
			GROUND_SCAN_CACHE.removeLast();
		}
	}

	private record GroundColumn(int x, int z, long minY, long maxY) {
	}

	private record CachedGroundY(long y, long time) {
	}

	private static PositionAndYaw getIdlePosition(Platform platform, long passengerId) {
		final ClientLevel clientWorld = Minecraft.getInstance().level;

		if (clientWorld == null) {
			return new PositionAndYaw(0, 0, 0, 0, 0);
		}

		final Rail rail = platform.rail;

		if (rail == null) {
			return new PositionAndYaw(0, 0, 0, 0, 0);
		}

		// Waiting passengers used to be placed exactly on the rail path — standing
		// between the rails, run through by every arriving train, and buried to
		// chest depth wherever the platform surface sits above the track plane
		// (the rail Y is the track block layer, not the platform floor — a whole
		// crowd "half sunk" reads exactly like uniformly half-sized passengers).
		// They now stand beside the track, on the actual standable surface: the
		// rail heading at a deterministic along-track position gives a perpendicular
		// direction; both sides are probed with the shared ground scan, preferring
		// the side whose surface is level with or above the rails (the platform
		// side). The passenger faces the track while waiting.
		final double railLength = rail.railMath.getLength();
		final double alongTrack = getRandomValue(passengerId, 0, railLength);
		final Vector position = rail.railMath.getPosition(alongTrack, false);
		final Vector positionAhead = rail.railMath.getPosition(Math.min(railLength, alongTrack + 0.1), false);
		final double differenceX = positionAhead.x() - position.x();
		final double differenceZ = positionAhead.z() - position.z();
		final double horizontalLength = Math.sqrt(differenceX * differenceX + differenceZ * differenceZ);

		if (horizontalLength < 1 / 1024D) {
			// Degenerate heading sample (start of rail) — fall back to the old behaviour.
			return new PositionAndYaw(position.x(), position.y(), position.z(), 0, 0);
		}

		final double perpendicularX = -differenceZ / horizontalLength;
		final double perpendicularZ = differenceX / horizontalLength;
		final long railY = (long) Math.floor(position.y());

		double bestX = position.x();
		double bestY = position.y();
		double bestZ = position.z();
		double bestGroundY = Double.NEGATIVE_INFINITY;

		// Probe both sides at increasing offsets; keep the highest standable surface
		// within a sane window around the track (the platform edge is 1-3 blocks up
		// and 1-3 blocks out; the wrong side of the track is usually level ground or
		// a wall, which scores lower or fails the window check).
		for (int side = 0; side < 2; side++) {
			final double sideSign = side == 0 ? 1 : -1;
			for (double offset = 1.5; offset <= 2.75; offset += 1.25) {
				final double x = position.x() + perpendicularX * offset * sideSign + (getRandomValue(passengerId + side, -0.3, 0.3));
				final double z = position.z() + perpendicularZ * offset * sideSign + (getRandomValue(passengerId + side + 1, -0.3, 0.3));
				final double groundY = findGroundY(clientWorld, x, z, railY, railY + 3);
				if (groundY >= railY - 0.5 && groundY <= railY + 2.5 && groundY > bestGroundY) {
					bestGroundY = groundY;
					bestX = x;
					bestY = groundY;
					bestZ = z;
				}
			}
		}

		if (bestGroundY == Double.NEGATIVE_INFINITY) {
			// No standable ground next to the track (fully enclosed?) — old behaviour.
			return new PositionAndYaw(position.x(), position.y(), position.z(), 0, 0);
		}

		// Face the track while waiting (heading convention: atan2(dz, dx)).
		final double yaw = Math.atan2(position.z() - bestZ, position.x() - bestX);
		return new PositionAndYaw(bestX, bestY, bestZ, yaw, 0);
	}

	/**
	 * Animation speed for the idle wander: walking (at half speed) while the wander walk is
	 * still in progress, standing while turning or waiting for the next wander segment.
	 *
	 * @param walkingTime  total duration of this wander walk in milliseconds
	 * @param progress     elapsed time within the wander segment in milliseconds
	 * @param walkingSpeed the wander walking speed in blocks per millisecond
	 * @return the current walking speed in blocks per second, or 0 when standing
	 */
	private static double getWanderAnimationSpeed(double walkingTime, long progress, double walkingSpeed) {
		return walkingTime > 0 && progress < walkingTime ? walkingSpeed * Utilities.MILLIS_PER_SECOND : 0;
	}

	/**
	 * @return a deterministic pseudo-random value based on a seed
	 */
	private static double getRandomValue(long seed, double min, double max) {
		return min + (Math.floorMod(seed * GOLDEN_RATIO_FRACTION, RESOLUTION)) * (max - min) / RESOLUTION;
	}

	private static void render(Passenger passenger, PositionAndYaw positionAndYaw) {
		final Minecraft minecraftClient = Minecraft.getInstance();
		final ClientLevel clientWorld = minecraftClient.level;

		// The scheduled lambda below captures the position, so it must be effectively final —
		// the smoothed position lives in its own local.
		final PositionAndYaw smoothedPositionAndYaw;
		if (clientWorld != null) {
			// Anti-teleport smoothing of the rendered position (leg re-plans, degenerate
			// fallbacks, ground-step easing); also the single choke point every on-foot
			// passenger passes through each frame, so the collision boxes track exactly
			// what the player sees.
			smoothedPositionAndYaw = smoothPosition(passenger.getId(), positionAndYaw);

			if (CullingHelper.getDistanceFromCamera(smoothedPositionAndYaw.x, smoothedPositionAndYaw.y, smoothedPositionAndYaw.z) <= COLLISION_RANGE) {
				PassengerCollisions.update(passenger.getId(), clientWorld.dimension(), smoothedPositionAndYaw.x, smoothedPositionAndYaw.y, smoothedPositionAndYaw.z);
			}
		} else {
			smoothedPositionAndYaw = positionAndYaw;
		}

		if (clientWorld != null && CullingHelper.getDistanceFromCamera(smoothedPositionAndYaw.x, smoothedPositionAndYaw.y, smoothedPositionAndYaw.z) <= minecraftClient.levelRenderer.getLastViewDistance() * 8) {
			MainRenderer.scheduleRender(QueuedRenderLayer.EXTERIOR, (matrixStack, vertexConsumer, offset) -> {
				matrixStack.pushPose();
				matrixStack.translate(smoothedPositionAndYaw.x - offset.x, smoothedPositionAndYaw.y - offset.y, smoothedPositionAndYaw.z - offset.z);
				Drawing.rotateYRadians(matrixStack, (float) (Math.PI / 2 - smoothedPositionAndYaw.yaw));
				final BlockPos blockPos = BlockPos.containing(smoothedPositionAndYaw.x, smoothedPositionAndYaw.y, smoothedPositionAndYaw.z);
				final int light = LightTexture.pack(clientWorld.getBrightness(LightLayer.BLOCK, blockPos), clientWorld.getBrightness(LightLayer.SKY, blockPos));
				// Rendered directly through the model renderer (walk cycle, idle head movement,
				// phone pose, skin) instead of the entity render dispatcher. The expected matrix
				// state on entry is identical to what the dispatcher call expected.
				PassengerModelRenderer.renderPassenger(clientWorld, passenger, smoothedPositionAndYaw.speed, matrixStack, minecraftClient.renderBuffers().bufferSource(), light);
				matrixStack.popPose();
			});
		}
	}

	/**
	 * Per-passenger rendered-position state. XZ follows the analytical target directly (so the
	 * walk cycle and the visual speed always agree — no foot sliding); a per-frame target jump
	 * between {@link #BLEND_TRIGGER_DISTANCE} and {@link #SNAP_DISTANCE} blocks starts a short
	 * linear blend (a visible discontinuity, e.g. a re-planned wander restart, glides instead
	 * of blinking), a jump beyond {@link #SNAP_DISTANCE} snaps (a genuine teleport — blending
	 * it would read as a superman slide). Y eases exponentially at all times: ground samples
	 * arrive in half-block steps and read as slopes rather than stair pops.
	 */
	private static PositionAndYaw smoothPosition(long passengerId, PositionAndYaw target) {
		final long now = System.currentTimeMillis();
		SmoothedPosition state = SMOOTHED_POSITIONS.getAndMoveToFirst(passengerId);

		if (state == null) {
			state = new SmoothedPosition(target.x, target.y, target.z, now);
			SMOOTHED_POSITIONS.putAndMoveToFirst(passengerId, state);
			while (SMOOTHED_POSITIONS.size() > POSITION_CACHE_LIMIT) {
				SMOOTHED_POSITIONS.removeLast();
			}
			return target;
		}

		final float deltaTime = state.lastUpdateTime == 0 ? 0 : Math.max(0, Math.min(0.25F, (now - state.lastUpdateTime) / 1000F));
		state.lastUpdateTime = now;

		final double jumpX = target.x - state.targetX;
		final double jumpZ = target.z - state.targetZ;
		final double jumpHorizontal = Math.sqrt(jumpX * jumpX + jumpZ * jumpZ);

		if (jumpHorizontal > SNAP_DISTANCE) {
			state.blending = false;
			state.renderX = target.x;
			state.renderZ = target.z;
		} else if (jumpHorizontal > BLEND_TRIGGER_DISTANCE) {
			state.blending = true;
			state.blendStart = now;
			state.fromX = state.renderX;
			state.fromZ = state.renderZ;
		}

		if (Math.abs(target.y - state.targetY) > SNAP_DISTANCE) {
			state.renderY = target.y;
		}

		state.targetX = target.x;
		state.targetY = target.y;
		state.targetZ = target.z;

		state.renderY += (target.y - state.renderY) * Math.min(1, deltaTime * Y_SMOOTHING_PER_SECOND);

		if (state.blending) {
			final float blend = (now - state.blendStart) / (float) BLEND_MILLIS;
			if (blend >= 1) {
				state.blending = false;
				state.renderX = target.x;
				state.renderZ = target.z;
			} else {
				state.renderX = state.fromX + (target.x - state.fromX) * blend;
				state.renderZ = state.fromZ + (target.z - state.fromZ) * blend;
			}
		} else {
			state.renderX = target.x;
			state.renderZ = target.z;
		}

		return new PositionAndYaw(state.renderX, state.renderY, state.renderZ, target.yaw, target.speed);
	}

	private static final class SmoothedPosition {
		private double targetX, targetY, targetZ;
		private double renderX, renderY, renderZ;
		private double fromX, fromZ;
		private boolean blending;
		private long blendStart;
		private long lastUpdateTime;

		private SmoothedPosition(double x, double y, double z, long now) {
			targetX = renderX = fromX = x;
			targetY = renderY = y;
			targetZ = renderZ = fromZ = z;
			lastUpdateTime = now;
			blending = false;
		}
	}

	private record PositionAndYaw(double x, double y, double z, double yaw, double speed) {
	}
}
