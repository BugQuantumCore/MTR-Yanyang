package org.mtr.mod.data;

import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches the topology-only connected component for each rail signal color.
 *
 * <p>The old core traverses the same same-color graph for every reservation. A component is
 * shared by all of its member rails, while its member references are weak so the static cache
 * state cannot keep a detached world or rail graph alive. The mutable reservation maps are
 * deliberately not cached here.</p>
 */
public final class RailSignalComponents {
	private static final AtomicLong EPOCH = new AtomicLong();
	private static final AtomicLong COMPONENT_BUILD_COUNT = new AtomicLong();
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Component EMPTY = new Component(new WeakReference[0]);
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static final Component FALLBACK = new Component(new WeakReference[0]);

	private RailSignalComponents() {
	}

	/** Per-call adapter. Production uses the Rail mixin; plain-core tests can supply an adapter. */
	public interface RailAccess {
		boolean hasSignalColor(Rail rail, long color);

		Iterable<Rail> connectedRails1(Rail rail);

		Iterable<Rail> connectedRails2(Rail rail);

		RailCache cacheFor(Rail rail);

		void writeReservation(Rail rail, long color, long owner, boolean currentlyBlocked);
	}

	/** Small target accessors used by the transformed Rail and its generated schema superclass. */
	public interface SignalColorAccessor {
		boolean mtr$hasSignalColor(long color);
	}

	public interface RailGraphAccessor {
		Iterable<Rail> mtr$connectedRails1();

		Iterable<Rail> mtr$connectedRails2();
	}

	public interface RailCacheOwner {
		RailCache mtr$getRailSignalCache();
	}

	public interface ReservationMapAccessor {
		void mtr$writeRailReservation(long color, long owner, boolean currentlyBlocked);
	}

	/** A lazily epoch-cleared cache stored on each transformed Rail, never in a static map. */
	public static final class RailCache {
		private long epoch = Long.MIN_VALUE;
		private final Long2ObjectOpenHashMap<Component> componentsByColor = new Long2ObjectOpenHashMap<>(2);

		public RailCache() {
		}

		private synchronized Component get(long currentEpoch, long color) {
			clearForEpoch(currentEpoch);
			return componentsByColor.get(color);
		}

		private synchronized void put(long currentEpoch, long color, Component component) {
			clearForEpoch(currentEpoch);
			componentsByColor.put(color, component);
		}

		private synchronized void markOriginalTraversal(long currentEpoch, long color) {
			clearForEpoch(currentEpoch);
			componentsByColor.put(color, FALLBACK);
		}

		private void clearForEpoch(long currentEpoch) {
			if (epoch != currentEpoch) {
				componentsByColor.clear();
				epoch = currentEpoch;
			}
		}
	}

	/** Immutable set of same-color rails in the exact connected component. */
	public static final class Component {
		private final WeakReference<Rail>[] members;

		@SuppressWarnings("unchecked")
		private Component(WeakReference<Rail>[] members) {
			this.members = members;
		}

		/** Returns the component size captured at build time. */
		public int size() {
			return members.length;
		}

		/** Identity-based membership query for diagnostics and integration tests. */
		public boolean containsIdentity(Rail rail) {
			for (final WeakReference<Rail> member : members) {
				if (member.get() == rail) {
					return true;
				}
			}
			return false;
		}

		private boolean writeReservations(RailAccess access, long color, long owner, boolean currentlyBlocked) {
			for (final WeakReference<Rail> memberReference : members) {
				final Rail member = memberReference.get();
				if (member == null) {
					return false;
				}
				access.writeReservation(member, color, owner, currentlyBlocked);
			}
			return true;
		}
	}

	/**
	 * Production entry point; this cast is valid after {@code RailReservationMixin} is applied.
	 */
	public static Component component(Rail rail, long color) {
		return component(rail, color, (RailAccess) (Object) rail);
	}

	/** Adapter entry point used by tests against untransformed instances of the actual old core. */
	public static Component component(Rail rail, long color, RailAccess access) {
		return component(rail, color, access, false);
	}

	private static Component component(Rail rail, long color, RailAccess access, boolean forReservation) {
		if (rail == null || !access.hasSignalColor(rail, color)) {
			return EMPTY;
		}

		for (int attempt = 0; attempt < 2; attempt++) {
			final long epoch = EPOCH.get();
			final RailCache rootCache = access.cacheFor(rail);
			final Component cached = rootCache.get(epoch, color);
			if (cached != null) {
				if (cached == FALLBACK && !forReservation) {
					return build(rail, color, access).component;
				}
				return cached;
			}

			final Build built = build(rail, color, access);

			if (EPOCH.get() != epoch) {
				continue;
			}

			final boolean cacheable = built.members.size() <= 4L * built.cacheMembers.size();
			for (final Rail member : built.cacheMembers) {
				final RailCache memberCache = access.cacheFor(member);
				if (cacheable) {
					memberCache.put(epoch, color, built.component);
				} else {
					memberCache.markOriginalTraversal(epoch, color);
				}
			}
			return built.component;
		}

		// A concurrent sync or collection made the snapshot unstable. Return a fresh weak snapshot
		// without publishing it; callers that cannot materialize it may use the original traversal.
		return build(rail, color, access).component;
	}

