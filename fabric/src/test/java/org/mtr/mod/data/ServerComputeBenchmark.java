package org.mtr.mod.data;

import com.sun.management.ThreadMXBean;
import org.mtr.core.data.*;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Optional synthetic measurements inside the same transformed Fabric process as the behavior checks. */
final class ServerComputeBenchmark {
	private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static volatile long blackhole;

	static void run() throws Exception {
		MEMORY.setThreadAllocatedMemoryEnabled(true);
		benchmarkVehicles(1);
		benchmarkVehicles(512);
		try (OldCoreOracle oracle = OldCoreOracle.open()) {
			for (final int members : new int[]{1, 16, 64, 256}) {
				benchmarkSignals(oracle, members);
			}
		}
		System.out.println("MTR_COMPUTE_BENCHMARK_DONE blackhole=" + blackhole);
	}

	private static void benchmarkVehicles(int groups) throws Exception {
		final Method write = Vehicle.class.getDeclaredMethod("writeVehiclePositions", int.class, Object2ObjectAVLTreeMap.class);
		final Method query = Vehicle.class.getDeclaredMethod("railBlockedDistance", int.class, double.class, double.class, ObjectArrayList.class, boolean.class, boolean.class);
		write.setAccessible(true);
		query.setAccessible(true);
		final Vehicle[] vehicles = new Vehicle[groups * 2];
		for (int group = 0; group < groups; group++) {
			final long offset = group * 128L;
			final PathData first = path(rail(offset, offset + 32), offset, offset + 32, 0, 32);
			final PathData second = path(rail(offset + 32, offset + 64), offset + 32, offset + 64, 32, 64);
			final PathData third = path(rail(offset + 64, offset + 96), offset + 64, offset + 96, 64, 96);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("benchmark", 12, 2, 3, 9, 0.5, 0.5));
			final VehicleExtraData extra = VehicleExtraData.create(1, 2, 32, cars, one(first), one(second), one(third), third, false, 0.1, 0.1, false, 80, 0);
			for (int index = 0; index < 2; index++) {
				final Vehicle vehicle = new Vehicle(extra, null, TransportMode.TRAIN, new ClientData());
				field(Vehicle.class, "id").setLong(vehicle, 1000L + group * 2L + index);
				field(Vehicle.class, "railProgress").setDouble(vehicle, index == 0 ? 60 : 42);
				vehicles[group * 2 + index] = vehicle;
			}
		}
		final VehicleWorkload old = new VehicleWorkload(vehicles, write, query, false);
		final VehicleWorkload compact = new VehicleWorkload(vehicles, write, query, true);
		if (old.run() != compact.run()) {
			throw new AssertionError("Native Vehicle write/query benchmark results differ");
		}
		compare("native Vehicle write/query vehicles=" + vehicles.length, old, compact, groups == 1 ? 1000 : 60, groups == 1 ? 1000 : 30);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void benchmarkSignals(OldCoreOracle oracle, int members) throws Exception {
		final long[][] endpoints = new long[members][4];
		final long[][] colors = new long[members][1];
		final Data data = new ClientData();
		Rail root = null;
		for (int i = 0; i < members; i++) {
			endpoints[i] = new long[]{i * 32L, 0, (i + 1) * 32L, 0};
			colors[i][0] = 42;
			final Rail rail = rail(i * 32L, (i + 1) * 32L);
			((LongArrayList) field(Rail.class, "signalColors").get(rail)).add(42);
			data.rails.add(rail);
			if (root == null) {
				root = rail;
			}
		}
		data.sync();
		final OldCoreOracle.SignalGraph oldGraph = oracle.signalGraph(endpoints, colors);
		final Object oldRoot = oldGraph.rail(0);
		final Class<?> oldReservationType = Class.forName("org.mtr.core.data.Rail$BlockReservation", true, oldRoot.getClass().getClassLoader());
		final Object oldReservation = Enum.valueOf((Class) oldReservationType, "PRE_RESERVE");
		final Method oldReserve = oldRoot.getClass().getDeclaredMethod("isBlocked", long.class, oldReservationType);
		final Method reserve = Rail.class.getDeclaredMethod("isBlocked", long.class, Rail.BlockReservation.class);
		oldReserve.setAccessible(true);
		reserve.setAccessible(true);
		final Rail currentRoot = root;
		final Workload old = () -> (boolean) oldReserve.invoke(oldRoot, 123L, oldReservation) ? 1 : 0;
		final Workload cached = () -> (boolean) reserve.invoke(currentRoot, 123L, Rail.BlockReservation.PRE_RESERVE) ? 1 : 0;
		if (old.run() != cached.run()) {
			throw new AssertionError("Native signal reservation benchmark results differ");
		}
		compare("native Rail.isBlocked component=" + members, old, cached, 1000, 2000);
	}

