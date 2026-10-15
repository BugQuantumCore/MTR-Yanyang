package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior comparison against the old core's real private reservation traversal. */
final class RailSignalComponentsTest {
	private static final long RED = 0x102030L;
	private static final long BLUE = 0x405060L;
	private static final String HELPER_NAME = "org.mtr.mod.data.RailSignalComponents";

	@Test
	void cachedComponentsAndReservationsMatchTheOldCoreTraversal() throws Exception {
		final Fixture expected = fixture();
		final Fixture actual = fixture();
		final Api api = api();
		final long initialBuildCount = api.buildCount();

		final Object redComponent = api.component(actual.rails.get("a"), RED);
		assertEquals(initialBuildCount + 1, api.buildCount());
		assertEquals(4, api.size(redComponent));
		assertTrue(api.contains(redComponent, actual.rails.get("a")));
		assertTrue(api.contains(redComponent, actual.rails.get("b")));
		assertTrue(api.contains(redComponent, actual.rails.get("d")));
		assertTrue(api.contains(redComponent, actual.rails.get("e")));
		assertFalse(api.contains(redComponent, actual.rails.get("c")));
		assertFalse(api.contains(redComponent, actual.rails.get("isolated")));
		assertSame(redComponent, api.component(actual.rails.get("a"), RED));
		assertSame(redComponent, api.component(actual.rails.get("e"), RED));
		assertEquals(initialBuildCount + 1, api.buildCount(), "members of one component share the cached build");

		final Object blueComponent = api.component(actual.rails.get("b"), BLUE);
		assertEquals(initialBuildCount + 2, api.buildCount());
		assertEquals(2, api.size(blueComponent));
		assertTrue(api.contains(blueComponent, actual.rails.get("b")));
		assertTrue(api.contains(blueComponent, actual.rails.get("c")));
		assertFalse(api.contains(blueComponent, actual.rails.get("a")));
		assertFalse(api.contains(blueComponent, actual.rails.get("e")));

		legacyReserve(expected.rails.get("a"), RED, 17, false);
		assertTrue(api.reserve(actual.rails.get("a"), RED, 17, false));
		legacyReserve(expected.rails.get("c"), BLUE, 17, true);
		assertTrue(api.reserve(actual.rails.get("c"), BLUE, 17, true));
		assertEquals(initialBuildCount + 2, api.buildCount(), "reservation reuses precomputed components");
		assertReservationWindowsEqual(expected, actual);
	}

	@Test
	void manualBlockAndBothReservationWindowsUseTheSameConnectedMembers() throws Exception {
		final Fixture expected = fixture();
		final Fixture actual = fixture();
		final Api api = api();

		legacyReserve(expected.rails.get("b"), RED, 88, false);
		assertTrue(api.reserve(actual.rails.get("b"), RED, 88, false));
		legacyReserve(expected.rails.get("a"), RED, 88, true);
		assertTrue(api.reserve(actual.rails.get("a"), RED, 88, true));
		assertReservationWindowsEqual(expected, actual);

		final Fixture expectedManual = fixture();
		final Fixture actualManual = fixture();
		final LongArrayList manualColors = new LongArrayList();
		expectedManual.rails.get("a").blockRail(manualColors);
		actualManual.rails.get("a").blockRail(manualColors);
		invoke(expectedManual.rails.get("a"), "tick2", new Class<?>[]{long.class}, 1L);
		final LongArrayList actualManualColors = (LongArrayList) field(actualManual.rails.get("a"), "manualBlockColors");
		for (int i = 0; i < actualManualColors.size(); i++) {
			assertTrue(api.reserve(actualManual.rails.get("a"), actualManualColors.getLong(i), 0, true));
		}
		assertReservationWindowsEqual(expectedManual, actualManual);
	}

