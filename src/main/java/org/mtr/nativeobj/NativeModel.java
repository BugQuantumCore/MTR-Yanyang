package org.mtr.nativeobj;

import net.minecraft.resources.ResourceLocation;
import org.mtr.resource.RenderStage;

/**
 * A mesh set built by the native pipeline. One instance exists per
 * (material, render stage) bucket of one model, mirroring what the Java path
 * stores in a {@code NewOptimizedModel} — the difference is that the vertex
 * data never leaves the native side: the {@code meshId} addresses a GPU
 * buffer the C++ recorder owns.
 */
public final class NativeModel {

	public final int modelId;
	public final int meshId;
	public final ResourceLocation texture;
	public final RenderStage renderStage;
	public final int colorPacked; // r << 24 | g << 16 | b << 8 | a (1.20.x packing)
	public final float[] aabb;

	NativeModel(int modelId, int meshId, ResourceLocation texture, RenderStage renderStage, int colorPacked, float[] aabb) {
		this.modelId = modelId;
		this.meshId = meshId;
		this.texture = texture;
		this.renderStage = renderStage;
		this.colorPacked = colorPacked;
		this.aabb = aabb;
	}

	/**
	 * The colour the draw item multiplies into the shader, exactly the chain
	 * the Java path computes: the material's Kd times the fork's scalar
	 * brightness ({@code light / 0xF} or full bright).
	 */
	public int colorFor(float lightMultiplier) {
		final int r = clampToByte(((colorPacked >>> 24) & 0xFF) / 255.0F * lightMultiplier);
		final int g = clampToByte(((colorPacked >>> 16) & 0xFF) / 255.0F * lightMultiplier);
		final int b = clampToByte(((colorPacked >>> 8) & 0xFF) / 255.0F * lightMultiplier);
		final int a = colorPacked & 0xFF;
		return r << 24 | g << 16 | b << 8 | a;
	}

	private static int clampToByte(float value) {
		return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
	}
}
