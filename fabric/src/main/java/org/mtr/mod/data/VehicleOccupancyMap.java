package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectSortedMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSortedSet;

import java.io.ObjectStreamException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Compact per-time-layer vehicle occupancy storage with the legacy nested-map type at its boundary.
 *
 * <p>Vehicle's pinned core API still accepts an AVL map. The mixin routes its hot writes and reads
 * through this class, where endpoint pairs receive local rail IDs and occupied ranges live in
 * primitive arrays. If another mod reads the map through its normal Map API, the current contents
 * are materialized into the original nested shape and this instance remains in compatibility mode
 * for its lifetime.</p>
 */
public final class VehicleOccupancyMap extends Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> {
	private static final long serialVersionUID = 1L;
	private transient RailIndex railIndex = new RailIndex();
	private boolean compatibilityMode;
	private boolean clearRegistryOnReset;

	public VehicleOccupancyMap() {
	}

	/** Appends a range through the callback used by the pinned core's {@code Data.put} call. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void record(Map positions, Object firstPosition, Object secondPosition,
			Function<VehiclePosition, VehiclePosition> putValue,
			Supplier<Object2ObjectAVLTreeMap<Position, VehiclePosition>> innerMapSupplier) {
		if (positions instanceof VehicleOccupancyMap occupancyMap) {
			occupancyMap.record((Position) firstPosition, (Position) secondPosition, putValue, innerMapSupplier);
		} else {
			final Map<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> typedPositions = (Map) positions;
			Data.put(typedPositions, (Position) firstPosition, (Position) secondPosition, putValue, innerMapSupplier);
		}
	}

	/** Looks up a rail pair while keeping ordinary maps and third-party map implementations supported. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static VehiclePosition get(Map positions, Object firstPosition, Object secondPosition) {
		if (positions instanceof VehicleOccupancyMap occupancyMap) {
			return occupancyMap.getOccupancy((Position) firstPosition, (Position) secondPosition);
		}
		return (VehiclePosition) Data.tryGet(positions, firstPosition, secondPosition);
	}

	/** Clears occupied intervals for a retired rolling buffer while reusing its rail IDs and arrays. */
	public VehicleOccupancyMap resetForNextTick() {
		super.clear();
		if (clearRegistryOnReset) {
			railIndex.clearRegistry();
			clearRegistryOnReset = false;
		} else {
			railIndex.clearIntervals();
		}
		return this;
	}

	/** Defers clearing endpoint-to-ID assignments until this buffer is retired by the simulator. */
	public void invalidateTopology() {
		clearRegistryOnReset = true;
	}

	boolean isCompatibilityMode() {
		return compatibilityMode;
	}

	int compactIntervalCount() {
		return compatibilityMode ? 0 : railIndex.intervalCount;
	}

	private void record(Position first, Position second, Function<VehiclePosition, VehiclePosition> putValue,
			Supplier<Object2ObjectAVLTreeMap<Position, VehiclePosition>> innerMapSupplier) {
		Objects.requireNonNull(first, "firstPosition");
		Objects.requireNonNull(second, "secondPosition");
		if (compatibilityMode) {
			Data.put(this, first, second, putValue, innerMapSupplier);
			return;
		}

		final int railId = railIndex.getOrCreate(first, second);
		final CompactVehiclePosition oldValue = railIndex.vehiclePositions[railId];
		final VehiclePosition newValue = putValue.apply(oldValue);
		if (newValue != oldValue) {
			throw new IllegalStateException("The pinned core replaced a compact VehiclePosition while recording an occupied range");
		}
	}

	private VehiclePosition getOccupancy(Position first, Position second) {
		if (compatibilityMode) {
			final Object2ObjectAVLTreeMap<Position, VehiclePosition> innerMap = super.get(first);
			return innerMap == null ? null : innerMap.get(second);
		}
		final int railId = railIndex.find(first, second);
		return railId < 0 || railIndex.heads[railId] < 0 ? null : railIndex.vehiclePositions[railId];
	}

