package org.mtr.mod.data;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the checked-in, untransformed core as an independent behavioral oracle. */
final class OldCoreOracle implements AutoCloseable {
	private static final String CORE_JAR = "Transport-Simulation-Core-0.0.1.jar";
	private static final String UTIL_JAR = "Shadow-Libraries-util-0.0.1.jar";
	private static final String NET_JAR = "Shadow-Libraries-net-0.0.1.jar";
	private static final String[] RESERVATION_FIELDS = {
			"preBlockedVehicleIds", "currentlyBlockedVehicleIds", "preBlockedVehicleIdsOld", "currentlyBlockedVehicleIdsOld"
	};

	private final URLClassLoader classLoader;
	private final Class<?> positionClass;
	private final Constructor<?> positionConstructor;
	private final Class<?> railClass;
	private final Class<?> railMapClass;
	private final Class<?> signalColorListClass;
	private final Class<?> reservationClass;
	private final Method newRailMethod;
	private final Method writePositionsMethod;
	private final Method writeConnectedRailsMethod;
	private final Method isBlockedMethod;

	private OldCoreOracle(URLClassLoader classLoader) throws ReflectiveOperationException {
		this.classLoader = classLoader;
		positionClass = Class.forName("org.mtr.core.data.Position", true, classLoader);
		positionConstructor = positionClass.getConstructor(long.class, long.class, long.class);
		railClass = Class.forName("org.mtr.core.data.Rail", true, classLoader);
		railMapClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap", true, classLoader);
		signalColorListClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList", true, classLoader);
		reservationClass = Class.forName("org.mtr.core.data.Rail$BlockReservation", true, classLoader);
		final Class<?> angleClass = Class.forName("org.mtr.core.tool.Angle", true, classLoader);
		final Class<?> shapeClass = Class.forName("org.mtr.core.data.Rail$Shape", true, classLoader);
		final Class<?> transportModeClass = Class.forName("org.mtr.core.data.TransportMode", true, classLoader);
		final Class<?> stylesClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList", true, classLoader);
		newRailMethod = railClass.getMethod("newRail", positionClass, angleClass, positionClass, angleClass, shapeClass,
			double.class, stylesClass, long.class, long.class, boolean.class, boolean.class, boolean.class, boolean.class,
			boolean.class, transportModeClass);
		writePositionsMethod = railClass.getDeclaredMethod("writePositionsToRailCache", railMapClass);
		writeConnectedRailsMethod = railClass.getDeclaredMethod("writeConnectedRailsCacheFromMap", railMapClass);
		isBlockedMethod = railClass.getDeclaredMethod("isBlocked", long.class, reservationClass);
		writePositionsMethod.setAccessible(true);
		writeConnectedRailsMethod.setAccessible(true);
		isBlockedMethod.setAccessible(true);
	}

	static OldCoreOracle open() throws Exception {
		final Path libDirectory = findLibraryDirectory();
		final URL[] jars = {
				libDirectory.resolve(CORE_JAR).toUri().toURL(),
				libDirectory.resolve(UTIL_JAR).toUri().toURL(),
				libDirectory.resolve(NET_JAR).toUri().toURL()
		};
		return new OldCoreOracle(new URLClassLoader(jars, ClassLoader.getPlatformClassLoader()));
	}

	private static Path findLibraryDirectory() throws IOException {
		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (directory != null) {
			final Path candidate = directory.resolve("libs");
			if (Files.isRegularFile(candidate.resolve(CORE_JAR)) && Files.isRegularFile(candidate.resolve(UTIL_JAR)) &&
					Files.isRegularFile(candidate.resolve(NET_JAR))) {
				return candidate;
			}
			directory = directory.getParent();
		}
		throw new IOException("Could not locate the checked-in old core and its shaded libraries above " + System.getProperty("user.dir"));
	}

	SignalGraph signalGraph(long[][] endpoints, long[][] colors) throws Exception {
		if (endpoints.length != colors.length) {
			throw new IllegalArgumentException("Each rail endpoint tuple must have one signal-color tuple");
		}
		return new SignalGraph(endpoints, colors);
	}

