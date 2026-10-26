package org.mtr.model;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.mtr.nativeobj.NativeModel;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

//? if >= 1.21.4 {
import net.minecraft.client.renderer.CompiledShaderProgram;
//? } else {
/*import net.minecraft.client.renderer.ShaderInstance;
 *///? }

/**
 * One drawable mesh — a single texture, a single GPU vertex buffer, one draw call.
 *
 * <p>Instances are created during model build (see
 * {@link NewOptimizedModelGroup#build(VertexFormat.Mode)}) and live for the rest of
 * the parent loader's lifetime. The buffer is uploaded once via
 * {@link VertexBuffer#uploadStatic}; subsequent frames just call {@link #render} and
 * the GPU re-uses the stored vertices.</p>
 *
 * <p>If the supplied {@code callback} is {@code null}, no buffer is allocated and
 * {@link #render} no-ops. This is the path for empty groups.</p>

 * <p>Every instance that owns a GL buffer registers itself in a live-instance registry;
 * {@code CustomResourceLoader#reload()} closes them all via {@link #releaseAll()} before
 * the model caches are cleared, so no VAO/VBO outlives its model set.</p>
 *
 * <p><b>Naming note:</b> the "New" prefix is historical — there is no non-{@code New}
 * counterpart any more. See {@code docs/MIGRATIONS.md} §4 for the planned rename.</p>
 */
public final class NewOptimizedModel {

	public final ResourceLocation texture;
	@Nullable
	private final VertexBuffer vertexBuffer;
	/**
	 * The native Vulkan mesh when this model was built through the native
	 * pipeline (see {@code docs/NATIVE_VULKAN.md}); null means the GL path.
	 * Dual-path build: when present, the GL {@link #vertexBuffer} is built
	 * too, and the render drain decides per frame which path draws — native
	 * under plain VulkanMod, GL under Beryl's pipeline or on plain OpenGL.
	 */
	@Nullable
	private final NativeModel nativeModel;

	/**
	 * Reusable model-view matrix scratch — render is single-threaded on the GL thread, so
	 * a single buffer avoids a per-draw allocation. See {@code docs/PERFORMANCE.md} §3.2.
	 */
	private static final Matrix4f MODEL_VIEW_SCRATCH = new Matrix4f();
	/**
	 * Reusable shader-colour save/restore scratch, same render-thread-confinement pattern
	 * as {@link #MODEL_VIEW_SCRATCH}. Copied element-wise because getShaderColor may hand
	 * back RenderSystem's internal array, so it must never be kept across a draw.
	 */
	private static final float[] SAVED_COLOR_SCRATCH = new float[4];
	/**
	 * Live instances that own a GL vertex buffer, so a resource reload can close them all
	 * before the model caches holding them are cleared (see {@link #releaseAll()}). Without
	 * this, every F3+T / pack change / Beryl toggle leaked the entire previous model set's
	 * VAO+VBO ids — the caches were cleared but the buffers were never closed. Guarded by
	 * itself; {@code CustomResourceLoader.reload()} drains it on the render thread.
	 */
	private static final Set<NewOptimizedModel> LIVE_GL_MODELS = new HashSet<>();
	/**
	 * Pre-built {@code colorModulator} arrays for every value of {@code lightMultiplier}
	 * the renderer ever emits. The renderer derives {@code lightMultiplier} from a 4-bit
	 * lightmap value (16 levels), or sets {@code 1.0f} for full-bright stages. We pack
	 * that into a 17-entry table — index {@code 0..15} for {@code i / 15f}, index
	 * {@code 16} for the full-bright case. Callers that supply a different value (e.g.
	 * fractional dimming for cinematic fades) fall back to a freshly allocated array.
	 * See {@code docs/PERFORMANCE.md} §3.2 item 1.
	 */
	private static final float[][] COLOR_MODULATORS;

	static {
		COLOR_MODULATORS = new float[17][];
		for (int i = 0; i < 16; i++) {
			final float v = i / 15f;
			COLOR_MODULATORS[i] = new float[]{v, v, v, 1f};
		}
		COLOR_MODULATORS[16] = new float[]{1f, 1f, 1f, 1f};
	}

	/**
	 * @param texture  the texture this mesh draws with
	 * @param drawMode the GL primitive type ({@code TRIANGLES} for OBJ, {@code QUADS} for
	 *                 Blockbench)
	 * @param callback the vertex-emitter callback invoked exactly once to populate the
	 *                 buffer, or {@code null} to skip buffer creation entirely
	 */
	public NewOptimizedModel(ResourceLocation texture, VertexFormat.Mode drawMode, @Nullable Consumer<VertexConsumer> callback) {
		this(texture, drawMode, callback, null);
	}

	public NewOptimizedModel(ResourceLocation texture, VertexFormat.Mode drawMode, @Nullable Consumer<VertexConsumer> callback, @Nullable NativeModel nativeModel) {
		this.vertexBuffer = callback == null ? null : createVertexBuffer(drawMode, DefaultVertexFormat.NEW_ENTITY, callback);
		this.texture = texture;
		this.nativeModel = nativeModel;
		if (vertexBuffer != null) {
			synchronized (LIVE_GL_MODELS) {
				LIVE_GL_MODELS.add(this);
			}
		}
	}

	/** The native mesh handle, or null when this model renders through MC's GL path. */
	@Nullable
	public NativeModel getNativeModel() {
		return nativeModel;
	}