	/** First ordinary Map read materializes a complete legacy snapshot before exposing inherited views. */
	private void ensureCompatibilityMode() {
		if (compatibilityMode) {
			return;
		}

		for (int railId = 0; railId < railIndex.railCount; railId++) {
			if (railIndex.heads[railId] < 0) {
				continue;
			}
			final Position first = railIndex.firstPositions[railId];
			final Position second = railIndex.secondPositions[railId];
			Object2ObjectAVLTreeMap<Position, VehiclePosition> innerMap;
			if (!super.containsKey(first)) {
				innerMap = new Object2ObjectAVLTreeMap<>();
				super.put(first, innerMap);
			} else {
				innerMap = super.get(first);
				if (innerMap == null) {
					innerMap = new Object2ObjectAVLTreeMap<>();
					super.put(first, innerMap);
				}
			}
			innerMap.put(second, railIndex.copyVehiclePosition(railId));
		}
		compatibilityMode = true;
		// The compact representation is no longer queried. Dropping it avoids retaining the old rail graph.
		railIndex = new RailIndex();
	}

	@Override
	public Object2ObjectAVLTreeMap<Position, VehiclePosition> get(Object key) {
		ensureCompatibilityMode();
		return super.get(key);
	}

	@Override
	public Object2ObjectAVLTreeMap<Position, VehiclePosition> put(Position key, Object2ObjectAVLTreeMap<Position, VehiclePosition> value) {
		ensureCompatibilityMode();
		return super.put(key, value);
	}

	@Override
	public Object2ObjectAVLTreeMap<Position, VehiclePosition> remove(Object key) {
		ensureCompatibilityMode();
		return super.remove(key);
	}

	@Override
	public void putAll(Map<? extends Position, ? extends Object2ObjectAVLTreeMap<Position, VehiclePosition>> map) {
		ensureCompatibilityMode();
		super.putAll(map);
	}

	@Override
	public boolean containsKey(Object key) {
		ensureCompatibilityMode();
		return super.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		ensureCompatibilityMode();
		return super.containsValue(value);
	}

	@Override
	public int size() {
		ensureCompatibilityMode();
		return super.size();
	}

	@Override
	public boolean isEmpty() {
		ensureCompatibilityMode();
		return super.isEmpty();
	}

	@Override
	public Position firstKey() {
		ensureCompatibilityMode();
		return super.firstKey();
	}

	@Override
	public Position lastKey() {
		ensureCompatibilityMode();
		return super.lastKey();
	}

	@Override
	public ObjectSortedSet<Object2ObjectMap.Entry<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> object2ObjectEntrySet() {
		ensureCompatibilityMode();
		return super.object2ObjectEntrySet();
	}

	@Override
	public ObjectSortedSet<Position> keySet() {
		ensureCompatibilityMode();
		return super.keySet();
	}

	@Override
	public ObjectCollection<Object2ObjectAVLTreeMap<Position, VehiclePosition>> values() {
		ensureCompatibilityMode();
		return super.values();
	}

	@Override
	public ObjectSortedSet<Map.Entry<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> entrySet() {
		ensureCompatibilityMode();
		return super.entrySet();
	}

	@Override
	public Object2ObjectSortedMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> headMap(Position to) {
		ensureCompatibilityMode();
		return super.headMap(to);
	}

	@Override
	public Object2ObjectSortedMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> tailMap(Position from) {
		ensureCompatibilityMode();
		return super.tailMap(from);
	}

	@Override
	public Object2ObjectSortedMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> subMap(Position from, Position to) {
		ensureCompatibilityMode();
		return super.subMap(from, to);
	}

	@Override
	public Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> clone() {
		ensureCompatibilityMode();
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> clone = super.clone();
		if (clone instanceof VehicleOccupancyMap occupancyClone) {
			occupancyClone.railIndex = occupancyClone.new RailIndex();
		}
		return clone;
	}

	@Override
	public Comparator<? super Position> comparator() {
		ensureCompatibilityMode();
		return super.comparator();
	}

	@Override
	public void defaultReturnValue(Object2ObjectAVLTreeMap<Position, VehiclePosition> defaultReturnValue) {
		ensureCompatibilityMode();
		super.defaultReturnValue(defaultReturnValue);
	}

	@Override
	public int hashCode() {
		ensureCompatibilityMode();
		return super.hashCode();
	}

	@Override
	public boolean equals(Object other) {
		ensureCompatibilityMode();
		return super.equals(other);
	}

