package org.mtr.mod.resource.nativeobj;

import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.object.IndexBuffer;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * The few pieces of state the native draw path needs that the prebuilt backend keeps
 * private.
 *
 * <h2>Why reflection is used at all</h2>
 *
 * <p>MTR's optimized renderer lives in a prebuilt jar that this branch does not modify, and
 * its {@code OptimizedRenderer} keeps three things package-private or private that a
 * replacement draw loop has to read:</p>
 *
 * <ul>
 *   <li>{@code OptimizedModel.uploadedParts} — the vertex arrays a model consists of. Without
 *       it there is nothing to register or draw.</li>
 *   <li>{@code VertexArray.materialProperties} — the per-bucket identity that decides which
 *       shader and texture to bind, and therefore the grouping.</li>
 *   <li>{@code OptimizedRenderer.shaderManager} and its
 *       {@code setupShaderBatchState}/{@code cleanupShaderBatchState} — the shader, texture
 *       and blend state for a material. Re-implementing that would mean duplicating shader
 *       loading and render-layer behaviour that needs a live client to get right.</li>
 * </ul>
 *
 * <p>Every handle is resolved once, on the first use, and a failure to resolve any of them
 * disables the native draw path entirely so the original one runs. That is deliberate: a
 * partial resolution would produce a renderer that draws some things and silently skips
 * others, which is worse than not engaging at all.</p>
 *
 * <h2>Why method handles rather than {@code Field.get}</h2>
 *
 * <p>These are read once per model at registration, not per frame, so either would do. Method
 * handles are used because they are resolved once and are {@code static} after that, and
 * because a failure is a checked exception at resolve time rather than an
 * {@code IllegalAccessException} on the hot path.</p>
 */
final class NativeModelAccess {

	/** Resolved once; null means the native draw path cannot be used. */
	private static boolean resolved;

	private static boolean available;

	@Nullable
	private static MethodHandle uploadedPartsGetter;

	@Nullable
	private static MethodHandle materialPropertiesGetter;

	@Nullable
	private static MethodHandle vertexArrayIdGetter;

	@Nullable
	private static MethodHandle indexBufferGetter;

	@Nullable
	private static MethodHandle indexBufferVertexCountGetter;

	@Nullable
	private static MethodHandle shaderManagerGetter;

	@Nullable
	private static MethodHandle setupShaderBatchState;

	@Nullable
	private static MethodHandle cleanupShaderBatchState;

	/** Why resolution failed, for the log. */
	private static String failureReason = "";

	private NativeModelAccess() {
	}

	/** True when every handle resolved. */
	static synchronized boolean isAvailable() {
		if (!resolved) {
			resolved = true;
			available = resolve();
			if (!available) {
				Init.LOGGER.warn("[wrgl] the native draw path is disabled: {}", failureReason);
			}
		}
		return available;
	}

	/** The reason the native draw path is unavailable, or an empty string. */
	static synchronized String getFailureReason() {
		return failureReason;
	}

	private static boolean resolve() {
		try {
			// A private lookup on a class in the prebuilt jar. privateLookupIn needs the
			// target's module to be open to this one; both live on the classpath rather than
			// in named modules, so the unnamed module rules apply and this succeeds.
			final MethodHandles.Lookup modelLookup = MethodHandles.privateLookupIn(
					OptimizedModel.class, MethodHandles.lookup());
			uploadedPartsGetter = modelLookup.findGetter(
					OptimizedModel.class, "uploadedParts", List.class);
			uploadedPartsGetter = MethodHandles.filterReturnValue(uploadedPartsGetter,
					MethodHandles.identity(List.class));

			final MethodHandles.Lookup arrayLookup = MethodHandles.privateLookupIn(
					VertexArray.class, MethodHandles.lookup());
			materialPropertiesGetter = arrayLookup.findGetter(
					VertexArray.class, "materialProperties", MaterialProperties.class);
			// The GL name and the index buffer are both private. The name is what
			// glBindVertexArray takes; the index buffer carries the count glDrawElements
			// needs, which is why it is read rather than recomputed from the geometry.
			vertexArrayIdGetter = arrayLookup.findGetter(VertexArray.class, "id", int.class);
			indexBufferGetter = arrayLookup.findGetter(
					VertexArray.class, "indexBuffer", IndexBuffer.class);

			final MethodHandles.Lookup indexLookup = MethodHandles.privateLookupIn(
					IndexBuffer.class, MethodHandles.lookup());
			indexBufferVertexCountGetter = indexLookup.findGetter(
					IndexBuffer.class, "vertexCount", int.class);

			// The renderer's shader manager is private and its methods are public, so the
			// lookup only has to reach the field.
			final Class<?> rendererClass = Class.forName("org.mtr.mapping.mapper.OptimizedRenderer");
			final MethodHandles.Lookup rendererLookup = MethodHandles.privateLookupIn(
					rendererClass, MethodHandles.lookup());
			shaderManagerGetter = rendererLookup.findGetter(
					rendererClass, "shaderManager", ShaderManager.class);

			setupShaderBatchState = MethodHandles.publicLookup().findVirtual(
					ShaderManager.class, "setupShaderBatchState",
					MethodType.methodType(void.class, MaterialProperties.class));
			cleanupShaderBatchState = MethodHandles.publicLookup().findVirtual(
					ShaderManager.class, "cleanupShaderBatchState", MethodType.methodType(void.class));

			return true;
		} catch (Throwable throwable) {
			failureReason = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
			uploadedPartsGetter = null;
			materialPropertiesGetter = null;
			vertexArrayIdGetter = null;
			indexBufferGetter = null;
			indexBufferVertexCountGetter = null;
			shaderManagerGetter = null;
			setupShaderBatchState = null;
			cleanupShaderBatchState = null;
			return false;
		}
	}

