package org.mtr.render;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Vector;

import java.util.Random;

final class CarBoxTransformTest {

	@Test
	void boundsAgreeWithAllEightRotatedCorners() {
		final Random random = new Random(0x4D5452);
		for (int sample = 0; sample < 100; sample++) {
			final Vector position = new Vector(random.nextDouble() * 60000000 - 30000000, random.nextDouble() * 512 - 64, random.nextDouble() * 60000000 - 30000000);
			final double yaw = random.nextDouble() * Math.PI * 2;
			final double pitch = random.nextDouble() * Math.PI - Math.PI / 2;
			final double roll = random.nextDouble() * Math.PI - Math.PI / 2;
			final AABB local = new AABB(-random.nextDouble() * 2, -random.nextDouble(), -random.nextDouble() * 20,
					random.nextDouble() * 3, random.nextDouble() * 5, random.nextDouble() * 20);
			final AABB actual = new RenderVehicles.CarBoxTransform(position, yaw, pitch, roll).apply(local);
			double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
			for (int corner = 0; corner < 8; corner++) {
				final Vector transformed = new Vector((corner & 1) == 0 ? local.minX : local.maxX,
						(corner & 2) == 0 ? local.minY : local.maxY, (corner & 4) == 0 ? local.minZ : local.maxZ)
						.rotateZ(roll).rotateX(pitch).rotateY(yaw).add(position);
				minX = Math.min(minX, transformed.x());
				minY = Math.min(minY, transformed.y());
				minZ = Math.min(minZ, transformed.z());
				maxX = Math.max(maxX, transformed.x());
				maxY = Math.max(maxY, transformed.y());
				maxZ = Math.max(maxZ, transformed.z());
			}
			Assertions.assertEquals(minX, actual.minX, 1E-8);
			Assertions.assertEquals(minY, actual.minY, 1E-8);
			Assertions.assertEquals(minZ, actual.minZ, 1E-8);
			Assertions.assertEquals(maxX, actual.maxX, 1E-8);
			Assertions.assertEquals(maxY, actual.maxY, 1E-8);
			Assertions.assertEquals(maxZ, actual.maxZ, 1E-8);
		}
	}
}