	@Test
	void explicitInvalidationRebuildsAfterSignalAndTopologyChanges() throws Exception {
		final Fixture fixture = fixture();
		final Api api = api();
		final long initialBuildCount = api.buildCount();
		final Object original = api.component(fixture.rails.get("a"), RED);
		assertEquals(initialBuildCount + 1, api.buildCount());
		assertEquals(4, api.size(original));

		final SignalModification removeRed = new SignalModification(fixture.positions.get("p2"), fixture.positions.get("q1"), false);
		removeRed.putColorToRemove((int) RED);
		removeRed.applyModificationToRail(fixture.data, new ObjectArrayList<>());
		api.invalidateAll();
		final Object afterColorChange = api.component(fixture.rails.get("a"), RED);
		assertEquals(initialBuildCount + 2, api.buildCount());
		assertNotSame(original, afterColorChange);
		assertEquals(3, api.size(afterColorChange));
		assertFalse(api.contains(afterColorChange, fixture.rails.get("e")));

		fixture.data.rails.remove(fixture.rails.get("d"));
		fixture.data.sync();
		api.invalidateAll();
		final Object afterSync = api.component(fixture.rails.get("a"), RED);
		assertEquals(initialBuildCount + 3, api.buildCount());
		assertNotSame(afterColorChange, afterSync);
		assertEquals(2, api.size(afterSync));
		assertFalse(api.contains(afterSync, fixture.rails.get("d")));
	}

	@Test
	void asymmetricStaleRailReachabilityDoesNotShareItsComponentIntoTheLiveRail() throws Exception {
		final Fixture expected = staleRootFixture();
		final Fixture actual = staleRootFixture();
		final Api api = api();
		final Rail expectedLive = expected.rails.get("live");
		final Rail actualOldRoot = actual.rails.get("old");
		final Rail actualLive = actual.rails.get("live");

		assertTrue(connectedRails(actualOldRoot).contains(actualLive), "removed old rail keeps its stale outgoing link");
		assertFalse(connectedRails(actualLive).contains(actualOldRoot), "Data.sync rebuilds the live rail without the old rail");

		legacyReserve(expectedLive, RED, 29, true);
		final Object oldRootComponent = api.component(actualOldRoot, RED);
		assertEquals(2, api.size(oldRootComponent));
		final Object liveComponent = api.component(actualLive, RED);
		assertNotSame(oldRootComponent, liveComponent, "a one-way reachable Rail cannot inherit the old root's cache");
		assertEquals(1, api.size(liveComponent));
		assertFalse(api.contains(liveComponent, actualOldRoot));
		assertTrue(api.reserve(actualLive, RED, 29, true));
		assertReservationWindowsEqual(expected, actual);
	}

	@Test
	void largeOneWayReachabilityUsesEpochScopedFallbackWithoutCachingQuadraticMembers() throws Exception {
		final Fixture expected = largeStaleRootFixture();
		final Fixture actual = largeStaleRootFixture();
		final Api api = api();
		final Rail expectedOldRoot = expected.rails.get("old");
		final Rail actualOldRoot = actual.rails.get("old");
		final long initialBuildCount = api.buildCount();

		assertTrue(connectedRails(actualOldRoot).contains(actual.rails.get("live1")));
		assertFalse(connectedRails(actual.rails.get("live1")).contains(actualOldRoot));
		assertTrue(api.reserve(actualOldRoot, RED, 41, true), "the first large component build serves this reservation");
		assertEquals(initialBuildCount + 1, api.buildCount());

		assertFalse(api.reserve(actualOldRoot, RED, 42, false), "later reservations use the old traversal directly");
		assertEquals(initialBuildCount + 1, api.buildCount(), "the fallback marker avoids rebuilding the large member snapshot");
		legacyReserve(expectedOldRoot, RED, 41, true);
		legacyReserve(expectedOldRoot, RED, 42, false);
		legacyReserve(actualOldRoot, RED, 42, false);
		assertReservationWindowsEqual(expected, actual);

		assertEquals(6, api.size(api.component(actualOldRoot, RED)), "component diagnostics still return the actual reachable snapshot");
		assertEquals(initialBuildCount + 2, api.buildCount());

		api.invalidateAll();
		assertTrue(api.reserve(actualOldRoot, RED, 43, true), "epoch invalidation clears the traversal fallback marker");
		legacyReserve(expectedOldRoot, RED, 43, true);
		assertReservationWindowsEqual(expected, actual);
		assertEquals(initialBuildCount + 3, api.buildCount());
	}

	private static Fixture fixture() {
		final Data data = new Data() {		};
		final Map<String, Position> positions = new LinkedHashMap<>();
		positions.put("p0", position(0, 0));
		positions.put("p1", position(1, 0));
		positions.put("p2", position(2, 0));
		positions.put("p3", position(3, 0));
		positions.put("q1", position(1, 1));
		positions.put("q2", position(2, 1));
		positions.put("p10", position(10, 0));
		positions.put("p11", position(11, 0));
		final Map<String, Rail> rails = new LinkedHashMap<>();
		raisAdd(data, rails, "a", positions.get("p0"), positions.get("p1"), RED);
		raisAdd(data, rails, "b", positions.get("p1"), positions.get("p2"), RED, BLUE);
		raisAdd(data, rails, "c", positions.get("p2"), positions.get("p3"), BLUE);
		raisAdd(data, rails, "d", positions.get("p1"), Angle.S, positions.get("q1"), Angle.N, RED);
		raisAdd(data, rails, "e", positions.get("p2"), Angle.SW, positions.get("q1"), Angle.NE, RED);
		raisAdd(data, rails, "isolated", positions.get("p10"), positions.get("p11"), RED);
		data.sync();
		return new Fixture(data, rails, positions);
	}

