package org.mtr.nativeobj;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.mtr.render.StoredMatrixTransformations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-frame native draw list writer. During the model drain (which runs
 * exactly where the GL path issues its draws — inside the world render phase,
 * with VulkanMod's command buffer recording), native items are appended into
 * the frame slot and one submission per drain pass records them on the C++
 * side.
 *
 * <p>Matrix semantics mirror {@code NewOptimizedModel.render} exactly:
 * {@code MV = RenderSystem.getModelViewMatrix() * pose}, column-major via
 * {@link Matrix4f#get(float[])}; the normal matrix is the inverse transpose's
 * 3×3, written as three vec4-padded columns to match the SPIR-V layout.</p>
 */
public final class NativeFrameRecorder {

	private final Map<Long, Integer> textureIndexMap = new HashMap<>();
	private final Map<Long, long[]> textureHandles = new HashMap<>();
	private final List<Item> items = new ArrayList<>();
	private final NativeRuntime runtime;

	private record Item(int meshId, int textureIndex, int stage, int colorPacked, float[] modelView, float[] normalMatrix) {
	}

	NativeFrameRecorder(NativeRuntime runtime) {
		this.runtime = runtime;
	}

	/**
	 * Queue one native model draw. {@code pose} is the transformed pose at the
	 * point the GL path would call
	 * {@code NewOptimizedModel.render(pose, lightMultiplier, shader)}.
	 */
	public void queue(NativeModel model, Matrix4f pose, float lightMultiplier) {
		final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose);
		final float[] mv = new float[16];
		modelView.get(mv);
		final Matrix3f normal = new Matrix3f(modelView).invert().transpose();
		final float[] n = new float[9];
		normal.get(n);
		final float[] normalPadded = {
				n[0], n[1], n[2], 0,
				n[3], n[4], n[5], 0,
				n[6], n[7], n[8], 0,
		};
		final long key = model.texture.hashCode() * 31L + System.identityHashCode(model.renderStage);
		final int textureIndex = textureIndexMap.computeIfAbsent(key, ignored -> textureIndexMap.size());
		items.add(new Item(model.meshId, textureIndex, model.renderStage.ordinal(),
				model.colorFor(lightMultiplier), mv, normalPadded));
		textureHandles.putIfAbsent(key, runtime.host().textureHandles(model.texture));
	}

	private void reset() {
		textureIndexMap.clear();
		textureHandles.clear();
		items.clear();
	}

	/** The payload written into the frame slot: header + textures + items. */
	void writeTo(ByteBuffer frame) {
		frame.order(ByteOrder.LITTLE_ENDIAN);
		// FrameHeader (after the 8-byte seq word handled by the region)
		frame.position(8);
		frame.putInt(WrxRegion.FRAME_MAGIC);
		frame.putInt(items.size());
		frame.putInt(textureHandles.size());
		frame.putInt(0); // reserved
		final float[] projection = new float[16];
		RenderSystem.getProjectionMatrix().get(projection);
		for (final float value : projection) {
			frame.putFloat(value);
		}
		final float[] fogColor = RenderSystem.getShaderFogColor();
		for (int i = 0; i < 4; i++) {
			frame.putFloat(i < fogColor.length ? fogColor[i] : 0);
		}
		frame.putFloat(RenderSystem.getShaderFogStart());
		frame.putFloat(RenderSystem.getShaderFogEnd());
		for (final float value : runtime.host().lightDirection0()) {
			frame.putFloat(value);
		}
		for (final float value : runtime.host().lightDirection1()) {
			frame.putFloat(value);
		}
		frame.putInt(Minecraft.getInstance().getWindow().getFramebufferWidth());
		frame.putInt(Minecraft.getInstance().getWindow().getFramebufferHeight());
		frame.putInt(130); // depth format (informational)
		frame.putInt(0);    // reserved1
		frame.putLong(runtime.host().currentCommandBuffer());
		frame.putLong(runtime.host().boundRenderPass());
		frame.putLong(0);   // reserved2
		frame.putLong(0);   // reserved3
		// texture table (index order)
		final long[][] orderedHandles = new long[textureHandles.size()][];
		for (final Map.Entry<Long, Integer> entry : textureIndexMap.entrySet()) {
			if (entry.getValue() < orderedHandles.length) {
				orderedHandles[entry.getValue()] = textureHandles.get(entry.getKey());
			}
		}
		for (final long[] handles : orderedHandles) {
			if (handles == null) {
				handles = new long[3];
			}
			frame.putLong(handles[0]);
			frame.putLong(handles[1]);
			frame.putLong(handles[2]);
			frame.putInt(43);  // R8G8B8A8_UNORM, VulkanMod's texture format
			frame.putInt(1);    // mip levels (informational)
			frame.putInt(5);    // SHADER_READ_ONLY_OPTIMAL
			frame.putInt(0);    // reserved
		}
		// items
		for (final Item item : items) {
			frame.putInt(item.meshId);
			frame.putInt(item.textureIndex);
			frame.putInt(item.stage);
			frame.putInt(item.colorPacked);
			for (final float value : item.modelView) {
				frame.putFloat(value);
			}
			for (final float value : item.normalMatrix) {
				frame.putFloat(value);
			}
		}
	}

	/** Data holder for the submission entry point. */
	record FrameData(NativeFrameRecorder recorder) {
		void writeTo(ByteBuffer frame) {
			recorder.writeTo(frame);
		}
	}

	/**
	 * Submit everything queued so far. Called once per drained bucket at the
	 * end of {@code MainRenderer.renderModel(matrixStack, modelRenders, offset)}.
	 */
	public void submitAndClear() {
		if (!items.isEmpty()) {
			runtime.submitFrame(new FrameData(this));
		}
		reset();
	}

	/** Bounded micro-wait used by the shared-memory transport's ack polls. */
	static void spin(int micros) {
		final long deadline = System.nanoTime() + micros * 1000L;
		while (System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
	}

	/**
	 * Evaluate a transform for queuing: mirrors the drain's
	 * {@code renderDetailsEntry.left().transform(matrixStack, offset)} +
	 * {@code matrixStack.last().pose()}.
	 */
	public static Matrix4f poseFor(StoredMatrixTransformations transformations, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.world.phys.Vec3 offset) {
		transformations.transform(poseStack, offset);
		final Matrix4f pose = new Matrix4f(poseStack.last().pose());
		poseStack.popPose();
		return pose;
	}
}