	@Override
	public String toString() {
		ensureCompatibilityMode();
		return super.toString();
	}

	@Override
	public void clear() {
		ensureCompatibilityMode();
		super.clear();
		railIndex.clearRegistry();
		clearRegistryOnReset = false;
	}

	private Object writeReplace() throws ObjectStreamException {
		ensureCompatibilityMode();
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> replacement = new Object2ObjectAVLTreeMap<>();
		replacement.defaultReturnValue(super.defaultReturnValue());
		for (Object2ObjectMap.Entry<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> outerEntry : super.object2ObjectEntrySet()) {
			// Keep inner defaults, subclasses and shared-value identity, just as normal AVL serialization does.
			replacement.put(outerEntry.getKey(), outerEntry.getValue());
		}
		return replacement;
	}

	private final class CompactVehiclePosition extends VehiclePosition {
		private final int railId;

		private CompactVehiclePosition(int railId) {
			this.railId = railId;
		}

		@Override
		public void addSegment(double startDistance, double endDistance, long vehicleId) {
			railIndex.append(railId, startDistance, endDistance, vehicleId);
		}

		@Override
		public double getClosestOverlap(double startDistance, double endDistance, boolean reversePositions, long vehicleId) {
			return railIndex.getClosestOverlap(railId, startDistance, endDistance, reversePositions, vehicleId);
		}
	}

	private final class RailIndex {
		private static final int INITIAL_CAPACITY = 16;
		private static final int INITIAL_INTERVAL_CAPACITY = 32;

		private Position[] firstPositions = new Position[INITIAL_CAPACITY];
		private Position[] secondPositions = new Position[INITIAL_CAPACITY];
		private CompactVehiclePosition[] vehiclePositions = new CompactVehiclePosition[INITIAL_CAPACITY];
		private int[] heads = new int[INITIAL_CAPACITY];
		private int[] tails = new int[INITIAL_CAPACITY];
		private int[] hashTable = new int[INITIAL_CAPACITY * 2];
		private int railCount;

		private int[] touchedRails = new int[INITIAL_CAPACITY];
		private int touchedRailCount;
		private double[] startDistances = new double[INITIAL_INTERVAL_CAPACITY];
		private double[] endDistances = new double[INITIAL_INTERVAL_CAPACITY];
		private long[] vehicleIds = new long[INITIAL_INTERVAL_CAPACITY];
		private int[] nextIntervals = new int[INITIAL_INTERVAL_CAPACITY];
		private int intervalCount;

		private RailIndex() {
			Arrays.fill(heads, -1);
			Arrays.fill(tails, -1);
		}

		private int find(Position first, Position second) {
			if (first == null || second == null) {
				return -1;
			}
			int slot = hash(first, second) & (hashTable.length - 1);
			while (hashTable[slot] != 0) {
				final int railId = hashTable[slot] - 1;
				if (firstPositions[railId].equals(first) && secondPositions[railId].equals(second)) {
					return railId;
				}
				slot = (slot + 1) & (hashTable.length - 1);
			}
			return -1;
		}

		private int getOrCreate(Position first, Position second) {
			final int existing = find(first, second);
			if (existing >= 0) {
				return existing;
			}
			if ((railCount + 1) * 4 >= hashTable.length * 3) {
				rehash(hashTable.length * 2);
			}
			ensureRailCapacity(railCount + 1);
			final int railId = railCount++;
			firstPositions[railId] = first;
			secondPositions[railId] = second;
			heads[railId] = -1;
			tails[railId] = -1;
			vehiclePositions[railId] = new CompactVehiclePosition(railId);
			int slot = hash(first, second) & (hashTable.length - 1);
			while (hashTable[slot] != 0) {
				slot = (slot + 1) & (hashTable.length - 1);
			}
			hashTable[slot] = railId + 1;
			return railId;
		}