	private static Fixture staleRootFixture() {
		final Data data = new Data() { };
		final Map<String, Position> positions = new LinkedHashMap<>();
		positions.put("p0", position(20, 0));
		positions.put("p1", position(21, 0));
		positions.put("p2", position(22, 0));
		final Map<String, Rail> rails = new LinkedHashMap<>();
		raisAdd(data, rails, "old", positions.get("p0"), positions.get("p1"), RED);
		raisAdd(data, rails, "live", positions.get("p1"), positions.get("p2"), RED);
		data.sync();
		data.rails.remove(rails.get("old"));
		data.sync();
		return new Fixture(data, rails, positions);
	}

	private static Fixture largeStaleRootFixture() {
		final Data data = new Data() { };
		final Map<String, Position> positions = new LinkedHashMap<>();
		for (int i = 0; i <= 6; i++) {
			positions.put("p" + i, position(40 + i, 0));
		}
		final Map<String, Rail> rails = new LinkedHashMap<>();
		raisAdd(data, rails, "old", positions.get("p0"), positions.get("p1"), RED);
		for (int i = 1; i <= 5; i++) {
			raisAdd(data, rails, "live" + i, positions.get("p" + i), positions.get("p" + (i + 1)), RED);
		}
		data.sync();
		data.rails.remove(rails.get("old"));
		data.sync();
		return new Fixture(data, rails, positions);
	}

	@SuppressWarnings("unchecked")
	private static ObjectOpenHashSet<Rail> connectedRails(Rail rail) throws ReflectiveOperationException {
		final ObjectOpenHashSet<Rail> first = (ObjectOpenHashSet<Rail>) field(rail, "connectedRails1");
		final ObjectOpenHashSet<Rail> second = (ObjectOpenHashSet<Rail>) field(rail, "connectedRails2");
		final ObjectOpenHashSet<Rail> result = new ObjectOpenHashSet<>();
		result.addAll(first);
		result.addAll(second);
		return result;
	}

	private static void raisAdd(Data data, Map<String, Rail> rails, String name, Position start, Position end, long... colors) {
		raisAdd(data, rails, name, start, Angle.E, end, Angle.W, colors);
	}

	private static void raisAdd(Data data, Map<String, Rail> rails, String name, Position start, Angle startAngle, Position end, Angle endAngle, long... colors) {
		final Rail rail = Rail.newRail(start, startAngle, end, endAngle, Rail.Shape.QUADRATIC, 0,
				new ObjectArrayList<>(), 80, 80, false, false, true, false, true, TransportMode.TRAIN);
		Assertions.assertTrue(rail.isValid(), "old core rejected test rail " + name);
		for (final long color : colors) {
			final SignalModification modification = new SignalModification(start, end, false);
			modification.putColorToAdd((int) color);
			rail.applyModification(modification);
		}
		data.rails.add(rail);
		rails.put(name, rail);
	}

	private static Position position(long x, long z) {
		return new Position(x, 64, z);
	}

	private static void legacyReserve(Rail rail, long color, long vehicleId, boolean current) throws Exception {
		final Method method = Rail.class.getDeclaredMethod("reserveRail", long.class, long.class, ObjectOpenHashSet.class, Rail.class, boolean.class);
		method.setAccessible(true);
		method.invoke(null, vehicleId, color, new ObjectOpenHashSet<Rail>(), rail, current);
	}

	private static void assertReservationWindowsEqual(Fixture expected, Fixture actual) throws Exception {
		for (final String name : expected.rails.keySet()) {
			final Rail expectedRail = expected.rails.get(name);
			final Rail actualRail = actual.rails.get(name);
			for (final String mapName : List.of("preBlockedVehicleIds", "currentlyBlockedVehicleIds", "preBlockedVehicleIdsOld", "currentlyBlockedVehicleIdsOld")) {
				assertEquals(snapshot((Long2LongAVLTreeMap) field(expectedRail, mapName)), snapshot((Long2LongAVLTreeMap) field(actualRail, mapName)), name + "/" + mapName);
			}
		}
	}

