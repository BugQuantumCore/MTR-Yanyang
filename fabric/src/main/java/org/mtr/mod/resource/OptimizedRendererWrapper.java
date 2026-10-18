package org.mtr.mod.resource;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgram;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mapping.render.tool.Utilities;
import org.mtr.mod.Init;
import org.mtr.mod.data.IGui;
import org.mtr.mod.resource.nativeobj.FlatDrawPass;
import org.mtr.mod.resource.nativeobj.NativeModelAccess;
import org.mtr.mod.resource.nativeobj.NativeObjPipeline;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The optimized renderer, drawn through the flat pass when the backend's private state can be
 * reached.
 *
 * <h2>What the flat path replaces</h2>
 *
 * <p>The reference draw path, per vertex array per frame, allocates a
 * {@code VertexAttributeState} and a {@code RenderCall}, looks the material up in a
 * {@code HashMap} keyed on boxed properties, and applies attribute state twice: once for the
 * queued pose and once for the material. Together that is a {@code glVertexAttrib4f} per
 * model matrix row, one for the colour, one {@code glVertexAttribI2i} each for the overlay
 * and the lightmap - nine GL calls per array, all issued unconditionally.</p>
 *
 * <p>Here the arrays are resolved once per model and referenced by small integers afterwards.
 * Per frame a draw costs one integer set and one 64-byte pose copy into a persistent array,
 * and nothing is allocated. The flat pass groups items by material {@code equals} like the
 * reference's batch manager - so each distinct material binds its shader once per frame -
 * applies the queued state and then the material's own state exactly as
 * {@code RenderCall.draw} does, and suppresses every GL call whose value did not change.</p>
 *
 * <p>The native draw list that preceded this drove the same cache from C++, which meant one
 * JNI upcall into Java plus one LWJGL downcall per GL call. On scenes with thousands of
 * vehicle draws the crossings cost more than the cache saved, which was the stutter; see
 * {@link FlatDrawPass}. The native library still owns the model build path, where it removes
 * the allocation cost of preparing the geometry rather than the cost of drawing it.</p>
 *
 * <h2>Fallback</h2>
 *
 * <p>If the prebuilt backend's private state cannot be reached, this class never engages and
 * the original {@code OptimizedRenderer} handles everything exactly as before. The choice is
 * made once, at construction, and is reported by {@link #getImplementationName()}.</p>
 */
public final class OptimizedRendererWrapper implements IGui {

	private static long shaderGeneration;

	public static long getShaderGeneration() {
		return shaderGeneration;
	}

	public void invalidateShaders() {
		shaderGeneration++;
	}

	@Nullable
	private final OptimizedRenderer optimizedRenderer;

	/** The flat pass, or null when the original path is in use. */
	@Nullable
	private final FlatDrawPass drawPass;

	/** The prebuilt backend's shader manager, used for the readiness gate like the reference. */
	@Nullable
	private final ShaderManager shaderManager;

	/** Sets up and tears down a material's shader state, through the shader manager. */
	@Nullable
	private final FlatDrawPass.MaterialBinder materialBinder;

	/** Emits the flat pass's GL calls through LWJGL directly. */
	@Nullable
	private final FlatDrawPass.GlSink glSink;

	/** Registered buckets per model instance, computed once per model. */
	private final Map<OptimizedModel, ModelBatches> batchesByModel = new IdentityHashMap<>();

	/** Materials resolved to bucket ids, so registration happens once per distinct material. */
	private final Map<MaterialProperties, Integer> materialBuckets = new HashMap<>();

	/** The pose currently being queued, reused so a frame allocates nothing. */
	private final float[] poseScratch = new float[16];

	/** The translucent flag for the in-flight render, handed to the persistent runnable. */
	private boolean pendingRenderTranslucent;

	/**
	 * Draws the flat pass. Allocated once, in the constructor, because it closes over the
	 * final fields the constructor assigns. {@link #render} brackets the run in
	 * {@code GlStateTracker} capture and restore, matching the reference.
	 */
	private final Runnable protectedRender;

	/** The model view matrix scratch for the shader-pack fallback. */
	private final Matrix4f modelViewScratch = new Matrix4f();

	/** Counts flushes so the statistics line is logged occasionally rather than every frame. */
	private int flushesSinceReport;

	public OptimizedRendererWrapper() {
		final OptimizedRenderer renderer = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedRenderer() : null;
		this.optimizedRenderer = renderer;

		FlatDrawPass pass = null;
		FlatDrawPass.MaterialBinder binder = null;
		FlatDrawPass.GlSink sink = null;
		ShaderManager manager = null;
		if (renderer != null && NativeModelAccess.isAvailable()) {
			manager = NativeModelAccess.getShaderManager(renderer);
			if (manager != null) {
				final ShaderManager boundManager = manager;
				pass = new FlatDrawPass();
				binder = new FlatDrawPass.MaterialBinder() {
					@Override
					public void setup(MaterialProperties material) {
						boundManager.setupShaderBatchState(material);
					}

					@Override
					public void cleanup() {
						boundManager.cleanupShaderBatchState();
					}
				};
				sink = new FlatDrawPass.GlSink() {

					@Override
					public void attrib4f(int location, float x, float y, float z, float w) {
						GL33.glVertexAttrib4f(location, x, y, z, w);
					}

					@Override
					public void attribI2i(int location, int x, int y) {
						GL33.glVertexAttribI2i(location, x, y);
					}

					@Override
					public void attrib2f(int location, float x, float y) {
						GL33.glVertexAttrib2f(location, x, y);
					}

					@Override
					public void bindVertexArray(int vao) {
						GL33.glBindVertexArray(vao);
					}

					@Override
					public void drawElements(int count) {
						GL33.glDrawElements(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L);
					}

					@Override
					public void setModelViewMatrix(float[] matrix, int offset) {
						// What the reference's VertexAttributeState does when the custom shader
						// cannot be used: the pose replaces the current shader's model view
						// matrix, because the camera transform is already part of the pose.
						final ShaderProgram shaderProgram = RenderSystem.getShader();
						if (shaderProgram != null && shaderProgram.modelViewMat != null) {
							shaderProgram.modelViewMat.set(modelViewScratch.set(matrix, offset));
							shaderProgram.bind();
						}
					}

					@Override
					public boolean useCustomShader() {
						return Utilities.canUseCustomShader();
					}

					@Override
					public boolean useFloatAttributeFallback() {
						// Read after the state capture, so the tracker's gl4ES detection is fresh.
						return GlStateTracker.isGl4ES();
					}
				};
				Init.LOGGER.info("[wrgl] the flat OBJ draw path is active; native model builds are {}",
								NativeObjPipeline.isAvailable() ? "active" : "falling back to the Java pipeline");
			}
		}
		this.drawPass = pass;
		this.shaderManager = manager;
		this.materialBinder = binder;
		this.glSink = sink;
		this.protectedRender = () -> drawPass.render(pendingRenderTranslucent, materialBinder, glSink);
		if (renderer != null && pass == null) {
			Init.LOGGER.info("[wrgl] the flat OBJ draw path is not active: {}",
							NativeModelAccess.getFailureReason().isEmpty()
											? "the shader manager could not be reached"
											: NativeModelAccess.getFailureReason());
		}
	}

	/** The name of the implementation actually in use, for diagnostics. */
	public String getImplementationName() {
		if (drawPass != null) {
			return "java-flat";
		}
		return optimizedRenderer != null ? "java" : "disabled";
	}

	/** A one-line summary of the draw path's work, or a note that it is not active. */
	public String describeDrawStatistics() {
		return drawPass == null ? "flat draw pass: not active" : drawPass.describeStatistics();
	}

	public void beginReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.beginReload();
		}
	}

	public void finishReload() {
		if (optimizedRenderer != null) {
			optimizedRenderer.finishReload();
		}
		// The registrations belong to the previous generation of models, whose GL objects have
		// been deleted and whose materials are new objects. Dropping both caches resets the
		// buckets and keeps stale ids out of the queue.
		batchesByModel.clear();
		materialBuckets.clear();
		if (drawPass != null) {
			drawPass.reset();
		}
	}

	/**
	 * Queues a model for this frame's draw.
	 *
	 * <p>On the flat path this records small integers and copies the pose into one shared
	 * array, so a frame allocates nothing. On the original path it hands the model to
	 * {@code OptimizedRenderer.queue}, which allocates a {@code VertexAttributeState} and a
	 * {@code Matrix4f} copy per call.</p>
	 */
	public void queue(OptimizedModelWrapper optimizedModel, GraphicsHolder graphicsHolder, int light) {
		if (optimizedRenderer == null || optimizedModel.optimizedModel == null) {
			return;
		}

		if (drawPass == null) {
			optimizedRenderer.queue(optimizedModel.optimizedModel, graphicsHolder, ARGB_WHITE, light);
			return;
		}

		if (graphicsHolder == null) {
			return;
		}

		final ModelBatches batches = batchesFor(optimizedModel.optimizedModel);
		if (batches.count == 0) {
			return;
		}

		// The pose comes from the graphics holder's matrix stack, exactly where
		// OptimizedRenderer.queue reads it. A holder with no matrix stack queues nothing, which
		// is what the reference does too.
		if (!NativeModelAccess.readPoseMatrix(graphicsHolder, poseScratch)) {
			return;
		}

		for (int i = 0; i < batches.count; i++) {
			drawPass.queue(batches.buckets[i], batches.vaos[i], batches.indexCounts[i], light, ARGB_WHITE, poseScratch, 0);
		}
	}

	/**
	 * Draws everything queued and empties the queue.
	 *
	 * <p>The pass runs inside {@code GlStateTracker} capture and restore, which is exactly
	 * how the prebuilt backend's own {@code OptimizedRenderer.render} brackets its draw
	 * calls; the previous native path omitted it and left the shader, blend and vertex
	 * array state dirty behind it. The shader readiness gate matches the reference too:
	 * when the shaders are not ready the reference draws nothing.</p>
	 */
	public void render(boolean renderTranslucent) {
		if (optimizedRenderer == null) {
			return;
		}

		if (drawPass == null) {
			optimizedRenderer.render(renderTranslucent);
			return;
		}

		if (drawPass.queuedCount() == 0 || !shaderManager.isReady()) {
			return;
		}

		pendingRenderTranslucent = renderTranslucent;
		GlStateTracker.capture();
		try {
			protectedRender.run();
		} finally {
			GlStateTracker.restore();
		}

		flushesSinceReport++;
		if (flushesSinceReport >= 1200) {
			flushesSinceReport = 0;
			Init.LOGGER.info("[wrgl] {}", drawPass.describeStatistics());
		}
	}

	/**
	 * The buckets for a model, registering them on first use.
	 *
	 * <p>Registration walks the model's {@code uploadedParts} and records the bucket, the GL
	 * vertex array name and the index count for each. The materials are read once here rather
	 * than per frame, and each distinct material resolves to one bucket id, which is what
	 * keeps the per-frame path free of property lookups.</p>
	 */
	private ModelBatches batchesFor(OptimizedModel model) {
		final ModelBatches cached = batchesByModel.get(model);
		if (cached != null) {
			return cached;
		}

		final FlatDrawPass pass = drawPass;
		if (pass == null) {
			return ModelBatches.EMPTY;
		}

		final List<VertexArray> parts = NativeModelAccess.getUploadedParts(model);
		if (parts == null || parts.isEmpty()) {
			batchesByModel.put(model, ModelBatches.EMPTY);
			return ModelBatches.EMPTY;
		}

		int[] buckets = new int[parts.size()];
		int[] vaos = new int[parts.size()];
		int[] indexCounts = new int[parts.size()];
		int registered = 0;
		for (final VertexArray part : parts) {
			final int vao = NativeModelAccess.getVertexArrayId(part);
			final int indexCount = NativeModelAccess.getIndexCount(part);
			final MaterialProperties material = NativeModelAccess.getMaterialProperties(part);
			if (vao == 0 || indexCount <= 0 || material == null) {
				// A closed array or a zero count draws nothing; skipping the part rather than
				// the model matches what a zero-count draw call would have done.
				continue;
			}
			buckets[registered] = resolveBucket(material);
			vaos[registered] = vao;
			indexCounts[registered] = indexCount;
			registered++;
		}

		final ModelBatches result;
		if (registered == 0) {
			result = ModelBatches.EMPTY;
		} else if (registered == parts.size()) {
			result = new ModelBatches(buckets, vaos, indexCounts, registered);
		} else {
			result = new ModelBatches(
							Arrays.copyOf(buckets, registered),
							Arrays.copyOf(vaos, registered),
							Arrays.copyOf(indexCounts, registered),
							registered);
		}

		batchesByModel.put(model, result);
		return result;
	}

	/**
	 * The bucket id for a material, registering the material on first sight.
	 *
	 * <p>Keyed on {@code equals} rather than identity, because the reference's batch manager
	 * groups by {@code equals} too: two models built with equal properties draw under one
	 * shader bind. The map is only touched at model registration, so the boxed lookup is not
	 * on the per-frame path.</p>
	 */
	private int resolveBucket(MaterialProperties material) {
		final Integer cached = materialBuckets.get(material);
		if (cached != null) {
			return cached;
		}
		final int bucket = drawPass.registerBucket(material);
		materialBuckets.put(material, bucket);
		return bucket;
	}

	/** Releases the flat pass state. Called when the client shuts down. */
	public void close() {
		batchesByModel.clear();
		materialBuckets.clear();
		if (drawPass != null) {
			drawPass.reset();
		}
	}

	/**
	 * The registered buckets of one model. Plain arrays rather than objects per part, because
	 * the queue path copies them into the flat pass's shared arrays.
	 */
	private static final class ModelBatches {

		static final ModelBatches EMPTY = new ModelBatches(new int[0], new int[0], new int[0], 0);

		final int[] buckets;
		final int[] vaos;
		final int[] indexCounts;
		final int count;

		ModelBatches(int[] buckets, int[] vaos, int[] indexCounts, int count) {
			this.buckets = buckets;
			this.vaos = vaos;
			this.indexCounts = indexCounts;
			this.count = count;
		}
	}
}