	/**
	 * Close the GL vertex buffer owned by this model and forget it in the live registry.
	 * Idempotent: the registry removal happens exactly once per instance, so a later close
	 * (for example one racing the reload's {@link #releaseAll()}) is a no-op. The native
	 * mesh, when present, is owned and freed by the native runtime instead.
	 */
	public void close() {
		synchronized (LIVE_GL_MODELS) {
			if (!LIVE_GL_MODELS.remove(this)) {
				return;
			}
		}
		if (vertexBuffer != null) {
			vertexBuffer.close();
		}
	}

	/**
	 * Close every live model's GL vertex buffer and clear the registry. Called from
	 * {@code CustomResourceLoader#reload()} on the render thread right before the model
	 * caches are cleared — the GL-path counterpart of the native pipeline's
	 * {@code freeAllModelsForReload} leak safety. Models rebuilt later in the same reload
	 * register fresh buffers.
	 */
	public static void releaseAll() {
		synchronized (LIVE_GL_MODELS) {
			final Iterator<NewOptimizedModel> iterator = LIVE_GL_MODELS.iterator();
			while (iterator.hasNext()) {
				final NewOptimizedModel model = iterator.next();
				iterator.remove();
				if (model.vertexBuffer != null) {
					model.vertexBuffer.close();
				}
			}
		}
	}

	/**
	 * Issue the draw call for this mesh.
	 *
	 * @param matrix4f        the per-instance model matrix (the pose, without the camera view)
	 * @param lightMultiplier brightness scale in {@code [0, 1]}; {@code 1} for full-bright stages
	 * @param shaderProgram   the active shader (set by the render layer's state setup), may be {@code null} during reload
	 */
//? if >= 1.21.4 {
	public void render(Matrix4f matrix4f, float lightMultiplier, @Nullable CompiledShaderProgram shaderProgram) {
//? } else {
	/*public void render(Matrix4f matrix4f, float lightMultiplier, @Nullable ShaderInstance shaderProgram) {
//
*///? }
		if (vertexBuffer != null && shaderProgram != null) {
			// Full per-instance model-view: camera view (RenderSystem snapshot) * pose.
			MODEL_VIEW_SCRATCH.set(RenderSystem.getModelViewMatrix()).mul(matrix4f);
			final float[] colorModulator = colorModulatorFor(lightMultiplier);
			// The light multiplier travels through the shader colour so every backend picks
			// it up: pure GL reads COLOR_MODULATOR via setDefaultUniforms inside
			// drawWithShader, and VulkanMod's translated pipelines plus Beryl's redirected
			// pipelines read the VRenderSystem colour that RenderSystem.setShaderColor
			// keeps in sync. The colour is restored afterwards so later vanilla batches
			// are not tinted by this instance.
			final float[] currentColor = RenderSystem.getShaderColor();
			System.arraycopy(currentColor, 0, SAVED_COLOR_SCRATCH, 0, 4);
			RenderSystem.setShaderColor(colorModulator[0], colorModulator[1], colorModulator[2], colorModulator[3]);
			// drawWithShader — never the raw draw() — is the same contract every vanilla
			// batch uses: it applies setDefaultUniforms (the per-instance MODEL_VIEW,
			// COLOR_MODULATOR and fog uniforms) and, under VulkanMod, additionally binds
			// the shader's CURRENT pipeline (VulkanMod-translated, or Beryl-redirected),
			// syncs the per-draw MVP into VRenderSystem, binds the shader textures and
			// uploads the UBOs, then restores the frame MVP. The previous raw draw()
			// relied on whichever pipeline the previous unrelated draw left bound: under
			// VulkanMod/Beryl that drew NEW_ENTITY data through stale terrain or entity
			// pipelines — stretched rectangles, wrong-blend translucent stages
			// (see-through ceilings and windows) and non-additive light stages
			// (misplaced colour blocks).
			vertexBuffer.drawWithShader(MODEL_VIEW_SCRATCH, RenderSystem.getProjectionMatrix(), shaderProgram);
			RenderSystem.setShaderColor(SAVED_COLOR_SCRATCH[0], SAVED_COLOR_SCRATCH[1], SAVED_COLOR_SCRATCH[2], SAVED_COLOR_SCRATCH[3]);
		}
	}

	public static VertexBuffer createVertexBuffer(VertexFormat.Mode drawMode, VertexFormat vertexFormat, Consumer<VertexConsumer> callback) {
//? if >= 1.21.4 {
		return VertexBuffer.uploadStatic(drawMode, vertexFormat, callback);
//? } else {
		/*final BufferBuilder builder = Tesselator.getInstance().begin(drawMode, vertexFormat);
		callback.accept(builder);
		final MeshData renderedBuffer = builder.build();
		final VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		vertexBuffer.bind();
		vertexBuffer.upload(renderedBuffer);
		return vertexBuffer;
*///? }
	}

	private static float[] colorModulatorFor(float lightMultiplier) {
		// Map [0, 1] → 16-step quantised table when possible; otherwise allocate.
		if (lightMultiplier >= 1f) {
			return COLOR_MODULATORS[16];
		}
		if (lightMultiplier <= 0f) {
			return COLOR_MODULATORS[0];
		}
		final int index = Math.round(lightMultiplier * 15f);
		final float quantised = index / 15f;
		// Only return the cached array when the requested value rounds cleanly — otherwise
		// allocate to preserve exact appearance for callers passing arbitrary floats.
		//noinspection FloatingPointEquality
		if (quantised == lightMultiplier) {
			return COLOR_MODULATORS[index];
		}
		return new float[]{lightMultiplier, lightMultiplier, lightMultiplier, 1f};
	}

}
