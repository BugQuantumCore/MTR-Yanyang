package org.mtr.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

final class SignalNodeCacheKeyTest {

	@Test
	void retainsAllCoordinateBitsAndFacing() {
		final Object world = new Object();
		final HashSet<SignalNodeCacheKey> keys = new HashSet<>();
		final BlockPos[] positions = {
			new BlockPos(2048, 0, 0), new BlockPos(0, 0, 1),
			new BlockPos(1, 0, 0), new BlockPos(0, 256, 0),
			new BlockPos(0, 0, 0), new BlockPos(1 << 21, 0, 0),
			new BlockPos(0, 0, 1 << 21), new BlockPos(0, 4096, 0),
			new BlockPos(30_000_000, 319, -30_000_000),
			new BlockPos(-30_000_000, -64, 30_000_000)
		};
		for (BlockPos position : positions) {
			for (Direction facing : Direction.values()) {
				assertTrue(keys.add(new SignalNodeCacheKey(world, position, facing)));
			}
		}
		assertEquals(positions.length * Direction.values().length, keys.size());
	}

	@Test
	void comparesWorldsByIdentityAndKeysByValue() {
		final Object world = new EqualWorld();
		final SignalNodeCacheKey first = new SignalNodeCacheKey(world, new BlockPos(1, 2, 3), Direction.NORTH);
		final SignalNodeCacheKey same = new SignalNodeCacheKey(world, new BlockPos(1, 2, 3), Direction.NORTH);
		final SignalNodeCacheKey anotherWorld = new SignalNodeCacheKey(new EqualWorld(), new BlockPos(1, 2, 3), Direction.NORTH);
		assertEquals(first, same);
		assertEquals(first.hashCode(), same.hashCode());
		assertNotEquals(first, anotherWorld);
	}

	@Test
	void snapshotsMutablePositions() {
		final Object world = new Object();
		final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(1, 2, 3);
		final SignalNodeCacheKey key = new SignalNodeCacheKey(world, position, Direction.NORTH);
		position.set(10, 20, 30);
		assertEquals(new SignalNodeCacheKey(world, new BlockPos(1, 2, 3), Direction.NORTH), key);
		assertNotEquals(new SignalNodeCacheKey(world, position, Direction.NORTH), key);
	}

	private static final class EqualWorld {
		@Override
		public boolean equals(Object other) {
			return other instanceof EqualWorld;
		}

		@Override
		public int hashCode() {
			return 1;
		}
	}
}