	// -----------------------------------------------------------------------
	// Accessors. Each returns null rather than throwing when unavailable, so a caller that
	// checked isAvailable() once still cannot be broken by a later failure.
	// -----------------------------------------------------------------------

	/** The vertex arrays a model consists of, or null. */
	@Nullable
	@SuppressWarnings("unchecked")
	static List<VertexArray> getUploadedParts(OptimizedModel model) {
		if (uploadedPartsGetter == null) {
			return null;
		}
		try {
			return (List<VertexArray>) uploadedPartsGetter.invoke(model);
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** A vertex array's material identity, or null. */
	@Nullable
	static MaterialProperties getMaterialProperties(VertexArray array) {
		if (materialPropertiesGetter == null) {
			return null;
		}
		try {
			return (MaterialProperties) materialPropertiesGetter.invoke(array);
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** The renderer's shader manager, or null. */
	@Nullable
	static ShaderManager getShaderManager(Object optimizedRenderer) {
		if (shaderManagerGetter == null) {
			return null;
		}
		try {
			return (ShaderManager) shaderManagerGetter.invoke(optimizedRenderer);
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** Binds the shader, texture and blend state for a material. */
	static void setupShaderBatchState(@Nullable ShaderManager shaderManager, MaterialProperties materialProperties) {
		if (shaderManager == null || setupShaderBatchState == null || materialProperties == null) {
			return;
		}
		try {
			setupShaderBatchState.invoke(shaderManager, materialProperties);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not set up shader batch state: {}", throwable.toString());
		}
	}

	/** Undoes whatever {@link #setupShaderBatchState} did. */
	static void cleanupShaderBatchState(@Nullable ShaderManager shaderManager) {
		if (shaderManager == null || cleanupShaderBatchState == null) {
			return;
		}
		try {
			cleanupShaderBatchState.invoke(shaderManager);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrgl] could not clean up shader batch state: {}", throwable.toString());
		}
	}

	/**
	 * The GL vertex array name behind a {@code VertexArray}.
	 *
	 * <p>MTR exposes no getter for it, and the native draw list needs the name rather than
	 * the object. Zero means the array was already closed, which the caller treats as "do not
	 * register".</p>
	 */
	static int getVertexArrayId(VertexArray array) {
		if (vertexArrayIdGetter == null) {
			return 0;
		}
		try {
			return (int) vertexArrayIdGetter.invoke(array);
		} catch (Throwable throwable) {
			return 0;
		}
	}

	/**
	 * The number of indices a {@code VertexArray} draws.
	 *
	 * <p>Read from the array's index buffer rather than recomputed, because the index buffer
	 * is what {@code VertexArray.draw} passes to {@code glDrawElements} and a mismatch would
	 * draw the wrong amount of geometry.</p>
	 */
	static int getIndexCount(VertexArray array) {
		if (indexBufferGetter == null || indexBufferVertexCountGetter == null) {
			return 0;
		}
		try {
			final Object indexBuffer = indexBufferGetter.invoke(array);
			if (indexBuffer == null) {
				return 0;
			}
			return (int) indexBufferVertexCountGetter.invoke(indexBuffer);
		} catch (Throwable throwable) {
			return 0;
		}
	}
}
