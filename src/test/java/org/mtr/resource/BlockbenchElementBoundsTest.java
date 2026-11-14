package org.mtr.resource;

import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.model.StoredVertexData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BlockbenchElementBoundsTest {

	private static final double EPSILON = 1E-5;

	@Test
	void unrotatedOffsetCubePreservesTheCollisionCoordinateConvention() {
		final AABB actual = boundsMatchingBakedVertices(element(
			new double[]{2, 3, 4}, new double[]{10, 15, 20}, new double[]{3, 5, 7}, new double[]{0, 0, 0}, 0, false
		), new GroupTransformations());
		assertBounds(new AABB(2D / 16, 3D / 16, -20D / 16, 10D / 16, 15D / 16, -4D / 16), actual);
	}

	@Test
	void fortyFiveDegreeRotationRetainsBothHorizontalExtrema() {
		final AABB actual = boundsMatchingBakedVertices(element(
			new double[]{-8, -8, -1}, new double[]{8, 8, 1}, new double[]{0, 0, 0}, new double[]{0, 0, 45}, 0, false
		), new GroupTransformations());
		final double extent = Math.sqrt(0.5);
		assertBounds(new AABB(-extent, -extent, -1D / 16, extent, extent, 1D / 16), actual);
	}

	@Test
	void offCenterCompoundRotationMatchesRenderedGeometry() {
		boundsMatchingBakedVertices(element(
			new double[]{2, -5, 7}, new double[]{11, 13, 24}, new double[]{3, 4, 5}, new double[]{25, -35, 42}, 0, false
		), new GroupTransformations());
	}

	@Test
	void parentGroupChainAndInflatedMirroredCubeMatchRenderedGeometry() {
		final GroupTransformations parent = new GroupTransformations(new GroupTransformations(),
			DoubleArrayList.of(8, -3, 7), DoubleArrayList.of(30, -45, 18));
		final GroupTransformations child = new GroupTransformations(parent,
			DoubleArrayList.of(-4, 9, 2), DoubleArrayList.of(-12, 27, 61));
		boundsMatchingBakedVertices(element(
			new double[]{-9, -2, 3}, new double[]{7, 14, 22}, new double[]{2, 5, -1}, new double[]{17, -23, 38}, 1.25, true
		), child);
	}

	@Test
	void rotatedZeroThicknessFloorKeepsItsRenderedExtent() {
		boundsMatchingBakedVertices(element(
			new double[]{-12, 0, -4}, new double[]{12, 0, 4}, new double[]{3, 0, 1}, new double[]{0, 45, 0}, 0, false
		), new GroupTransformations());
	}

	private static AABB boundsMatchingBakedVertices(BlockbenchElement element, GroupTransformations groups) {
		final PartDefinition root = new MeshDefinition().getRoot();
		final AABB actual = element.setModelPart(root, groups).left();
		// Bake and walk the same Minecraft ModelPart geometry used by the loader.
		// This oracle exercises its rotation order, pivots, inflation and mirroring
		// independently from the bounds helper's eight-corner calculation.
		final ObjectArrayList<StoredVertexData> vertices = new ObjectArrayList<>();
		StoredVertexData.write(root.bake(64, 64), vertices);
		assertFalse(vertices.isEmpty());
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (final StoredVertexData vertex : vertices) {
			minX = Math.min(minX, -vertex.x());
			minY = Math.min(minY, vertex.y());
			minZ = Math.min(minZ, vertex.z());
			maxX = Math.max(maxX, -vertex.x());
			maxY = Math.max(maxY, vertex.y());
			maxZ = Math.max(maxZ, vertex.z());
		}
		assertBounds(new AABB(minX, minY, minZ, maxX, maxY, maxZ), actual);
		return actual;
	}

	private static BlockbenchElement element(double[] from, double[] to, double[] origin, double[] rotation, double inflation, boolean mirror) {
		final JsonObject json = new JsonObject();
		json.addProperty("name", "bounds-regression");
		json.addProperty("uuid", "bounds-regression");
		json.addProperty("inflate", inflation);
		json.addProperty("mirror_uv", mirror);
		json.addProperty("shade", true);
		json.add("from", array(from));
		json.add("to", array(to));
		json.add("origin", array(origin));
		json.add("rotation", array(rotation));
		json.add("uv_offset", array(new double[]{0, 0}));
		return new BlockbenchElement(new JsonReader(json));
	}

	private static JsonArray array(double[] values) {
		final JsonArray array = new JsonArray();
		for (final double value : values) {
			array.add(value);
		}
		return array;
	}

	private static void assertBounds(AABB expected, AABB actual) {
		assertEquals(expected.minX, actual.minX, EPSILON, "min X");
		assertEquals(expected.minY, actual.minY, EPSILON, "min Y");
		assertEquals(expected.minZ, actual.minZ, EPSILON, "min Z");
		assertEquals(expected.maxX, actual.maxX, EPSILON, "max X");
		assertEquals(expected.maxY, actual.maxY, EPSILON, "max Y");
		assertEquals(expected.maxZ, actual.maxZ, EPSILON, "max Z");
	}
}