	/** Runs the exact old VehiclePosition implementation on plain segment values. */
	double closestVehicleOverlap(List<VehicleSegment> segments, double start, double end, boolean reverse, long vehicleId) throws Exception {
		final Class<?> vehiclePositionClass = Class.forName("org.mtr.core.data.VehiclePosition", true, classLoader);
		final Object position = vehiclePositionClass.getConstructor().newInstance();
		final Method addSegment = vehiclePositionClass.getMethod("addSegment", double.class, double.class, long.class);
		for (final VehicleSegment segment : segments) {
			addSegment.invoke(position, segment.startDistance(), segment.endDistance(), segment.vehicleId());
		}
		return (double) vehiclePositionClass.getMethod("getClosestOverlap", double.class, double.class, boolean.class, long.class)
			.invoke(position, start, end, reverse, vehicleId);
	}

	/** Keeps IDs as longs so oracle checks include the full signed 64-bit domain. */
	record VehicleSegment(double startDistance, double endDistance, long vehicleId) {
	}

	@Override
	public void close() throws IOException {
		classLoader.close();
	}

	final class SignalGraph {
		private final Object[] rails;
		private final Object[][] positions;
		private final Object positionsToRail;

		private SignalGraph(long[][] endpoints, long[][] colors) throws Exception {
			rails = new Object[endpoints.length];
			positions = new Object[endpoints.length][2];
			positionsToRail = railMapClass.getConstructor().newInstance();
			final Class<?> angleClass = Class.forName("org.mtr.core.tool.Angle", true, classLoader);
			final Class<?> shapeClass = Class.forName("org.mtr.core.data.Rail$Shape", true, classLoader);
			final Class<?> transportModeClass = Class.forName("org.mtr.core.data.TransportMode", true, classLoader);
			final Class<?> stylesClass = Class.forName("org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList", true, classLoader);
			final Object shape = shapeClass.getMethod("valueOf", String.class).invoke(null, "QUADRATIC");
			final Object train = transportModeClass.getField("TRAIN").get(null);
			final Method angleFromAngle = angleClass.getMethod("fromAngle", float.class);
			final Constructor<?> stylesConstructor = stylesClass.getConstructor();
			final Method isValidMethod = railClass.getMethod("isValid");
			for (int i = 0; i < endpoints.length; i++) {
				if (endpoints[i].length != 4) {
					throw new IllegalArgumentException("Each rail endpoint tuple must be [x1, z1, x2, z2]");
				}
				final long x1 = endpoints[i][0];
				final long z1 = endpoints[i][1];
				final long x2 = endpoints[i][2];
				final long z2 = endpoints[i][3];
				positions[i][0] = positionConstructor.newInstance(x1, 64L, z1);
				positions[i][1] = positionConstructor.newInstance(x2, 64L, z2);
				final float angle = (float) Math.toDegrees(Math.atan2(z2 - z1, x2 - x1));
				final Object startAngle = angleFromAngle.invoke(null, angle);
				final Object endAngle = angleFromAngle.invoke(null, angle + 180.0F);
				final Object rail = newRailMethod.invoke(null, positions[i][0], startAngle, positions[i][1], endAngle,
					shape, 0.0, stylesConstructor.newInstance(), 80L, 80L, false, true, true, false, false, train);
				if (!(boolean) isValidMethod.invoke(rail)) {
					throw new AssertionError("The old core rejected rail fixture " + i + " at [" + x1 + "," + z1 + "]- [" + x2 + "," + z2 + "]");
				}
				rails[i] = rail;
				setColors(i, colors[i]);
				writePositionsMethod.invoke(rail, positionsToRail);
			}
			rebuildConnections();
		}

		int railCount() {
			return rails.length;
		}

		Object rail(int index) {
			return rails[index];
		}

