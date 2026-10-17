package org.mtr.mod.data;

import org.mtr.core.data.Client;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Simulation-thread cache of the clients to notify for each immutable core Rail. */
public final class ServerRailVisibility {

	private static final int CELL_SIZE = 256;
	private static final int MAX_CELLS = 256;
	private final Data data;
	private final ObjectArraySet<Client> clients;
	private final IdentityHashMap<Rail, Entry> entries = new IdentityHashMap<>();
	private final Long2ObjectOpenHashMap<List<Entry>> cells = new Long2ObjectOpenHashMap<>();
	private final List<Entry> overflow = new ArrayList<>();
	private final List<Entry> subscribedEntries = new ArrayList<>();
	private final List<ClientSnapshot> snapshots = new ArrayList<>();
	private Entry[] railOrder = new Entry[0];
	private int railCursor;
	private boolean railOrderDirty;
	private boolean dirty = true;
	private Thread railTickThread;
	private long queryStamp;

	public ServerRailVisibility(Data data, ObjectArraySet<Client> clients) {
		this.data = data;
		this.clients = clients;
	}

	public void invalidate() {
		dirty = true;
	}

	/** Called immediately before Simulator's original rails.tick1 loop, on its own thread. */
	public void beginRailTick() {
		railTickThread = null;
		railCursor = 0;
		final boolean clientsChanged = snapshotClients();
		if (dirty || entries.size() != data.rails.size()) {
			rebuildRails();
			rebuildSubscriptions();
		} else {
			if (railOrderDirty) {
				int index = 0;
				for (final Rail rail : data.rails) {
					railOrder[index++] = entries.get(rail);
				}
				railOrderDirty = false;
			}
			if (clientsChanged) {
				rebuildSubscriptions();
			}
		}
		railTickThread = Thread.currentThread();
	}

	public void endRailTick() {
		railTickThread = null;
	}

	public void updateClients(Rail rail, boolean needsUpdate) {
		if (railTickThread != Thread.currentThread()) {
			updateDirectly(clients, rail, needsUpdate);
			return;
		}
		// The normal Simulator loop has the same order as the last index build. Avoid
		// a hash lookup per rail; direct/reordered calls still resolve by object identity.
		final Entry expected = railCursor < railOrder.length ? railOrder[railCursor++] : null;
		final Entry entry;
		if (expected != null && expected.rail == rail) {
			entry = expected;
		} else {
			railOrderDirty = true;
			entry = entries.get(rail);
		}
		if (entry == null) {
			// Public rails can be replaced without Data.sync, including a same-size replacement.
			// Notify the new object this tick and rebuild the index at the next rail phase.
			dirty = true;
			for (int i = 0; i < snapshots.size(); i++) {
				final ClientSnapshot snapshot = snapshots.get(i);
				if (rail.closeTo(snapshot.position, snapshot.radius)) {
					snapshot.client.update(rail, needsUpdate);
				}
			}
		} else if (entry.subscribers != null) {
			for (int i = 0; i < entry.subscribers.size(); i++) {
				// Client.sendUpdates clears keep IDs every tick, so even cache hits must notify.
				entry.subscribers.get(i).update(rail, needsUpdate);
			}
		}
	}

	public static void updateClients(Simulator simulator, Rail rail, boolean needsUpdate) {
		final ServerRailVisibility visibility = simulator instanceof Owner ? ((Owner) simulator).mtr$getRailVisibility() : null;
		if (visibility == null) {
			updateDirectly(simulator.clients, rail, needsUpdate);
		} else {
			visibility.updateClients(rail, needsUpdate);
		}
	}

	private static void updateDirectly(ObjectArraySet<Client> clients, Rail rail, boolean needsUpdate) {
		for (final Client client : clients) {
			if (rail.closeTo(client.getPosition(), client.getUpdateRadius())) {
				client.update(rail, needsUpdate);
			}
		}
	}

	private boolean snapshotClients() {
		boolean changed = snapshots.size() != clients.size();
		int index = 0;
		for (final Client client : clients) {
			final Position position = client.getPosition();
			final double radius = client.getUpdateRadius();
			if (index == snapshots.size()) {
				snapshots.add(new ClientSnapshot());
			}
			final ClientSnapshot snapshot = snapshots.get(index++);
			final long x = position.getX();
			final long y = position.getY();
			final long z = position.getZ();
			changed |= snapshot.client != client || snapshot.x != x || snapshot.y != y || snapshot.z != z ||
					Double.doubleToLongBits(snapshot.radius) != Double.doubleToLongBits(radius);
			snapshot.client = client;
			snapshot.position = position;
			snapshot.x = x;
			snapshot.y = y;
			snapshot.z = z;
			snapshot.radius = radius;
		}
		while (snapshots.size() > index) {
			snapshots.remove(snapshots.size() - 1);
		}
		return changed;
	}