	/** Returns true after writing every component member, false if callers must use old traversal. */
	public static boolean reserveComponent(Rail rail, long color, long owner, boolean currentlyBlocked) {
		return reserveComponent(rail, color, owner, currentlyBlocked, (RailAccess) (Object) rail);
	}

	/** Adapter entry point used by the old-core behavior comparison tests. */
	public static boolean reserveComponent(Rail rail, long color, long owner, boolean currentlyBlocked, RailAccess access) {
		Component component = component(rail, color, access, true);
		if (component == FALLBACK) {
			return false;
		}
		if (component.writeReservations(access, color, owner, currentlyBlocked)) {
			return true;
		}
		invalidateAll();
		component = component(rail, color, access, true);
		if (component == FALLBACK) {
			return false;
		}
		return component.writeReservations(access, color, owner, currentlyBlocked);
	}

	/** Invalidates every per-Rail cache lazily on its next read without retaining Rail instances. */
	public static void invalidateAll() {
		EPOCH.incrementAndGet();
	}

	/** Increments only when a component is actually rebuilt; useful to verify transformed callsites. */
	public static long componentBuildCount() {
		return COMPONENT_BUILD_COUNT.get();
	}

	private static Build build(Rail root, long color, RailAccess access) {
		final IdentityHashMap<Rail, Boolean> visited = new IdentityHashMap<>();
		final IdentityHashMap<Rail, ArrayList<Rail>> reverseEdges = new IdentityHashMap<>();
		final ArrayList<Rail> members = new ArrayList<>();
		visited.put(root, Boolean.TRUE);
		members.add(root);

		final ArrayDeque<Frame> stack = new ArrayDeque<>();
		stack.push(new Frame(root, access));
		while (!stack.isEmpty()) {
			final Frame frame = stack.peek();
			if (frame.current.hasNext()) {
				final Rail connectedRail = frame.current.next();
				final boolean alreadyVisited = visited.containsKey(connectedRail);
				final boolean connectedHasColor = alreadyVisited || access.hasSignalColor(connectedRail, color);
				if (connectedHasColor) {
					reverseEdges.computeIfAbsent(connectedRail, ignored -> new ArrayList<>()).add(frame.rail);
				}
				if (!alreadyVisited && connectedHasColor) {
					visited.put(connectedRail, Boolean.TRUE);
					members.add(connectedRail);
					stack.push(new Frame(connectedRail, access));
				}
			} else if (frame.usingFirst) {
				frame.current = access.connectedRails2(frame.rail).iterator();
				frame.usingFirst = false;
			} else {
				stack.pop();
			}
		}

		// A Rail's cached connection sets can be one-way after Data.sync replaces other Rails.
		// Share this reachable component only among the root's strongly connected members: each of
		// those members has exactly the same reachable set, while a one-way predecessor does not.
		final IdentityHashMap<Rail, Boolean> stronglyConnected = new IdentityHashMap<>();
		final ArrayList<Rail> cacheMembers = new ArrayList<>();
		final ArrayDeque<Rail> reverseStack = new ArrayDeque<>();
		stronglyConnected.put(root, Boolean.TRUE);
		cacheMembers.add(root);
		reverseStack.push(root);
		while (!reverseStack.isEmpty()) {
			final ArrayList<Rail> predecessors = reverseEdges.get(reverseStack.pop());
			if (predecessors != null) {
				for (final Rail predecessor : predecessors) {
					if (!stronglyConnected.containsKey(predecessor)) {
						stronglyConnected.put(predecessor, Boolean.TRUE);
						cacheMembers.add(predecessor);
						reverseStack.push(predecessor);
					}
				}
			}
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		final WeakReference<Rail>[] references = new WeakReference[members.size()];
		for (int i = 0; i < members.size(); i++) {
			references[i] = new WeakReference<>(members.get(i));
		}
		COMPONENT_BUILD_COUNT.incrementAndGet();
		return new Build(new Component(references), members, cacheMembers);
	}

	private static final class Build {
		private final Component component;
		private final ArrayList<Rail> members;
		private final ArrayList<Rail> cacheMembers;

		private Build(Component component, ArrayList<Rail> members, ArrayList<Rail> cacheMembers) {
			this.component = component;
			this.members = members;
			this.cacheMembers = cacheMembers;
		}
	}

	private static final class Frame {
		private final Rail rail;
		private boolean usingFirst;
		private Iterator<Rail> current;

		private Frame(Rail rail, RailAccess access) {
			this.rail = rail;
			this.usingFirst = true;
			this.current = access.connectedRails1(rail).iterator();
		}
	}
}
