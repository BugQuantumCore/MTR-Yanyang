package org.mtr.mod.data;

import net.fabricmc.api.ModInitializer;
import org.mtr.core.data.Client;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.operation.SignalBlockUpdate;
import org.mtr.core.operation.VehicleLiftResponse;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs inside Loom's isolated dedicated-server launch, after Fabric has applied
 * MTR's mixins and before Minecraft creates a world.
 */
public final class ServerOptimizationIntegrationEntrypoint implements ModInitializer {
	private static final Method SIMULATOR_TICK = simulatorTickMethod();
	private static final String[] RESERVATION_FIELDS = {
			"preBlockedVehicleIds", "currentlyBlockedVehicleIds", "preBlockedVehicleIdsOld", "currentlyBlockedVehicleIdsOld"
	};

	@Override
	public void onInitialize() {
		int status = 1;
		try {
			runChecks();
			Files.writeString(Path.of("server-optimization-test.ok"), "MTR_SERVER_OPTIMIZATION_INTEGRATION_OK");
			System.out.println("MTR_SERVER_OPTIMIZATION_INTEGRATION_OK: transformed rail tick, reservation buffers, client visibility and sync invalidation");
			status = 0;
		} catch (Throwable failure) {
			failure.printStackTrace();
		} finally {
			System.exit(status);
		}
	}

	private static void runChecks() throws Throwable {
		final Path dataDirectory = Files.createTempDirectory("mtr-server-optimization-");
		Simulator simulator = null;
		try {
			simulator = new Simulator("overworld", new String[]{"overworld"}, dataDirectory, false);
			final Rail firstRail = rail(0, 32);
			final Rail secondRail = rail(1000, 1032);
			simulator.rails.add(firstRail);
			simulator.rails.add(secondRail);
			simulator.sync();

			final Client client = new Client(UUID.randomUUID());
			client.setPositionAndUpdateRadius(position(16), 80);
			simulator.clients.add(client);

			final Long2LongAVLTreeMap firstPre = railMap(firstRail, "preBlockedVehicleIds");
			final Long2LongAVLTreeMap firstPreOld = railMap(firstRail, "preBlockedVehicleIdsOld");
			final Long2LongAVLTreeMap firstCurrently = railMap(firstRail, "currentlyBlockedVehicleIds");
			final Long2LongAVLTreeMap firstCurrentlyOld = railMap(firstRail, "currentlyBlockedVehicleIdsOld");
			firstPre.put(101L, 1001L);
			firstPreOld.put(201L, 2001L);
			firstCurrently.put(301L, 3001L);
			firstCurrentlyOld.put(401L, 4001L);

			tick(simulator, 1L);
			assertSwappedBuffer(firstRail, "preBlockedVehicleIds", firstPreOld, "preBlockedVehicleIdsOld", firstPre, 101L, 1001L, "pre-blocked");
			assertSwappedBuffer(firstRail, "currentlyBlockedVehicleIds", firstCurrentlyOld, "currentlyBlockedVehicleIdsOld", firstCurrently, 301L, 3001L, "currently-blocked");
			final List<SignalBlockUpdate> initialUpdates = signalUpdates(simulator);
			check(hasRailUpdate(initialUpdates, firstRail), "A newly visible client did not receive the rail's first update");
			check(!hasRailUpdate(initialUpdates, secondRail), "A client received a rail update outside its radius");
			assertExistingRails(client, firstRail);
			final Object visibility = requireRailVisibilityOwner(simulator);
			check(!visibilityDirty(visibility), "The real Simulator rail phase did not build its visibility cache");

			// Replace each one-element color-key set with a different one-element
			// set. A count-only comparison would miss this real signal change.
			final Long2LongAVLTreeMap nextPre = railMap(firstRail, "preBlockedVehicleIds");
			final Long2LongAVLTreeMap nextCurrently = railMap(firstRail, "currentlyBlockedVehicleIds");
			nextPre.put(102L, 1002L);
			nextCurrently.put(302L, 3002L);
			check(nextPre.size() == railMap(firstRail, "preBlockedVehicleIdsOld").size(), "Pre-blocked fixture must retain the same number of color keys");
			check(nextCurrently.size() == railMap(firstRail, "currentlyBlockedVehicleIdsOld").size(), "Currently-blocked fixture must retain the same number of color keys");

			tick(simulator, 1L);
			assertSwappedBuffer(firstRail, "preBlockedVehicleIds", firstPre, "preBlockedVehicleIdsOld", nextPre, 102L, 1002L, "pre-blocked replacement");
			assertSwappedBuffer(firstRail, "currentlyBlockedVehicleIds", firstCurrently, "currentlyBlockedVehicleIdsOld", nextCurrently, 302L, 3002L, "currently-blocked replacement");
			final List<SignalBlockUpdate> replacementUpdates = signalUpdates(simulator);
			check(hasRailUpdate(replacementUpdates, firstRail), "Replacing a color key with another key of the same count dropped the rail update");

			// The changed reservation set drains on the next tick. Two following
			// empty ticks must keep the subscribed rail without retransmitting it.
			tick(simulator, 1L);
			signalUpdates(simulator);
			check(allReservationMapsEmpty(firstRail), "Reservation maps did not settle to an empty quiet state");
			for (int quietTick = 0; quietTick < 2; quietTick++) {
				tick(simulator, 1L);
				check(signalUpdates(simulator).isEmpty(), "An unchanged visible rail emitted a signal update on a quiet tick");
				assertExistingRails(client, firstRail);
			}

			client.setPositionAndUpdateRadius(position(500), 80);
			tick(simulator, 1L);
			final Set<String> afterLeave = existingRailIds(client);
			check(afterLeave.isEmpty(), "A rail remained registered after the client left its update radius: " + afterLeave);
			signalUpdates(simulator);

			client.setPositionAndUpdateRadius(position(1016), 80);
			tick(simulator, 1L);
			final List<SignalBlockUpdate> movedUpdates = signalUpdates(simulator);
			check(hasRailUpdate(movedUpdates, secondRail), "Moving into a new rail's radius did not send that rail's first update");
			assertExistingRails(client, secondRail);

			final int railCount = simulator.rails.size();
			simulator.rails.remove(secondRail);
			final Rail replacementRail = rail(1000, 1033);
			simulator.rails.add(replacementRail);
			check(simulator.rails.size() == railCount, "Replacement fixture must keep the same number of rails");
			simulator.sync();
			check(visibilityDirty(visibility), "Data.sync did not invalidate the existing rail visibility cache");
			tick(simulator, 1L);
			check(!visibilityDirty(visibility), "The rail visibility cache did not rebuild after Data.sync");
			final List<SignalBlockUpdate> syncUpdates = signalUpdates(simulator);
			check(hasRailUpdate(syncUpdates, replacementRail), "Data.sync did not invalidate visibility state for a same-count rail replacement");
			assertExistingRails(client, replacementRail);

			testSignalComponentsAgainstOldCore(simulator);
			testStaleRailTopologyAfterSync(simulator);
			testVehicleOccupancyHotPath(simulator);
			if (Boolean.getBoolean("mtr.test.computeBenchmark")) {
				ServerComputeBenchmark.run();
			}
		} finally {
			try {
				if (simulator != null) {
					simulator.stop();
				}
			} finally {
				deleteTree(dataDirectory);
			}
		}
	}