	private void rebuildRails() {
		entries.clear();
		cells.clear();
		overflow.clear();
		subscribedEntries.clear();
		railOrder = new Entry[data.rails.size()];
		int index = 0;
		for (final Rail rail : data.rails) {
			final Entry entry = new Entry(rail);
			railOrder[index++] = entry;
			entries.put(rail, entry);
			final long minX = Math.floorDiv(rail.railMath.minX, CELL_SIZE);
			final long maxX = Math.floorDiv(rail.railMath.maxX, CELL_SIZE);
			final long minZ = Math.floorDiv(rail.railMath.minZ, CELL_SIZE);
			final long maxZ = Math.floorDiv(rail.railMath.maxZ, CELL_SIZE);
			if (!boundedRange(minX, maxX, minZ, maxZ)) {
				overflow.add(entry);
				continue;
			}
			for (long x = minX; x <= maxX; x++) {
				for (long z = minZ; z <= maxZ; z++) {
					final long key = cellKey(x, z);
					List<Entry> bucket = cells.get(key);
					if (bucket == null) {
						bucket = new ArrayList<>();
						cells.put(key, bucket);
					}
					bucket.add(entry);
				}
			}
		}
		dirty = false;
		railOrderDirty = false;
	}

	private void rebuildSubscriptions() {
		// A moving player should clear only previously visible rails, not the entire network.
		for (int i = 0; i < subscribedEntries.size(); i++) {
			subscribedEntries.get(i).subscribers.clear();
		}
		subscribedEntries.clear();
		for (int i = 0; i < snapshots.size(); i++) {
			query(snapshots.get(i));
		}
	}

	private void query(ClientSnapshot snapshot) {
		if (++queryStamp == 0) {
			for (final Entry entry : entries.values()) {
				entry.lastQueryStamp = 0;
			}
			queryStamp = 1;
		}
		// Expand outwards to preserve inclusive closeTo boundaries after double arithmetic.
		final long minX = (long) Math.floor(Math.nextDown(snapshot.x - snapshot.radius) / CELL_SIZE);
		final long maxX = (long) Math.floor(Math.nextUp(snapshot.x + snapshot.radius) / CELL_SIZE);
		final long minZ = (long) Math.floor(Math.nextDown(snapshot.z - snapshot.radius) / CELL_SIZE);
		final long maxZ = (long) Math.floor(Math.nextUp(snapshot.z + snapshot.radius) / CELL_SIZE);
		if (!Double.isFinite(snapshot.radius) || snapshot.radius < 0 || !boundedRange(minX, maxX, minZ, maxZ)) {
			for (final Entry entry : entries.values()) {
				consider(entry, snapshot);
			}
			return;
		}
		for (long x = minX; x <= maxX; x++) {
			for (long z = minZ; z <= maxZ; z++) {
				final List<Entry> bucket = cells.get(cellKey(x, z));
				if (bucket != null) {
					for (int i = 0; i < bucket.size(); i++) {
						consider(bucket.get(i), snapshot);
					}
				}
			}
		}
		for (int i = 0; i < overflow.size(); i++) {
			consider(overflow.get(i), snapshot);
		}
	}

	private void consider(Entry entry, ClientSnapshot snapshot) {
		if (entry.lastQueryStamp == queryStamp) {
			return;
		}
		entry.lastQueryStamp = queryStamp;
		if (entry.rail.closeTo(snapshot.position, snapshot.radius)) {
			if (entry.subscribers == null) {
				entry.subscribers = new ArrayList<>();
			}
			if (entry.subscribers.isEmpty()) {
				subscribedEntries.add(entry);
			}
			entry.subscribers.add(snapshot.client);
		}
	}

	private static boolean boundedRange(long minX, long maxX, long minZ, long maxZ) {
		return minX >= Integer.MIN_VALUE && maxX <= Integer.MAX_VALUE && minZ >= Integer.MIN_VALUE && maxZ <= Integer.MAX_VALUE &&
				maxX >= minX && maxZ >= minZ && maxX - minX < MAX_CELLS && maxZ - minZ < MAX_CELLS &&
				(maxX - minX + 1) * (maxZ - minZ + 1) <= MAX_CELLS;
	}

	private static long cellKey(long x, long z) {
		return (x << 32) | (z & 0xFFFFFFFFL);
	}

	/** Implemented only by the server Simulator mixin. The getter does not create a cache. */
	public interface Owner {
		ServerRailVisibility mtr$getRailVisibility();
	}

	private static final class Entry {
		private final Rail rail;
		private List<Client> subscribers;
		private long lastQueryStamp;

		private Entry(Rail rail) {
			this.rail = rail;
		}
	}

	private static final class ClientSnapshot {
		private Client client;
		private Position position;
		private long x;
		private long y;
		private long z;
		private double radius;
	}
}
