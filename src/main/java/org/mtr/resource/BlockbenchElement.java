package org.mtr.resource;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.mtr.MTR;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.generated.resource.BlockbenchElementSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.render.StoredMatrixTransformations;

public final class BlockbenchElement extends BlockbenchElementSchema {

	public BlockbenchElement(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public String getUuid() {
		return uuid;
	}

	public ObjectObjectImmutablePair<AABB, ObjectObjectImmutablePair<StoredMatrixTransformations, IntIntImmutablePair>> setModelPart(PartDefinition parentModelPart, GroupTransformations groupTransformations) {
		// Add model Y offset when creating the model parts
		final float originX = -Utilities.getElement(origin, 0, 0D).floatValue();
		final float originY = -Utilities.getElement(origin, 1, 0D).floatValue();
		final float originZ = Utilities.getElement(origin, 2, 0D).floatValue();
		final int textureX = Utilities.getElement(uv_offset, 0, 0L).intValue();
		final int textureY = Utilities.getElement(uv_offset, 1, 0L).intValue();

		final GroupTransformations newGroupTransformations = new GroupTransformations(groupTransformations, origin, rotation);
		final PartDefinition modelPartData = newGroupTransformations.create(parentModelPart, textureX, textureY);

		final float x = -Utilities.getElement(to, 0, 0D).floatValue() - originX;
		final float y = -Utilities.getElement(to, 1, 0D).floatValue() - originY;
		final float z = Utilities.getElement(from, 2, 0D).floatValue() - originZ;
		final int sizeX = (int) Math.round(Utilities.getElement(to, 0, 0D) - Utilities.getElement(from, 0, 0D));
		final int sizeY = (int) Math.round(Utilities.getElement(to, 1, 0D) - Utilities.getElement(from, 1, 0D));
		final int sizeZ = (int) Math.round(Utilities.getElement(to, 2, 0D) - Utilities.getElement(from, 2, 0D));

		modelPartData.addOrReplaceChild(MTR.randomString(), CubeListBuilder.create().texOffs(textureX, textureY).mirror(!shade || mirror_uv).addBox(x, y, z, sizeX, sizeY, sizeZ, new CubeDeformation((float) inflate)), PartPose.ZERO);

		final StoredMatrixTransformations storedMatrixTransformationsDisplay = new StoredMatrixTransformations(0, 0, 0);
		// Write rotation to modelDisplayPart
		newGroupTransformations.create(storedMatrixTransformationsDisplay);
		final AABB bounds = getBounds(storedMatrixTransformationsDisplay, x, y, z, sizeX, sizeY, sizeZ, (float) inflate);
		storedMatrixTransformationsDisplay.add(matrixStack -> matrixStack.translate(x / 16, y / 16, z / 16));

		return new ObjectObjectImmutablePair<>(bounds, new ObjectObjectImmutablePair<>(storedMatrixTransformationsDisplay, new IntIntImmutablePair(sizeX, sizeY)));
	}

	/**
	 * Match the complete pose used by the baked model, including parent groups and
	 * cube inflation. Rotating only two opposite corners can collapse a 45-degree
	 * box or omit its extrema. This runs once while loading, not during rendering.
	 */
	private static AABB getBounds(StoredMatrixTransformations transformations, float x, float y, float z, int sizeX, int sizeY, int sizeZ, float inflation) {
		final PoseStack matrixStack = new PoseStack();
		transformations.transform(matrixStack, Vec3.ZERO);
		final Matrix4f matrix = matrixStack.last().pose();
		final Vector3f corner = new Vector3f();
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			matrix.transformPosition(
				((i & 1) == 0 ? x - inflation : x + sizeX + inflation) / 16,
				((i & 2) == 0 ? y - inflation : y + sizeY + inflation) / 16,
				((i & 4) == 0 ? z - inflation : z + sizeZ + inflation) / 16,
				corner
			);
			// Keep the existing local collision convention. StoredVertexData flips
			// the baked Y/Z axes; collision boxes additionally reverse X. The
			// placement step in ModelPropertiesPart performs the final Z reversal.
			minX = Math.min(minX, -corner.x);
			minY = Math.min(minY, -corner.y);
			minZ = Math.min(minZ, -corner.z);
			maxX = Math.max(maxX, -corner.x);
			maxY = Math.max(maxY, -corner.y);
			maxZ = Math.max(maxZ, -corner.z);
		}

		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
