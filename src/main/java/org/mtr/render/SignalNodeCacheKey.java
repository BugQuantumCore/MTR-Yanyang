package org.mtr.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.lang.ref.WeakReference;

/** Full-coordinate signal lookup key that never keeps a disconnected world alive. */
final class SignalNodeCacheKey {

	private final WeakReference<Object> world;
	private final BlockPos position;
	private final Direction facing;
	private final int hash;

	SignalNodeCacheKey(Object world, BlockPos position, Direction facing) {
		this.world = new WeakReference<>(world);
		this.position = position.immutable();
		this.facing = facing;
		hash = 31 * (31 * System.identityHashCode(world) + this.position.hashCode()) + facing.hashCode();
	}

	boolean hasLiveWorld() {
		return world.get() != null;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof SignalNodeCacheKey other)) {
			return false;
		}
		final Object currentWorld = world.get();
		return currentWorld != null && currentWorld == other.world.get() && position.equals(other.position) && facing == other.facing;
	}

	@Override
	public int hashCode() {
		return hash;
	}
}