		void setColors(int railIndex, long... colors) throws Exception {
			final Object list = findField(rails[railIndex].getClass(), "signalColors").get(rails[railIndex]);
			list.getClass().getMethod("clear").invoke(list);
			final Method add = signalColorListClass.getMethod("add", long.class);
			for (final long color : colors) {
				add.invoke(list, color);
			}
		}

		void modifyColor(int railIndex, int colorToRemove, int colorToAdd) throws Exception {
			final Class<?> modificationClass = Class.forName("org.mtr.core.data.SignalModification", true, classLoader);
			final Constructor<?> constructor = modificationClass.getConstructor(positionClass, positionClass, boolean.class);
			final Object modification = constructor.newInstance(positions[railIndex][0], positions[railIndex][1], false);
			modificationClass.getMethod("putColorToRemove", int.class).invoke(modification, colorToRemove);
			modificationClass.getMethod("putColorToAdd", int.class).invoke(modification, colorToAdd);
			railClass.getMethod("applyModification", modificationClass).invoke(rails[railIndex], modification);
		}

		void rebuildConnections() throws Exception {
			railMapClass.getMethod("clear").invoke(positionsToRail);
			for (final Object rail : rails) {
				writePositionsMethod.invoke(rail, positionsToRail);
			}
			for (final Object rail : rails) {
				writeConnectedRailsMethod.invoke(rail, positionsToRail);
			}
		}

		void blockRail(int railIndex, long... colors) throws Exception {
			final Object colorList = signalColorListClass.getConstructor().newInstance();
			final Method add = signalColorListClass.getMethod("add", long.class);
			for (final long color : colors) {
				add.invoke(colorList, color);
			}
			railClass.getMethod("blockRail", signalColorListClass).invoke(rails[railIndex], colorList);
		}

		void tick2(int railIndex, long elapsedMillis) throws Exception {
			railClass.getMethod("tick2", long.class).invoke(rails[railIndex], elapsedMillis);
		}

		boolean isBlocked(int railIndex, long vehicleId, String reservation) throws Exception {
			final Object value = reservationClass.getMethod("valueOf", String.class).invoke(null, reservation);
			return (boolean) isBlockedMethod.invoke(rails[railIndex], vehicleId, value);
		}

		void setReservation(int railIndex, String windowName, long color, long owner) throws Exception {
			final Object map = findField(rails[railIndex].getClass(), windowName).get(rails[railIndex]);
			map.getClass().getMethod("put", long.class, long.class).invoke(map, color, owner);
		}

		void clearReservations() throws Exception {
			for (final Object rail : rails) {
				for (final String fieldName : RESERVATION_FIELDS) {
					final Object map = findField(rail.getClass(), fieldName).get(rail);
					map.getClass().getMethod("clear").invoke(map);
				}
			}
		}

		List<RailReservations> reservations() throws Exception {
			final List<RailReservations> result = new ArrayList<>(rails.length);
			for (final Object rail : rails) {
				final Map<String, Map<Long, Long>> windows = new LinkedHashMap<>();
				for (final String fieldName : RESERVATION_FIELDS) {
					final Object map = findField(rail.getClass(), fieldName).get(rail);
					windows.put(fieldName, copyLongMap(map));
				}
				result.add(new RailReservations(windows));
			}
			return result;
		}

		private Map<Long, Long> copyLongMap(Object source) throws Exception {
			final Map<Long, Long> result = new LinkedHashMap<>();
			for (final Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet()) {
				final long key = ((Number) entry.getKey()).longValue();
				final long value = ((Number) entry.getValue()).longValue();
				result.put(key, value);
			}
			return result;
		}
	}

	static final class RailReservations {
		private final Map<String, Map<Long, Long>> windows;

		private RailReservations(Map<String, Map<Long, Long>> windows) {
			this.windows = windows;
		}

		Map<Long, Long> window(String fieldName) {
			return windows.get(fieldName);
		}

		Map<String, Map<Long, Long>> windows() {
			return windows;
		}
	}

	private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				final Field field = current.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// Continue through the schema base classes.
			}
		}
		throw new NoSuchFieldException(type.getName() + "." + fieldName);
	}
}