	private static void testSignalComponentsAgainstOldCore(Simulator simulator) throws Exception {
		final long red = 11L;
		final long blue = 22L;
		final Position[] nodes = {
				position(2000, 0), position(2032, 0), position(2032, 32),
				position(2000, 32), position(2064, 32), position(2096, 32)
		};
		final int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {2, 4}, {4, 5}};
		final long[][] colors = {{red, blue}, {red}, {red}, {red}, {red}, {blue}};
		final long[][] endpoints = new long[edges.length][4];
		final List<Rail> rails = new ArrayList<>(edges.length);
		for (int i = 0; i < edges.length; i++) {
			final Position first = nodes[edges[i][0]];
			final Position second = nodes[edges[i][1]];
			endpoints[i] = new long[]{first.getX(), first.getZ(), second.getX(), second.getZ()};
			final Rail rail = rail(simulator, first, second);
			addSignalColors(rail, first, second, colors[i]);
			rails.add(rail);
		}
		simulator.sync();

		try (OldCoreOracle oracle = OldCoreOracle.open()) {
			final OldCoreOracle.SignalGraph oldGraph = oracle.signalGraph(endpoints, colors);
			final long beforeReserve = signalComponentBuildCount();
			final boolean actualBlocked = isBlocked(rails.get(0), 9001L, "PRE_RESERVE");
			final boolean oldBlocked = oldGraph.isBlocked(0, 9001L, "PRE_RESERVE");
			check(actualBlocked == oldBlocked && !actualBlocked, "The transformed rail's same-color cycle/branch reservation disagreed with the old core");
			check(signalComponentBuildCount() == beforeReserve + 2,
				"Rail.isBlocked did not build exactly one cached component for each of its two signal colors");
			assertSignalReservations(rails, oldGraph.reservations(), "initial red/blue cycle reservation");

			final Object redComponentBefore = signalComponent(rails.get(0), red);
			final Object blueComponentBefore = signalComponent(rails.get(0), blue);
			final long afterCacheHits = signalComponentBuildCount();
			check(redComponentBefore == signalComponent(rails.get(0), red), "A repeated red component lookup missed the cache");
			check(blueComponentBefore == signalComponent(rails.get(0), blue), "A repeated blue component lookup missed the cache");
			check(componentSize(redComponentBefore) == 5, "Red component did not include its cycle and single red branch");
			check(componentSize(blueComponentBefore) == 1, "Blue component crossed a red-only edge to an unrelated blue rail");
			check(signalComponentBuildCount() == afterCacheHits, "A component cache hit rebuilt the graph traversal");
			for (int index : new int[]{1, 2, 3, 4}) {
				check(componentContainsIdentity(redComponentBefore, rails.get(index)), "Red component omitted connected rail " + index);
			}
			check(!componentContainsIdentity(redComponentBefore, rails.get(5)), "Red component crossed a red-only/blue-only boundary");

			clearSignalReservations(rails);
			oldGraph.clearReservations();
			setRailReservation(rails.get(0), "currentlyBlockedVehicleIdsOld", red, 778L);
			oldGraph.setReservation(0, "currentlyBlockedVehicleIdsOld", red, 778L);
			final boolean conflictActual = isBlocked(rails.get(0), 777L, "PRE_RESERVE");
			final boolean conflictOld = oldGraph.isBlocked(0, 777L, "PRE_RESERVE");
			check(conflictActual == conflictOld && conflictActual, "A reservation conflict did not preserve the old all-or-nothing gate");
			assertSignalReservations(rails, oldGraph.reservations(), "conflicting reservation no-write behavior");

			clearSignalReservations(rails);
			oldGraph.clearReservations();
			simulator.sync();
			oldGraph.rebuildConnections();
			final LongArrayList manualColors = new LongArrayList();
			manualColors.add(red);
			rails.get(4).blockRail(manualColors);
			oldGraph.blockRail(4, red);
			setRailReservation(rails.get(4), "preBlockedVehicleIdsOld", red, 53L);
			oldGraph.setReservation(4, "preBlockedVehicleIdsOld", red, 53L);
			final long beforeBlockedManualTick = signalComponentBuildCount();
			tickRail2(rails.get(4), 1L);
			oldGraph.tick2(4, 1L);
			check(signalComponentBuildCount() == beforeBlockedManualTick,
				"A manual-block owner conflict traversed or cached the component before the four-window gate allowed it");
			assertSignalReservations(rails, oldGraph.reservations(), "manual block held behind a conflicting old window");

			clearSignalReservations(rails);
			oldGraph.clearReservations();
			final long beforeManualReservation = signalComponentBuildCount();
			tickRail2(rails.get(4), 1L);
			oldGraph.tick2(4, 1L);
			check(signalComponentBuildCount() == beforeManualReservation + 1,
				"Rail.tick2 did not enter the cached component path after its manual-block gates passed");
			assertSignalReservations(rails, oldGraph.reservations(), "manual red reservation from a branch root");
			final Object branchRedBeforeSync = signalComponent(rails.get(4), red);
			final Object branchBlueBeforeSync = signalComponent(rails.get(4), blue);
			check(componentSize(branchRedBeforeSync) == 5 && componentSize(branchBlueBeforeSync) == 0,
				"Branch-root components did not honor exact signal colors before modification");

			clearSignalReservations(rails);
			oldGraph.clearReservations();
			modifySignalColor(rails.get(4), nodes[2], nodes[4], (int) red, (int) blue);
			oldGraph.modifyColor(4, (int) red, (int) blue);
			simulator.sync();
			oldGraph.rebuildConnections();
			final long beforeModifiedReservation = signalComponentBuildCount();
			final boolean modifiedActual = isBlocked(rails.get(4), 555L, "PRE_RESERVE");
			final boolean modifiedOld = oldGraph.isBlocked(4, 555L, "PRE_RESERVE");
			check(modifiedActual == modifiedOld && !modifiedActual, "Signal color modification changed the transformed reservation result");
			check(signalComponentBuildCount() == beforeModifiedReservation + 1,
				"The color edit plus Data.sync did not invalidate the prior component before reservation");
			assertSignalReservations(rails, oldGraph.reservations(), "blue branch after signal color edit and Data.sync");
			final Object branchBlueAfterSync = signalComponent(rails.get(4), blue);
			final Object rootRedAfterSync = signalComponent(rails.get(0), red);
			check(branchBlueAfterSync != branchBlueBeforeSync && componentSize(branchBlueAfterSync) == 2,
				"Data.sync did not replace the old branch blue component with its newly connected blue edge");
			check(rootRedAfterSync != redComponentBefore && componentSize(rootRedAfterSync) == 4,
				"Data.sync did not rebuild the red cycle after the branch lost red");

			final long beforeOtherRoot = signalComponentBuildCount();
			final boolean otherRootActual = isBlocked(rails.get(0), 555L, "PRE_RESERVE");
			final boolean otherRootOld = oldGraph.isBlocked(0, 555L, "PRE_RESERVE");
			check(otherRootActual == otherRootOld && !otherRootActual, "The modified red cycle disagreed when reserved from its other root");
			check(signalComponentBuildCount() == beforeOtherRoot + 1,
				"Rail.isBlocked did not reuse the cached red cycle while building the modified root's separate blue component");
			assertSignalReservations(rails, oldGraph.reservations(), "post-sync disconnected red/blue components");
		}
	}

	private static void testVehicleOccupancyHotPath(Simulator simulator) throws Throwable {
		final Rail rail0 = rail(simulator, position(3000, 0), position(3032, 0));
		final Rail rail1 = rail(simulator, position(3032, 0), position(3064, 0));
		final Rail rail2 = rail(simulator, position(3064, 0), position(3096, 0));
		final Rail reverseRail0 = rail(simulator, position(3000, 64), position(3032, 64));
		final Rail reverseRail1 = rail(simulator, position(3032, 64), position(3064, 64));
		final Rail reverseRail2 = rail(simulator, position(3064, 64), position(3096, 64));
		simulator.sync();

		final Object vehiclePositions = field(simulator, "vehiclePositions").get(simulator);
		@SuppressWarnings("unchecked")
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trainBuffers =
				(ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>>) ((List<?>) vehiclePositions).get(TransportMode.TRAIN.ordinal());
		check(trainBuffers.size() == 2, "The actual Simulator did not retain the two vehicle occupancy windows");
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> previous = trainBuffers.get(0);
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> current = trainBuffers.get(1);
		check(previous instanceof VehicleOccupancyMap && current instanceof VehicleOccupancyMap,
			"The actual Simulator constructor did not replace its two train occupancy maps");
		final VehicleOccupancyMap previousCompact = (VehicleOccupancyMap) previous;
		final VehicleOccupancyMap currentCompact = (VehicleOccupancyMap) current;

		final Vehicle forwardA = createVehicleWithPath(simulator, Long.MAX_VALUE, 42.0, rail0, false, rail1, false, rail2, false);
		final Vehicle forwardB = createVehicleWithPath(simulator, Long.MAX_VALUE - 1, 50.0, rail0, false, rail1, false, rail2, false);
		check(forwardA.vehicleExtraData.immutablePath.size() == 3, "Forward Vehicle fixture did not build the expected three actual PathData entries");
		writeVehiclePositions(forwardA, 1, current);
		check(!currentCompact.isCompatibilityMode() && currentCompact.compactIntervalCount() == 1,
			"Vehicle.writeVehiclePositions did not record through the compact hot path");
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> liveBuffers = trainBuffers;
		check(railBlockedDistance(forwardA, 1, 20.0, 20.0, liveBuffers, false, false) < 0.0,
			"The actual Vehicle query did not exclude its own compact occupancy interval");
		check(!currentCompact.isCompatibilityMode(), "Vehicle.railBlockedDistance materialized the compact map via legacy Map.get");
		writeVehiclePositions(forwardB, 1, current);
		check(!currentCompact.isCompatibilityMode() && currentCompact.compactIntervalCount() == 2,
			"The second real Vehicle write was not immediately appended to the same compact rail interval");

		try (OldCoreOracle oracle = OldCoreOracle.open()) {
			final PathData pathData = forwardA.vehicleExtraData.immutablePath.get(1);
			check(getLongField(forwardA, "id") == Long.MAX_VALUE && getLongField(forwardB, "id") == Long.MAX_VALUE - 1,
				"The full Vehicle path did not retain adjacent large signed 64-bit IDs");
			final OldCoreOracle.VehicleSegment forwardSegmentA = segmentFor(forwardA, pathData);
			final OldCoreOracle.VehicleSegment forwardSegmentB = segmentFor(forwardB, pathData);
			final double[] forwardQuery = blockedBounds(pathData, 20.0, 20.0 + 20.0 + TransportMode.TRAIN.stoppingSpace);
			final double forwardOverlap = oracle.closestVehicleOverlap(List.of(forwardSegmentA, forwardSegmentB),
				forwardQuery[0], forwardQuery[1], pathData.reversePositions, Long.MAX_VALUE);
			final double forwardExpected = expectedVehicleBlockDistance(pathData, 20.0, forwardOverlap);
			final double forwardActual = railBlockedDistance(forwardA, 1, 20.0, 20.0, liveBuffers, false, false);
			check(forwardOverlap >= 0.0 && Math.abs(forwardActual - forwardExpected) < 1.0E-9,
				"The transformed Vehicle read did not observe the later writer with old forward-distance semantics: expected " + forwardExpected + ", got " + forwardActual);
			final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> retiredPrevious = trainBuffers.get(0);
			final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> retiredCurrent = trainBuffers.get(1);
			tick(simulator, 1L);
			check(trainBuffers.get(0) == retiredCurrent && trainBuffers.get(1) == retiredPrevious,
				"The Simulator tick did not preserve the previous/current occupancy window order while rotating maps");
			check(((VehicleOccupancyMap) trainBuffers.get(0)).compactIntervalCount() == 2,
				"The just-retired current window was not available as the previous window for one tick");
			final double previousWindowDistance = railBlockedDistance(forwardA, 1, 20.0, 20.0, liveBuffers, false, false);
			check(Math.abs(previousWindowDistance - forwardExpected) < 1.0E-9,
				"The Vehicle query stopped seeing occupancy in the previous tick window");
			tick(simulator, 1L);
			check(((VehicleOccupancyMap) trainBuffers.get(1)).compactIntervalCount() == 0,
				"The retired occupancy window was not cleared before reuse on the next tick");
			check(railBlockedDistance(forwardA, 1, 20.0, 20.0, liveBuffers, false, false) < 0.0,
				"A vehicle interval remained visible after its two-window lifetime elapsed");

			final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> reverseWriteMap = trainBuffers.get(1);
			final Vehicle reverseA = createVehicleWithPath(simulator, Long.MIN_VALUE, 42.0,
				reverseRail1, false, reverseRail1, true, reverseRail0, true);
			final Vehicle reverseB = createVehicleWithPath(simulator, Long.MAX_VALUE, 50.0,
				reverseRail1, false, reverseRail1, true, reverseRail0, true);
			writeVehiclePositions(reverseA, 1, reverseWriteMap);
			check(((VehicleOccupancyMap) reverseWriteMap).compactIntervalCount() == 1,
				"A reverse-oriented Vehicle did not enter the compact occupancy writer");
			writeVehiclePositions(reverseB, 1, reverseWriteMap);
			final PathData reversePathData = reverseA.vehicleExtraData.immutablePath.get(1);
			check(reversePathData.reversePositions, "The reverse fixture did not set PathData.reversePositions");
			final OldCoreOracle.VehicleSegment reverseSegmentA = segmentFor(reverseA, reversePathData);
			final OldCoreOracle.VehicleSegment reverseSegmentB = segmentFor(reverseB, reversePathData);
			final double[] reverseQuery = blockedBounds(reversePathData, 20.0, 20.0 + 20.0 + TransportMode.TRAIN.stoppingSpace);
			final double reverseOverlap = oracle.closestVehicleOverlap(List.of(reverseSegmentA, reverseSegmentB),
				reverseQuery[0], reverseQuery[1], true, Long.MIN_VALUE);
			final double reverseExpected = expectedVehicleBlockDistance(reversePathData, 20.0, reverseOverlap);
			final double reverseActual = railBlockedDistance(reverseA, 1, 20.0, 20.0, liveBuffers, false, false);
			check(reverseOverlap >= 0.0 && Math.abs(reverseActual - reverseExpected) < 1.0E-9,
				"The transformed Vehicle read did not preserve old reverse-distance semantics: expected " + reverseExpected + ", got " + reverseActual);
			check(!((VehicleOccupancyMap) reverseWriteMap).isCompatibilityMode(),
				"Reverse Vehicle writes or reads unexpectedly materialized the legacy map");

			check(oracle.closestVehicleOverlap(List.of(new OldCoreOracle.VehicleSegment(0.0, 2.0, Long.MIN_VALUE)),
				0.0, 1.0, false, Long.MIN_VALUE) < 0.0,
				"Old-core oracle did not exclude an exact Long.MIN_VALUE owner");
			check(oracle.closestVehicleOverlap(List.of(new OldCoreOracle.VehicleSegment(0.0, 2.0, Long.MAX_VALUE)),
				0.0, 1.0, false, Long.MAX_VALUE - 1) == 0.0,
				"Old-core oracle lost precision between adjacent large vehicle IDs");
		}
	}

	private static void testStaleRailTopologyAfterSync(Simulator simulator) throws Exception {
		final long color = 44L;
		final Position first = position(4000, 0);
		final Position shared = position(4032, 0);
		final Position last = position(4064, 0);
		final Position next = position(4096, 0);
		final Position end = position(4128, 0);
		final Rail oldA = rail(simulator, first, shared);
		final Rail liveB = rail(simulator, shared, last);
		final Rail liveC = rail(simulator, last, next);
		final Rail liveD = rail(simulator, next, end);
		addSignalColors(oldA, first, shared, color);
		addSignalColors(liveB, shared, last, color);
		addSignalColors(liveC, last, next, color);
		addSignalColors(liveD, next, end, color);
		simulator.sync();
		check(hasConnectedRail(oldA, "connectedRails2", liveB) && hasConnectedRail(liveB, "connectedRails1", oldA),
			"The stale-topology fixture did not begin with a real bidirectional A/B connection");

		check(simulator.rails.remove(oldA), "Could not remove the original A rail before its same-endpoint replacement");
		final Rail replacementA = rail(simulator, first, shared);
		addSignalColors(replacementA, first, shared, color);
		simulator.sync();
		check(!simulator.rails.contains(oldA) && simulator.rails.contains(replacementA),
			"Data.sync did not retain the same-endpoint replacement instead of the original A rail");
		check(hasConnectedRail(oldA, "connectedRails2", liveB),
			"The removed A rail unexpectedly lost its stale outgoing edge to live B");
		check(hasConnectedRail(liveB, "connectedRails1", replacementA) && !hasConnectedRail(liveB, "connectedRails1", oldA),
			"Data.sync did not rebuild live B's incoming edge from replacement A");
		check(hasConnectedRail(liveB, "connectedRails2", liveC) && hasConnectedRail(liveC, "connectedRails2", liveD),
			"The stale-topology fixture did not retain its full four-rail live chain");

		final long beforeOldRoot = signalComponentBuildCount();
		check(!isBlocked(oldA, 6101L, "PRE_RESERVE"), "The removed A rail unexpectedly reported a reservation conflict");
		check(signalComponentBuildCount() == beforeOldRoot + 1,
			"Calling the real Rail.isBlocked on removed A did not build its stale reachable snapshot");
		final List<Rail> liveRails = List.of(replacementA, liveB, liveC, liveD);
		final List<Rail> allRails = new ArrayList<>(liveRails.size() + 1);
		allRails.add(oldA);
		allRails.addAll(liveRails);
		assertReservationWrites(allRails, allRails, "preBlockedVehicleIds", color, 6101L,
			"first stale-root traversal beyond the component cache's bounded expansion");
		final Object oldReachable = signalComponent(oldA, color);
		check(componentSize(oldReachable) == 5 && componentContainsIdentity(oldReachable, oldA)
				&& componentContainsIdentity(oldReachable, liveB) && componentContainsIdentity(oldReachable, replacementA)
				&& componentContainsIdentity(oldReachable, liveC) && componentContainsIdentity(oldReachable, liveD),
			"The removed A snapshot did not preserve its actual five-rail old-A -> B -> replacement-A/C/D reachable graph");

		clearSignalReservations(allRails);
		final long beforeRepeatedOldRoot = signalComponentBuildCount();
		check(!isBlocked(oldA, 6102L, "PRE_RESERVE"), "The removed A rail unexpectedly reported a conflict on its repeated reservation");
		check(signalComponentBuildCount() == beforeRepeatedOldRoot,
			"A repeated stale-root reservation rebuilt the over-expanded component instead of taking the original DFS fallback");
		assertReservationWrites(allRails, allRails, "preBlockedVehicleIds", color, 6102L,
			"repeated stale-root Invoker fallback reservation");

		clearSignalReservations(allRails);
		final long beforeLiveRoot = signalComponentBuildCount();
		check(!isBlocked(liveB, 6103L, "PRE_RESERVE"), "Live B unexpectedly reported a reservation conflict after sync");
		check(signalComponentBuildCount() == beforeLiveRoot + 1,
			"Calling Rail.isBlocked on live B reused the stale predecessor's reachable component");
		final Object liveReachable = signalComponent(liveB, color);
		check(liveReachable != oldReachable && componentSize(liveReachable) == 4
				&& componentContainsIdentity(liveReachable, liveB) && componentContainsIdentity(liveReachable, replacementA)
				&& componentContainsIdentity(liveReachable, liveC) && componentContainsIdentity(liveReachable, liveD)
				&& !componentContainsIdentity(liveReachable, oldA),
			"Live B reused the removed A's over-expanded reachable component instead of its four-rail post-sync component");
		assertReservationWrites(allRails, liveRails, "preBlockedVehicleIds", color, 6103L,
			"post-sync live-root component reservation");
	}

	private static void assertReservationWrites(List<Rail> allRails, List<Rail> expectedRails, String expectedWindow,
			long color, long owner, String label) throws ReflectiveOperationException {
		for (final Rail rail : allRails) {
			boolean shouldWriteRail = false;
			for (final Rail expectedRail : expectedRails) {
				shouldWriteRail |= rail == expectedRail;
			}
			for (final String fieldName : RESERVATION_FIELDS) {
				final Map<Long, Long> expected = shouldWriteRail && fieldName.equals(expectedWindow) ? Map.of(color, owner) : Map.of();
				final Map<Long, Long> actual = copyLongMap(railMap(rail, fieldName));
				check(actual.equals(expected), label + " wrote the wrong members: rail=" + rail + ", window=" + fieldName
						+ ", actual=" + actual + ", expected=" + expected);
			}
		}
	}

	private static boolean hasConnectedRail(Rail rail, String fieldName, Rail connectedRail) throws ReflectiveOperationException {
		final Object value = hierarchyField(rail, fieldName).get(rail);
		check(value instanceof Set<?>, "The core connected-rail cache is not a Set: " + value.getClass().getName());
		return ((Set<?>) value).contains(connectedRail);
	}

	private static Rail rail(Simulator simulator, Position first, Position second) {
		final float angle = (float) Math.toDegrees(Math.atan2(second.getZ() - first.getZ(), second.getX() - first.getX()));
		final Rail rail = Rail.newRail(first, Angle.fromAngle(angle), second, Angle.fromAngle(angle + 180.0F),
				Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, true, true, false, false, TransportMode.TRAIN);
		check(rail.isValid(), "The core rejected a connected-rail integration fixture");
		simulator.rails.add(rail);
		return rail;
	}

	private static Position position(long x, long z) {
		return new Position(x, 64, z);
	}

	private static void addSignalColors(Rail rail, Position first, Position second, long... colors) {
		final SignalModification modification = new SignalModification(first, second, false);
		for (long color : colors) {
			modification.putColorToAdd((int) color);
		}
		rail.applyModification(modification);
	}

	private static void modifySignalColor(Rail rail, Position first, Position second, int oldColor, int newColor) {
		final SignalModification modification = new SignalModification(first, second, false);
		modification.putColorToRemove(oldColor);
		modification.putColorToAdd(newColor);
		rail.applyModification(modification);
	}

	private static boolean isBlocked(Rail rail, long vehicleId, String reservation) throws Exception {
		final Class<?> reservationClass = Class.forName("org.mtr.core.data.Rail$BlockReservation", true, Rail.class.getClassLoader());
		@SuppressWarnings({"rawtypes", "unchecked"})
		final Object reservationValue = Enum.valueOf((Class) reservationClass, reservation);
		final Method method = Rail.class.getDeclaredMethod("isBlocked", long.class, reservationClass);
		method.setAccessible(true);
		try {
			return (boolean) method.invoke(rail, vehicleId, reservationValue);
		} catch (InvocationTargetException e) {
			throw asException(e.getCause());
		}
	}

	private static Object signalComponent(Rail rail, long color) throws Exception {
		final Class<?> helperClass = Class.forName("org.mtr.mod.data.RailSignalComponents", true, Rail.class.getClassLoader());
		final Method method = helperClass.getDeclaredMethod("component", Rail.class, long.class);
		method.setAccessible(true);
		try {
			return method.invoke(null, rail, color);
		} catch (InvocationTargetException e) {
			throw asException(e.getCause());
		}
	}

	private static long signalComponentBuildCount() throws Exception {
		final Class<?> helperClass = Class.forName("org.mtr.mod.data.RailSignalComponents", true, Rail.class.getClassLoader());
		final Method method = helperClass.getDeclaredMethod("componentBuildCount");
		method.setAccessible(true);
		return ((Number) method.invoke(null)).longValue();
	}

	private static int componentSize(Object component) throws ReflectiveOperationException {
		return ((Number) component.getClass().getMethod("size").invoke(component)).intValue();
	}

	private static boolean componentContainsIdentity(Object component, Rail rail) throws ReflectiveOperationException {
		return (boolean) component.getClass().getMethod("containsIdentity", Rail.class).invoke(component, rail);
	}

	private static void tickRail2(Rail rail, long elapsedMillis) {
		rail.tick2(elapsedMillis);
	}

	private static void setRailReservation(Rail rail, String fieldName, long color, long owner) throws ReflectiveOperationException {
		railMap(rail, fieldName).put(color, owner);
	}

	private static void clearSignalReservations(List<Rail> rails) throws ReflectiveOperationException {
		for (Rail rail : rails) {
			for (String fieldName : RESERVATION_FIELDS) {
				railMap(rail, fieldName).clear();
			}
		}
	}

	private static void assertSignalReservations(List<Rail> rails, List<OldCoreOracle.RailReservations> expected, String label)
			throws ReflectiveOperationException {
		check(rails.size() == expected.size(), label + " fixture has mismatched rail counts");
		for (int index = 0; index < rails.size(); index++) {
			for (String fieldName : RESERVATION_FIELDS) {
				final Map<Long, Long> actual = copyLongMap(railMap(rails.get(index), fieldName));
				check(actual.equals(expected.get(index).window(fieldName)),
						label + " rail " + index + " window " + fieldName + " differed: actual=" + actual + ", old=" + expected.get(index).window(fieldName));
			}
		}
	}

	private static Map<Long, Long> copyLongMap(Long2LongAVLTreeMap source) {
		final Map<Long, Long> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : source.entrySet()) {
			result.put(((Number) entry.getKey()).longValue(), ((Number) entry.getValue()).longValue());
		}
		return result;
	}

	/** Test-only fixture builder shared with the optional full-Vehicle benchmark. */
	static Vehicle createVehicleWithPath(Simulator simulator, long vehicleId, double railProgress,
			Rail firstRail, boolean reverseFirst, Rail secondRail, boolean reverseSecond, Rail thirdRail, boolean reverseThird)
			throws ReflectiveOperationException {
		final PathData firstPath = vehiclePath(firstRail, 0.0, railLength(firstRail), reverseFirst);
		final double secondStart = firstPath.getEndDistance();
		final PathData secondPath = vehiclePath(secondRail, secondStart, secondStart + railLength(secondRail), reverseSecond);
		final double thirdStart = secondPath.getEndDistance();
		final PathData thirdPath = vehiclePath(thirdRail, thirdStart, thirdStart + railLength(thirdRail), reverseThird);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("server-optimization-test", 12, 2, 3, 9, 0.5, 0.5));
		final VehicleExtraData extra = VehicleExtraData.create(1, 2, railLength(firstRail), cars,
				singletonPath(firstPath), singletonPath(secondPath), singletonPath(thirdPath), thirdPath,
				false, 0.1, 0.1, false, 80, 0);
		final Vehicle vehicle = new Vehicle(extra, null, TransportMode.TRAIN, simulator);
		setLongField(vehicle, "id", vehicleId);
		setDoubleField(vehicle, "railProgress", railProgress);
		return vehicle;
	}

	static void writeVehiclePositions(Vehicle vehicle, int currentIndex,
			Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> positions) throws Exception {
		final Method method = Vehicle.class.getDeclaredMethod("writeVehiclePositions", int.class, Object2ObjectAVLTreeMap.class);
		method.setAccessible(true);
		try {
			method.invoke(vehicle, currentIndex, positions);
		} catch (InvocationTargetException e) {
			throw asException(e.getCause());
		}
	}

	static double railBlockedDistance(Vehicle vehicle, int currentIndex, double checkProgress, double checkDistance,
			ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> positions,
			boolean reserveRail, boolean secondPass) throws Exception {
		final Method method = Vehicle.class.getDeclaredMethod("railBlockedDistance", int.class, double.class, double.class,
				ObjectArrayList.class, boolean.class, boolean.class);
		method.setAccessible(true);
		try {
			return (double) method.invoke(vehicle, currentIndex, checkProgress, checkDistance, positions, reserveRail, secondPass);
		} catch (InvocationTargetException e) {
			throw asException(e.getCause());
		}
	}

	private static ObjectArrayList<PathData> singletonPath(PathData pathData) {
		final ObjectArrayList<PathData> path = new ObjectArrayList<>();
		path.add(pathData);
		return path;
	}

	private static PathData vehiclePath(Rail rail, double startDistance, double endDistance, boolean reverse)
			throws ReflectiveOperationException {
		Position first = (Position) hierarchyField(rail, "position1").get(rail);
		Position second = (Position) hierarchyField(rail, "position2").get(rail);
		if (reverse) {
			final Position swap = first;
			first = second;
			second = swap;
		}
		return new PathData(rail, 1, 0, 0, startDistance, endDistance,
				first, rail.getStartAngle(first), second, rail.getStartAngle(second));
	}

	private static double railLength(Rail rail) {
		return rail.railMath.getLength();
	}

	private static OldCoreOracle.VehicleSegment segmentFor(Vehicle vehicle, PathData pathData) throws Exception {
		final double progress = getDoubleField(vehicle, "railProgress");
		final double length = vehicle.vehicleExtraData.getTotalVehicleLength();
		final double[] bounds = blockedBounds(pathData, progress - length, progress - 0.01);
		return new OldCoreOracle.VehicleSegment(bounds[0], bounds[1], getLongField(vehicle, "id"));
	}

	private static double[] blockedBounds(PathData pathData, double start, double end) throws Exception {
		final Method method = Vehicle.class.getDeclaredMethod("getBlockedBounds", PathData.class, double.class, double.class);
		method.setAccessible(true);
		try {
			final Object bounds = method.invoke(null, pathData, start, end);
			return new double[]{
					((Number) bounds.getClass().getMethod("leftDouble").invoke(bounds)).doubleValue(),
					((Number) bounds.getClass().getMethod("rightDouble").invoke(bounds)).doubleValue()
			};
		} catch (InvocationTargetException e) {
			throw asException(e.getCause());
		}
	}

	private static double expectedVehicleBlockDistance(PathData pathData, double checkProgress, double closestOverlap) {
		if (closestOverlap < 0.0) {
			return -1.0;
		}
		final double blockedStartOffset = Math.max(0.0, pathData.getStartDistance() - checkProgress);
		return Math.max(0.0, blockedStartOffset + closestOverlap - TransportMode.TRAIN.stoppingSpace);
	}

	private static Field hierarchyField(Object target, String name) throws NoSuchFieldException {
		for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
			try {
				final Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// Search generated schema superclasses for inherited core state.
			}
		}
		throw new NoSuchFieldException(target.getClass().getName() + "." + name);
	}

	private static void setLongField(Object target, String name, long value) throws ReflectiveOperationException {
		hierarchyField(target, name).setLong(target, value);
	}

	private static void setDoubleField(Object target, String name, double value) throws ReflectiveOperationException {
		hierarchyField(target, name).setDouble(target, value);
	}

	private static long getLongField(Object target, String name) throws ReflectiveOperationException {
		return hierarchyField(target, name).getLong(target);
	}

	private static double getDoubleField(Object target, String name) throws ReflectiveOperationException {
		return hierarchyField(target, name).getDouble(target);
	}

	private static Exception asException(Throwable cause) {
		if (cause instanceof Exception exception) {
			return exception;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new RuntimeException(cause);
	}

	private static Method simulatorTickMethod() {
		try {
			final Method method = Simulator.class.getDeclaredMethod("tick", long.class);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static void tick(Simulator simulator, long millis) throws Throwable {
		try {
			SIMULATOR_TICK.invoke(simulator, millis);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private static Rail rail(long x1, long x2) {
		final Rail rail = Rail.newRail(position(x1), Angle.E, position(x2), Angle.W, Rail.Shape.QUADRATIC, 0,
				new ObjectArrayList<>(), 80, 80, false, true, true, false, false, TransportMode.TRAIN);
		check(rail.isValid(), "The core rejected the integration rail fixture");
		return rail;
	}

	private static Position position(long x) {
		return new Position(x, 64, 0);
	}

	private static Long2LongAVLTreeMap railMap(Rail rail, String fieldName) throws ReflectiveOperationException {
		return (Long2LongAVLTreeMap) field(rail, fieldName).get(rail);
	}

	private static void assertSwappedBuffer(Rail rail, String activeName, Long2LongAVLTreeMap expectedActive,
			String oldName, Long2LongAVLTreeMap expectedOld, long expectedKey, long expectedValue, String label) throws ReflectiveOperationException {
		final Long2LongAVLTreeMap active = railMap(rail, activeName);
		final Long2LongAVLTreeMap old = railMap(rail, oldName);
		check(active == expectedActive, label + " active buffer did not swap by identity");
		check(old == expectedOld, label + " old buffer did not swap by identity");
		check(active.isEmpty(), label + " active buffer was not cleared for the next tick");
		check(old.size() == 1 && old.get(expectedKey) == expectedValue,
				label + " old buffer lost its color key or reservation value");
	}

	private static boolean allReservationMapsEmpty(Rail rail) throws ReflectiveOperationException {
		return railMap(rail, "preBlockedVehicleIds").isEmpty() &&
				railMap(rail, "preBlockedVehicleIdsOld").isEmpty() &&
				railMap(rail, "currentlyBlockedVehicleIds").isEmpty() &&
				railMap(rail, "currentlyBlockedVehicleIdsOld").isEmpty();
	}

	private static Object requireRailVisibilityOwner(Simulator simulator) throws ReflectiveOperationException, ClassNotFoundException {
		final Class<?> ownerType = Class.forName("org.mtr.mod.data.ServerRailVisibility$Owner", false, simulator.getClass().getClassLoader());
		check(ownerType.isInstance(simulator), "The Simulator visibility wrapper mixin was not applied");
		final Object visibility = ownerType.getMethod("mtr$getRailVisibility").invoke(simulator);
		check(visibility != null, "The Simulator rail visibility wrapper did not initialize its cache during tick(long)");
		return visibility;
	}

	private static boolean visibilityDirty(Object visibility) throws ReflectiveOperationException {
		return field(visibility, "dirty").getBoolean(visibility);
	}

	private static List<SignalBlockUpdate> signalUpdates(Simulator simulator) {
		final List<SignalBlockUpdate> updates = new ArrayList<>();
		simulator.processMessagesS2C((QueueObject message) -> {
			if ("vehicles_lifts".equals(message.key) && message.data instanceof VehicleLiftResponse response) {
				response.iterateSignalBlockUpdates(updates::add);
			}
		});
		return updates;
	}

	private static boolean hasRailUpdate(List<SignalBlockUpdate> updates, Rail rail) {
		for (SignalBlockUpdate update : updates) {
			if (rail.getHexId().equals(update.getRailId())) {
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> existingRailIds(Client client) throws ReflectiveOperationException {
		return (Set<String>) field(client, "existingRailIds").get(client);
	}

	private static void assertExistingRails(Client client, Rail expectedRail) throws ReflectiveOperationException {
		final Set<String> existing = existingRailIds(client);
		check(existing.size() == 1 && existing.contains(expectedRail.getHexId()),
				"Client's visible rail set was " + existing + ", expected only " + expectedRail.getHexId());
	}

	private static Field field(Object target, String fieldName) throws ReflectiveOperationException {
		final Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		return field;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