	private static Map<Long, Long> snapshot(Long2LongAVLTreeMap values) {
		final Map<Long, Long> result = new TreeMap<>();
		values.forEach((key, value) -> result.put(key, value));
		return result;
	}

	private static Object field(Object instance, String name) throws ReflectiveOperationException {
		Class<?> current = instance.getClass();
		while (current != null) {
			try {
				final Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(instance);
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static Object invoke(Object instance, String name, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
		final Method method = instance.getClass().getMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(instance, args);
	}

	private static Api api() throws Exception {
		final Class<?> helper;
		try {
			helper = Class.forName(HELPER_NAME);
		} catch (ClassNotFoundException e) {
			Assertions.fail("old-core signal component cache is not implemented yet", e);
			throw e;
		}
		final Class<?> accessType = Class.forName(HELPER_NAME + "$RailAccess");
		final Class<?> cacheType = Class.forName(HELPER_NAME + "$RailCache");
		final Constructor<?> cacheConstructor = cacheType.getConstructor();
		final IdentityHashMap<Rail, Object> cacheByRail = new IdentityHashMap<>();
		final InvocationHandler handler = (proxy, method, args) -> {
			final Rail rail = args == null || args.length == 0 ? null : (Rail) args[0];
			switch (method.getName()) {
				case "hasSignalColor":
					return ((LongArrayList) field(rail, "signalColors")).contains((long) args[1]);
				case "connectedRails1":
					return field(rail, "connectedRails1");
				case "connectedRails2":
					return field(rail, "connectedRails2");
				case "cacheFor":
					Object cache = cacheByRail.get(rail);
					if (cache == null) {
						cache = cacheConstructor.newInstance();
						cacheByRail.put(rail, cache);
					}
					return cache;
				case "writeReservation":
					final long color = (long) args[1];
					final long owner = (long) args[2];
					final boolean current = (boolean) args[3];
					final String mapName = current ? "currentlyBlockedVehicleIds" : "preBlockedVehicleIds";
					((Long2LongAVLTreeMap) field(rail, mapName)).put(color, owner);
					return null;
				case "toString":
					return "TestRailAccess";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				default:
					throw new AssertionError("unexpected RailAccess method: " + method);
			}
		};
		final Object access = Proxy.newProxyInstance(accessType.getClassLoader(), new Class<?>[]{accessType}, handler);
		return new Api(helper, accessType, access);
	}

	private static final class Api {
		private final Method component;
		private final Method reserve;
		private final Method invalidate;
		private final Method buildCount;
		private final Method size;
		private final Method containsIdentity;
		private final Object access;

		private Api(Class<?> helper, Class<?> accessType, Object access) throws ReflectiveOperationException {
			this.component = helper.getMethod("component", Rail.class, long.class, accessType);
			this.reserve = helper.getMethod("reserveComponent", Rail.class, long.class, long.class, boolean.class, accessType);
			this.invalidate = helper.getMethod("invalidateAll");
			this.buildCount = helper.getMethod("componentBuildCount");
			final Class<?> componentType = this.component.getReturnType();
			this.size = componentType.getMethod("size");
			this.containsIdentity = componentType.getMethod("containsIdentity", Rail.class);
			this.access = access;
		}

		private Object component(Rail rail, long color) throws ReflectiveOperationException {
			return component.invoke(null, rail, color, access);
		}

		private boolean reserve(Rail rail, long color, long owner, boolean current) throws ReflectiveOperationException {
			return (boolean) reserve.invoke(null, rail, color, owner, current, access);
		}

		private void invalidateAll() throws ReflectiveOperationException {
			invalidate.invoke(null);
		}

		private long buildCount() throws ReflectiveOperationException {
			return (long) buildCount.invoke(null);
		}

		private int size(Object component) throws ReflectiveOperationException {
			return (int) size.invoke(component);
		}

		private boolean contains(Object component, Rail rail) throws ReflectiveOperationException {
			return (boolean) containsIdentity.invoke(component, rail);
		}
	}

	private static final class Fixture {
		private final Data data;
		private final Map<String, Rail> rails;
		private final Map<String, Position> positions;

		private Fixture(Data data, Map<String, Rail> rails, Map<String, Position> positions) {
			this.data = data;
			this.rails = rails;
			this.positions = positions;
		}
	}
}