		private void append(int railId, double startDistance, double endDistance, long vehicleId) {
			if (heads[railId] < 0) {
				if (touchedRailCount == touchedRails.length) {
					touchedRails = Arrays.copyOf(touchedRails, touchedRails.length * 2);
				}
				touchedRails[touchedRailCount++] = railId;
			}
			if (intervalCount == startDistances.length) {
				final int newCapacity = startDistances.length * 2;
				startDistances = Arrays.copyOf(startDistances, newCapacity);
				endDistances = Arrays.copyOf(endDistances, newCapacity);
				vehicleIds = Arrays.copyOf(vehicleIds, newCapacity);
				nextIntervals = Arrays.copyOf(nextIntervals, newCapacity);
			}
			final int interval = intervalCount++;
			startDistances[interval] = startDistance;
			endDistances[interval] = endDistance;
			vehicleIds[interval] = vehicleId;
			nextIntervals[interval] = -1;
			if (heads[railId] < 0) {
				heads[railId] = interval;
			} else {
				nextIntervals[tails[railId]] = interval;
			}
			tails[railId] = interval;
		}

		private double getClosestOverlap(int railId, double queryStart, double queryEnd, boolean reversePositions, long vehicleId) {
			double closestOverlap = Double.MAX_VALUE;
			boolean found = false;
			for (int interval = heads[railId]; interval >= 0; interval = nextIntervals[interval]) {
				if (vehicleIds[interval] != vehicleId && Utilities.isIntersecting(queryStart, queryEnd, startDistances[interval], endDistances[interval])) {
					if (reversePositions) {
						closestOverlap = Math.min(closestOverlap, Math.max(0, queryEnd - endDistances[interval]));
					} else {
						closestOverlap = Math.min(closestOverlap, Math.max(0, startDistances[interval] - queryStart));
					}
					found = true;
				}
			}
			return found ? closestOverlap : -1;
		}

		private VehiclePosition copyVehiclePosition(int railId) {
			final VehiclePosition copy = new VehiclePosition();
			for (int interval = heads[railId]; interval >= 0; interval = nextIntervals[interval]) {
				copy.addSegment(startDistances[interval], endDistances[interval], vehicleIds[interval]);
			}
			return copy;
		}

		private void clearIntervals() {
			for (int index = 0; index < touchedRailCount; index++) {
				final int railId = touchedRails[index];
				heads[railId] = -1;
				tails[railId] = -1;
			}
			touchedRailCount = 0;
			intervalCount = 0;
		}

		private void clearRegistry() {
			Arrays.fill(hashTable, 0);
			Arrays.fill(firstPositions, null);
			Arrays.fill(secondPositions, null);
			Arrays.fill(vehiclePositions, null);
			Arrays.fill(heads, -1);
			Arrays.fill(tails, -1);
			railCount = 0;
			touchedRailCount = 0;
			intervalCount = 0;
		}

		private void ensureRailCapacity(int required) {
			if (required <= firstPositions.length) {
				return;
			}
			final int newCapacity = Math.max(required, firstPositions.length * 2);
			firstPositions = Arrays.copyOf(firstPositions, newCapacity);
			secondPositions = Arrays.copyOf(secondPositions, newCapacity);
			vehiclePositions = Arrays.copyOf(vehiclePositions, newCapacity);
			heads = Arrays.copyOf(heads, newCapacity);
			tails = Arrays.copyOf(tails, newCapacity);
			Arrays.fill(heads, railCount, newCapacity, -1);
			Arrays.fill(tails, railCount, newCapacity, -1);
		}

		private void rehash(int newCapacity) {
			final int[] oldTable = hashTable;
			hashTable = new int[newCapacity];
			for (int entry : oldTable) {
				if (entry != 0) {
					final int railId = entry - 1;
					int slot = hash(firstPositions[railId], secondPositions[railId]) & (newCapacity - 1);
					while (hashTable[slot] != 0) {
						slot = (slot + 1) & (newCapacity - 1);
					}
					hashTable[slot] = entry;
				}
			}
		}

		private static int hash(Position first, Position second) {
			long hash = 0x9e3779b97f4a7c15L;
			hash = mix64(hash ^ first.getX());
			hash = mix64(hash ^ first.getY());
			hash = mix64(hash ^ first.getZ());
			hash = mix64(hash ^ second.getX());
			hash = mix64(hash ^ second.getY());
			hash = mix64(hash ^ second.getZ());
			return (int) (hash ^ (hash >>> 32));
		}

		private static long mix64(long value) {
			value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
			value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
			return value ^ (value >>> 31);
		}
	}
}