	private static void compare(String label, Workload old, Workload optimized, int warmup, int operations) throws Exception {
		for (int i = 0; i < warmup; i++) {
			blackhole ^= old.run();
			blackhole ^= optimized.run();
		}
		final long[] oldTimes = new long[7];
		final long[] newTimes = new long[7];
		final long[] oldBytes = new long[7];
		final long[] newBytes = new long[7];
		for (int round = 0; round < 7; round++) {
			final long[] first = measure((round & 1) == 0 ? old : optimized, operations);
			final long[] second = measure((round & 1) == 0 ? optimized : old, operations);
			oldTimes[round] = ((round & 1) == 0 ? first : second)[0];
			oldBytes[round] = ((round & 1) == 0 ? first : second)[1];
			newTimes[round] = ((round & 1) == 0 ? second : first)[0];
			newBytes[round] = ((round & 1) == 0 ? second : first)[1];
		}
		System.out.printf("MTR_COMPUTE_BENCH %s old=%d ns/op,%d B/op new=%d ns/op,%d B/op speedup=%.2fx%n", label,
				median(oldTimes), median(oldBytes), median(newTimes), median(newBytes), (double) median(oldTimes) / median(newTimes));
	}

	private static long[] measure(Workload workload, int operations) throws Exception {
		final long thread = Thread.currentThread().getId();
		final long allocated = MEMORY.getThreadAllocatedBytes(thread);
		final long start = System.nanoTime();
		long result = 0;
		for (int i = 0; i < operations; i++) {
			result ^= workload.run();
		}
		final long elapsed = System.nanoTime() - start;
		final long bytes = MEMORY.getThreadAllocatedBytes(thread) - allocated;
		blackhole ^= result;
		return new long[]{elapsed / operations, bytes / operations};
	}

	private static long median(long[] values) {
		Arrays.sort(values);
		return values[values.length / 2];
	}

	private static Rail rail(long first, long second) {
		return Rail.newRail(new Position(first, 64, 0), Angle.E, new Position(second, 64, 0), Angle.W,
				Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, true, true, false, false, TransportMode.TRAIN);
	}

	private static PathData path(Rail rail, long first, long second, double start, double end) {
		final Position firstPosition = new Position(first, 64, 0);
		final Position secondPosition = new Position(second, 64, 0);
		return new PathData(rail, 1, 0, 0, start, end, firstPosition, rail.getStartAngle(firstPosition), secondPosition, rail.getStartAngle(secondPosition));
	}

	private static ObjectArrayList<PathData> one(PathData value) {
		final ObjectArrayList<PathData> list = new ObjectArrayList<>();
		list.add(value);
		return list;
	}

	private static Field field(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				final Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(name);
	}

	@FunctionalInterface
	private interface Workload {
		long run() throws Exception;
	}

	private static final class VehicleWorkload implements Workload {
		private final Vehicle[] vehicles;
		private final Method write;
		private final Method query;
		private final boolean compact;
		private final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> windows = new ObjectArrayList<>();

		private VehicleWorkload(Vehicle[] vehicles, Method write, Method query, boolean compact) {
			this.vehicles = vehicles;
			this.write = write;
			this.query = query;
			this.compact = compact;
			windows.add(compact ? new VehicleOccupancyMap() : new Object2ObjectAVLTreeMap<>());
			windows.add(compact ? new VehicleOccupancyMap() : new Object2ObjectAVLTreeMap<>());
		}

		@Override
		public long run() throws Exception {
			if (compact) {
				((VehicleOccupancyMap) windows.get(1)).resetForNextTick();
			} else {
				windows.set(1, new Object2ObjectAVLTreeMap<>());
			}
			for (final Vehicle vehicle : vehicles) {
				write.invoke(vehicle, 1, windows.get(1));
			}
			long result = 0;
			for (int i = 1; i < vehicles.length; i += 2) {
				final double blocked = (double) query.invoke(vehicles[i], 1, 42.0, 16.0, windows, false, true);
				result = 31 * result + Double.doubleToLongBits(blocked);
			}
			if (compact && ((VehicleOccupancyMap) windows.get(1)).isCompatibilityMode()) {
				throw new AssertionError("Native Vehicle hot path bypassed the compact-map mixin");
			}
			return result;
		}
	}
}
