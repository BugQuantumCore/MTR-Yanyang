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
import org.mtr.mod.config.Config;
import org.mtr.mod.data.IGui;
import org.mtr.mod.resource.nativeobj.FlatDrawPass;
import org.mtr.mod.resource.nativeobj.NativeModelAccess;
import org.mtr.mod.resource.nativeobj.NativeObjPipeline;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
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

	/**
	 * Direct buffers for the periodic attribute readback. The getters need a direct buffer,
	 * the readback happens at most once per statistics interval, and allocating per call would
	 * put the one allocation exactly on the diagnostic path.
	 *
	 * <p>The integer buffer must hold four ints: {@code glGetVertexAttribIiv} reports the full
	 * {@code ivec4} current value and LWJGL refuses the call when fewer elements remain. The
	 * eight-byte buffer this field used to carry made every readback throw
	 * {@code IllegalArgumentException: Number of remaining elements is 2, must be at least 4},
	 * so the diagnostics line never printed and the exception fired once per flush after the
	 * first statistics interval.</p>
	 */
	private final IntBuffer readbackInts = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();
	private final FloatBuffer readbackFloats = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();

	/** The light of the most recent queue call, sampled into the periodic statistics line. */
	private int lastQueuedLight = -1;

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

					@Override
					public void readIntegerAttrib(int location, int[] out) {
						// The getter reports the full ivec4, so the buffer and the out array are
						// four elements wide; the pass only ever sets the x and y components.
						readbackInts.clear();
						GL33.glGetVertexAttribIiv(location, GL33.GL_CURRENT_VERTEX_ATTRIB, readbackInts);
						out[0] = readbackInts.get(0);
						out[1] = readbackInts.get(1);
						out[2] = readbackInts.get(2);
						out[3] = readbackInts.get(3);
					}

					@Override
					public void readFloatAttrib(int location, float[] out) {
						readbackFloats.clear();
						GL33.glGetVertexAttribfv(location, GL33.GL_CURRENT_VERTEX_ATTRIB, readbackFloats);
						out[0] = readbackFloats.get(0);
						out[1] = readbackFloats.get(1);
						out[2] = readbackFloats.get(2);
						out[3] = readbackFloats.get(3);
					}

					@Override
					public int readAttribArrayEnabled(int location) {
						readbackInts.clear();
						GL33.glGetVertexAttribiv(location, GL33.GL_VERTEX_ATTRIB_ARRAY_ENABLED, readbackInts);
						return readbackInts.get(0);
					}

					@Override
					public int readAttribArrayDivisor(int location) {
						readbackInts.clear();
						GL33.glGetVertexAttribiv(location, GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR, readbackInts);
						return readbackInts.get(0);
					}

					@Override
					public int readCurrentProgram() {
						return GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM);
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

	/** The shader generation the backend's shaders were last reloaded for. */
	private long reloadedGeneration = -1;

	/** Set by {@link #finishReload}; consumed by the next frame's first {@link #queue}. */
	private boolean reloadPending;

	/** How many distinct materials the audit has logged; the audit stops after a few. */
	private int auditedMaterials;

	public void beginReload() {
		// The GL state protection must bracket every upload, but the shader programs only
		// need reloading when a resource reload actually invalidated them: reloading them
		// per model resource compile-checks three programs on every one of the thousands of
		// per-load calls, which stalls the loading screen and every lazy mid-game load.
		GlStateTracker.capture();
		if (shaderGeneration != reloadedGeneration) {
			reloadedGeneration = shaderGeneration;
			if (optimizedRenderer != null) {
				// The backend's own beginReload reloads the shaders and captures
				// again; the nested capture is a no-op, matching its flat design.
				optimizedRenderer.beginReload();
			}
		}
	}

	public void finishReload() {
		if (reloadedGeneration == shaderGeneration && optimizedRenderer != null) {
			optimizedRenderer.finishReload();
		} else {
			GlStateTracker.restore();
		}
		// The registrations belong to a previous generation of models when a resource reload
		// replaced them, and a closed VertexArray's GL name can be reused by a new upload.
		// Dropping the caches cannot happen here, though: a lazy resource load can run in
		// the middle of a frame, after models have already been queued, and resetting the
		// bucket registry then would silently drop every already-queued item of the frame
		// (bucket ids beyond the reset count) - a one-frame flicker per load. Deferring the
		// reset to the next frame's first queue instead makes it free of dropped frames.
		reloadPending = true;
	}

	/** Applies a pending reload reset, at a point where nothing is queued. */
	private void applyPendingReload() {
		if (!reloadPending) {
			return;
		}
		reloadPending = false;
		batchesByModel.clear();
		materialBuckets.clear();
		auditedMaterials = 0;
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

		// The flat path is the fast draw route for models from either backend, so it runs
		// whenever the native OBJ setting allows it. Turning the setting off in the config
		// screen routes this frame's queues through the original OptimizedRenderer instead,
		// immediately and without a restart; models already built natively are still valid
		// OptimizedModels, so the original renderer draws them unchanged.
		if (drawPass == null || !Config.getClient().getUseNativeObjRendering()) {
			optimizedRenderer.queue(optimizedModel.optimizedModel, graphicsHolder, ARGB_WHITE, light);
			return;
		}

		if (graphicsHolder == null) {
			return;
		}

		// A resource reload finished since the last frame: drop the registrations at this
		// point, where nothing is queued, so the frame that follows registers fresh ids.
		applyPendingReload();

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
		lastQueuedLight = light;
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

		if (drawPass == null || !Config.getClient().getUseNativeObjRendering()) {
			// The flag is read per frame, and the queue call in the same frame read the same
			// value, so anything queued while the setting was off went to the original renderer
			// and is flushed by it here.
			optimizedRenderer.render(renderTranslucent);
			return;
		}

		if (drawPass.queuedCount() == 0 || !shaderManager.isReady()) {
			return;
		}

		pendingRenderTranslucent = renderTranslucent;
		// The next flush is the statistics flush: ask it to read the attribute current
		// values back from the driver, so the line can verify what the draws used. The
		// decision is made before the flush runs: a flush that still throws (the readback
		// now contains its own failures, but anything else can abort the pass) must not
		// latch the counter at the request threshold, requesting a readback on every frame
		// that follows - the reset below runs whether the flush completed or not.
		final boolean reportThisFlush = flushesSinceReport >= 1199;
		if (reportThisFlush) {
			drawPass.requestReadback();
		}
		GlStateTracker.capture();
		try {
			protectedRender.run();
		} finally {
			GlStateTracker.restore();
		}

		if (reportThisFlush) {
			flushesSinceReport = 0;
			Init.LOGGER.info("[wrgl] {}; last queued light 0x{}; {}",
					drawPass.describeStatistics(), Integer.toHexString(lastQueuedLight), describeReadback());
		} else {
			flushesSinceReport++;
		}
	}

	/**
	 * One line describing the last attribute readback: what the driver reports against what
	 * the pass wrote for the last drawn item, plus the shading state that item was drawn
	 * under.
	 *
	 * <p>A match means the writes reached the attribute state the draws consumed, so a gray
	 * report with matching readbacks points past the attribute stream - at the shader, the
	 * samplers, the fog or the lightmap texture. A mismatch means the driver moved or reset
	 * the values between the write and the draw, which is exactly the class of problem this
	 * line exists to expose.</p>
	 *
	 * <p>The rest of the line carries every uniform input that can dim a model. The samplers
	 * are what {@code setupShaderBatchState} handed the program for the last material run -
	 * the texture at unit 0, the overlay at unit 1 and the lightmap at unit 2 - read after
	 * the flush, before the rest of the frame changes them. The fog, the shader colour
	 * modulator and the attribute-array flags of the last vertex array are printed for the
	 * same reason: a gray model with a correct attribute stream is being dimmed by one of
	 * them, and this line names which.</p>
	 */
	private String describeReadback() {
		final String readbackError = drawPass.readbackErrorValue();
		if (readbackError != null) {
			return "readback failed: " + readbackError;
		}
		final int[] reportedLight = drawPass.readbackLightValue();
		final float[] reportedColor = drawPass.readbackColorValue();
		if (reportedLight == null || reportedColor == null) {
			return "readback not captured";
		}
		final int[] reportedOverlay = drawPass.readbackOverlayValue();
		final float[] reportedMatrix = drawPass.readbackMatrixValue();
		final float[] writtenMatrix = drawPass.readbackWrittenMatrixValue();
		final int[] reportedEnabled = drawPass.readbackAttribEnabledValue();
		final int[] reportedDivisor = drawPass.readbackAttribDivisorValue();
		final int[] writtenLight = drawPass.readbackWrittenLightValue();

		final boolean lightMatches = (short) reportedLight[0] == (short) writtenLight[0]
						&& (short) reportedLight[1] == (short) writtenLight[1];
		final int expectedColor = drawPass.readbackExpectedColorValue();
		final boolean colorMatches = Math.abs(reportedColor[0] - ((expectedColor >>> 24) & 0xFF) / 255F) < 0.004F
						&& Math.abs(reportedColor[1] - ((expectedColor >>> 16) & 0xFF) / 255F) < 0.004F
						&& Math.abs(reportedColor[2] - ((expectedColor >>> 8) & 0xFF) / 255F) < 0.004F
						&& Math.abs(reportedColor[3] - (expectedColor & 0xFF) / 255F) < 0.004F;
		final boolean overlayMatches = reportedOverlay == null
					|| (short) reportedOverlay[0] == (short) (FlatDrawPass.OVERLAY_DEFAULT >>> 16)
					&& (short) reportedOverlay[1] == (short) FlatDrawPass.OVERLAY_DEFAULT;
		final int firstMatrixMismatch = matrixMismatch(reportedMatrix, writtenMatrix);

		final StringBuilder builder = new StringBuilder();
		builder.append(String.format(
					"readback color=(%.3f,%.3f,%.3f,%.3f) lightmap=(%d,%d) overlay=(%d,%d)%s "
								+ "expected color 0x%08X light 0x%08X, vao %d",
					reportedColor[0], reportedColor[1], reportedColor[2], reportedColor[3],
					reportedLight[0], reportedLight[1],
					reportedOverlay == null ? -1 : reportedOverlay[0],
					reportedOverlay == null ? -1 : reportedOverlay[1],
					colorMatches && lightMatches && overlayMatches && firstMatrixMismatch < 0
								? " matches"
								: String.format(" [MISMATCH: matrix[%d]", firstMatrixMismatch),
					expectedColor, drawPass.readbackExpectedLightValue(), drawPass.readbackLastVaoValue()));
		if (reportedMatrix != null) {
			builder.append(String.format("; matrix rows (%.2f,%.2f,%.2f,%.2f) (%.2f,%.2f,%.2f,%.2f) "
							+ "(%.2f,%.2f,%.2f,%.2f) (%.2f,%.2f,%.2f,%.2f)",
					(Object[]) formatMatrix(reportedMatrix)));
		}
		if (reportedEnabled != null) {
			// One character per attribute location, 0 through 9: E enabled, . disabled,
			// ? unknown (the test sinks answer -1). The expected pattern for a model built
			// under the reference mapping is E-E--E----: position, texture and normal from
			// the buffer; colour, overlay, lightmap and the four matrix rows current.
			final StringBuilder flags = new StringBuilder();
			for (int location = 0; location < reportedEnabled.length; location++) {
				flags.append(reportedEnabled[location] < 0 ? '?' : reportedEnabled[location] != 0 ? 'E' : '.');
			}
			builder.append("; vao attribs ").append(flags);
		}
		if (reportedDivisor != null) {
			builder.append(String.format("; matrix divisors (%d,%d,%d,%d)",
					reportedDivisor[0], reportedDivisor[1], reportedDivisor[2], reportedDivisor[3]));
		}
		builder.append(String.format("; program 0x%X", drawPass.readbackProgramValue()));
		builder.append(String.format("; samplers (%d,%d,%d)",
					RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2)));
		final float[] fogColor = RenderSystem.getShaderFogColor();
		builder.append(String.format("; fog %.1f..%.1f rgba (%.2f,%.2f,%.2f,%.2f)",
					RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
					fogColor[0], fogColor[1], fogColor[2], fogColor[3]));
		final float[] shaderColor = RenderSystem.getShaderColor();
		builder.append(String.format("; shaderColor (%.2f,%.2f,%.2f,%.2f)",
					shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]));
		builder.append(String.format("; path (customShader=%s, gl4ES=%s)",
					Utilities.canUseCustomShader(), GlStateTracker.isGl4ES()));
		return builder.toString();
	}

	/**
	 * The index of the first element where the driver-reported matrix rows differ from the
	 * rows the pass wrote for the last drawn item, or -1 when they agree. Null inputs - the
	 * test sinks, or the shader-pack fallback that sends no matrix attributes at all - agree
	 * with anything.
	 */
	private static int matrixMismatch(@Nullable float[] reported, @Nullable float[] written) {
		if (reported == null || written == null) {
			return -1;
		}
		for (int i = 0; i < 16; i++) {
			if (Float.floatToRawIntBits(reported[i]) != Float.floatToRawIntBits(written[i])) {
				return i;
			}
		}
		return -1;
	}

	/** The sixteen floats of a matrix, boxed for {@code String.format}'s varargs. */
	private static Object[] formatMatrix(float[] matrix) {
		final Object[] out = new Object[16];
		for (int i = 0; i < 16; i++) {
			out[i] = matrix[i];
		}
		return out;
	}

	/**
	 * Logs one line per distinct material, for the first few materials of a session.
	 *
	 * <p>The line carries every input that can dim a model: the shader type (which decides
	 * the BRIGHT full-bright override), the texture identifier, the material's Kd colour and
	 * the lightmap the BRIGHT types force. A gray-model report can be read straight from
	 * these lines: a washed-out Kd, a wrong texture or a missing BRIGHT override all show
	 * up here without needing a debugger.</p>
	 */
	private void auditMaterial(MaterialProperties material, int bucket) {
		if (auditedMaterials >= 12) {
			return;
		}
		auditedMaterials++;
		Init.LOGGER.info("[wrgl] material audit #{}: bucket={} shader={} texture={} kd-color={} bright-lightmap={}",
				auditedMaterials, bucket, material.shaderType, describeTexture(material),
				material.vertexAttributeState.color == null ? "none"
						: String.format("0x%08X", material.vertexAttributeState.color),
				material.vertexAttributeState.lightmapUV == null ? "none"
						: String.format("0x%08X", material.vertexAttributeState.lightmapUV));
	}

	/**
	 * The texture as {@code namespace:path}, so an audit line can be matched against the
	 * resource pack's files directly. The holder's own toString is the object identity,
	 * which is what the audit used to print and which no two lines ever shared usefully.
	 */
	private static String describeTexture(MaterialProperties material) {
		final org.mtr.mapping.holder.Identifier texture = material.getTexture();
		if (texture == null) {
			return "none";
		}
		try {
			return texture.getNamespace() + ":" + texture.getPath();
		} catch (final Exception exception) {
			return String.valueOf(texture);
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
		auditMaterial(material, bucket);
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
