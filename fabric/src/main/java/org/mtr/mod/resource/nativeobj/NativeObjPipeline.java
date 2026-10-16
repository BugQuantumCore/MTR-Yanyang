package org.mtr.mod.resource.nativeobj;

import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point to the native OBJ model pipeline.
 *
 * <p>The native library replaces MTR's CPU-side model pipeline: OBJ and MTL parsing, group
 * and material-group splitting, the per-vertex renderable conversion, every model and
 * placement transform, normal generation, vertex de-duplication, and the interleaved vertex
 * buffer plus index buffer that gets uploaded to OpenGL.</p>
 *
 * <p>Everything here is optional. The library is loaded lazily and every failure path leaves
 * {@link #isAvailable()} false, in which case the caller keeps using the original Java
 * pipeline. There is deliberately no configuration switch that can make a missing or
 * mismatched library fatal: a resource pack must never be able to stop the game from
 * starting.</p>
 *
 * <h2>Why the ABI is checked</h2>
 *
 * <p>A stale library that agrees on method names but not on semantics would produce subtly
 * wrong geometry rather than an error, which is far worse than falling back. So the native
 * side reports an ABI integer and the load is rejected unless it matches exactly.</p>
 */
public final class NativeObjPipeline {

	/** Must match {@code WROBJ_ABI_VERSION} in native/include/wrobj/wrobj.hpp. */
	public static final int EXPECTED_ABI = 1;

	private static final String LIBRARY_NAME = "wrobj";

	/** Set once loading has been attempted, successfully or not. */
	private static boolean loadAttempted;

	private static boolean available;

	private static String statusMessage = "not loaded yet";

	/** Diagnostics, so a player report can say which path was taken and why. */
	private static long buildCount;
	private static long placementCount;
	private static long totalVertices;
	private static long estimatedHeapBytesAvoided;

	private NativeObjPipeline() {
	}

	/**
	 * Loads the native library on first use.
	 *
	 * @return true when the native pipeline can be used
	 */
	public static synchronized boolean isAvailable() {
		if (!loadAttempted) {
			loadAttempted = true;
			available = tryLoad();
		}
		return available;
	}

	/** A human-readable description of the load outcome, for logs. */
	public static synchronized String getStatusMessage() {
		return statusMessage;
	}

	/** A one-line summary of the work the native pipeline has performed. */
	public static synchronized String getStatistics() {
		return String.format(
				"native OBJ pipeline: %d model build(s), %d placement(s), %d vertices, "
						+ "~%.1f MiB of Java heap avoided",
				buildCount, placementCount, totalVertices, estimatedHeapBytesAvoided / 1048576.0);
	}

	static synchronized void recordBuild(int placements, long vertices, long heapBytes) {
		buildCount++;
		placementCount += placements;
		totalVertices += vertices;
		estimatedHeapBytesAvoided += heapBytes;
	}

	// -----------------------------------------------------------------------
	// Library loading
	// -----------------------------------------------------------------------

	private static boolean tryLoad() {
		// The packaged layout first: the mod jar ships the library next to the classes, and
		// the loader extracts it to a temporary directory before loadLibrary can find it.
		if (loadFromModDirectory()) {
			statusMessage = "loaded from the mod's native directory, ABI " + nativeAbiVersion();
			return true;
		}
		// A developer build tree, so a local `cmake --build` is usable without repackaging.
		for (final Path candidate : developmentCandidates()) {
			if (loadFromPath(candidate)) {
				statusMessage = "loaded from " + candidate + ", ABI " + nativeAbiVersion();
				return true;
			}
		}
		// Finally the plain library path, which covers a library installed system wide and
		// an explicit -Djava.library.path for testing.
		try {
			System.loadLibrary(LIBRARY_NAME);
			if (nativeAbiVersion() == EXPECTED_ABI) {
				statusMessage = "loaded from java.library.path, ABI " + EXPECTED_ABI;
				return true;
			}
			statusMessage = "ABI mismatch on java.library.path: expected " + EXPECTED_ABI
					+ ", found " + nativeAbiVersion();
			return false;
		} catch (UnsatisfiedLinkError error) {
			statusMessage = "not available (" + error.getMessage() + "); the Java OBJ pipeline will be used";
			Init.LOGGER.info("[wrobj] {}", statusMessage);
			return false;
		}
	}

	private static boolean loadFromModDirectory() {
		// This class lives inside the mod jar, so its own code source locates the jar. When
		// running from a directory (a dev run) there is no jar to inspect and this returns
		// false, leaving the development candidates to do the work.
		try {
			final var source = NativeObjPipeline.class.getProtectionDomain().getCodeSource();
			if (source == null || source.getLocation() == null) {
				return false;
			}
			final Path location = Paths.get(source.getLocation().toURI());
			if (!Files.isRegularFile(location)) {
				return false;
			}
			return loadFromJar(location);
		} catch (Throwable throwable) {
			return false;
		}
	}

	/**
	 * Extracts the platform library from a jar and loads it.
	 *
	 * <p>The library is written to a temporary directory keyed by its own length so repeated
	 * loads of the same build reuse one file instead of filling the temp directory. A pack
	 * cannot influence the path, which matters because the file is executed.</p>
	 */
	private static boolean loadFromJar(Path jarPath) {
		final String entryName = "native/" + platformDirectory() + "/" + libraryFileName();
		try (final var jar = new java.util.jar.JarFile(jarPath.toFile())) {
			final var entry = jar.getEntry(entryName);
			if (entry == null) {
				return false;
			}
			final Path directory = Paths.get(System.getProperty("java.io.tmpdir"), "mtr-wrobj");
			Files.createDirectories(directory);
			final Path target = directory.resolve(entry.getSize() + "-" + libraryFileName());
			if (!Files.isRegularFile(target) || Files.size(target) != entry.getSize()) {
				try (final var input = jar.getInputStream(entry)) {
					Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			return loadFromPath(target);
		} catch (Throwable throwable) {
			return false;
		}
	}

	private static boolean loadFromPath(Path path) {
		if (!Files.isRegularFile(path)) {
			return false;
		}
		try {
			System.load(path.toAbsolutePath().toString());
			if (nativeAbiVersion() != EXPECTED_ABI) {
				statusMessage = "ABI mismatch at " + path + ": expected " + EXPECTED_ABI
						+ ", found " + nativeAbiVersion();
				Init.LOGGER.warn("[wrobj] {}", statusMessage);
				return false;
			}
			return true;
		} catch (UnsatisfiedLinkError error) {
			Init.LOGGER.warn("[wrobj] {} could not be loaded: {}", path, error.getMessage());
			return false;
		}
	}

	/** Candidate paths in a source checkout, so a local native build is picked up. */
	private static Iterable<Path> developmentCandidates() {
		final String workingDirectory = System.getProperty("user.dir", ".");
		return java.util.List.of(
				Paths.get(workingDirectory, "native", "build-windows", libraryFileName()),
				Paths.get(workingDirectory, "native", "build", libraryFileName()),
				Paths.get(workingDirectory, "..", "native", "build-windows", libraryFileName()).normalize(),
				Paths.get(workingDirectory, "..", "native", "build", libraryFileName()).normalize()
		);
	}

	private static String libraryFileName() {
		final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("win")) {
			return LIBRARY_NAME + ".dll";
		}
		if (os.contains("mac")) {
			return "lib" + LIBRARY_NAME + ".dylib";
		}
		return "lib" + LIBRARY_NAME + ".so";
	}

	private static String platformDirectory() {
		final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		final String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
		final String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
		final String archName = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
		return osName + "-" + archName;
	}

	// -----------------------------------------------------------------------
	// Native declarations
	// -----------------------------------------------------------------------

	/**
	 * Parses an OBJ document and its MTL, reporting the material group keys the reference
	 * loader would produce, in the same order.
	 *
	 * @return the keys, or null when the document is structurally unreadable
	 */
	@Nullable
	public static native NativeObjKey[] nativeLoadKeys(byte[] objContent, byte[] mtlContent, boolean flipTextureV);

	/**
	 * Creates a builder for one model instance.
	 *
	 * <p>Each vehicle part needs its own builder. The reference implementation deep-copies
	 * the model per part, and sharing a builder would accumulate every part's geometry into
	 * one buffer — the cross-condition rendering bug this branch already had to fix.</p>
	 *
	 * @param materialGroupTextures false to take the loader's "no materials" branch, where
	 *                              the material group name doubles as the texture string
	 * @param textureResolver       resolves each key's texture string to a handle
	 * @return a native handle, or 0 on failure
	 */
	public static native long nativeCreateBuilder(byte[] objContent, byte[] mtlContent,
			boolean materialGroupTextures, boolean flipTextureV, NativeTextureResolver textureResolver);

	/** Releases a builder. Safe to call with 0. */
	public static native void nativeFreeBuilder(long handle);

	/**
	 * Applies a model-level transform to the shared source meshes, matching
	 * {@code RawMesh.applyTranslation / applyRotation / applyScale / applyMirror}.
	 *
	 * @param operation 0 translation, 1 rotation in degrees per axis, 2 scale, 3 mirror flags
	 */
	public static native void nativeApplyTransform(long handle, int operation, double a, double b, double c);

	/**
	 * Appends a placed copy of every source mesh, matching
	 * {@code ObjModel.addTransformation(shaderType, x, y, z, flipped)}.
	 */
	public static native void nativeAddTransform(long handle, int shaderType, double x, double y, double z, boolean flipped);

	/** Bounding box of the source meshes, reflecting every transform applied so far. */
	@Nullable
	public static native float[] nativeGetBounds(long handle);

	/** Runs normal generation, de-duplication and packing. Returns null for an empty model. */
	@Nullable
	public static native NativeObjResult nativeBuild(long handle);

	/** Releases a build result. Safe to call with 0. */
	public static native void nativeFreeResult(long handle);

	/** The native library's ABI version. */
	public static native int nativeAbiVersion();

	/** The native library's version string, for logs. */
	public static native String nativeVersionString();

	// -----------------------------------------------------------------------
	// Guarded wrappers
	// -----------------------------------------------------------------------

	/**
	 * Parses an OBJ document and reports its material group keys, or null when the native
	 * pipeline is unavailable or the document is unreadable.
	 */
	@Nullable
	public static NativeObjKey[] loadKeys(byte[] objContent, @Nullable byte[] mtlContent, boolean flipTextureV) {
		if (!isAvailable() || objContent == null || objContent.length == 0) {
			return null;
		}
		try {
			final NativeObjKey[] keys = nativeLoadKeys(objContent, mtlContent, flipTextureV);
			return keys == null || keys.length == 0 ? null : keys;
		} catch (Throwable throwable) {
			// A malformed model is a resource pack problem, not a mod failure. Log it and let
			// the caller fall back rather than propagating out of model loading.
			Init.LOGGER.warn("[wrobj] could not parse an OBJ document: {}", throwable.toString());
			return null;
		}
	}

	/**
	 * Creates a builder, or returns 0 when the native pipeline is unavailable or the
	 * document is unreadable.
	 */
	public static long createBuilder(byte[] objContent, @Nullable byte[] mtlContent,
			boolean materialGroupTextures, boolean flipTextureV, @Nullable NativeTextureResolver textureResolver) {
		if (!isAvailable() || objContent == null || objContent.length == 0) {
			return 0L;
		}
		try {
			return nativeCreateBuilder(objContent, mtlContent, materialGroupTextures, flipTextureV,
					textureResolver == null ? NativeTextureResolver.unresolved() : textureResolver);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not build a native model: {}", throwable.toString());
			return 0L;
		}
	}

	/** Creates a builder from an OBJ document that has no MTL at all. */
	public static long createBuilder(byte[] objContent, boolean flipTextureV,
			@Nullable NativeTextureResolver textureResolver) {
		return createBuilder(objContent, null, false, flipTextureV, textureResolver);
	}

	static void freeBuilder(long handle) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeFreeBuilder(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not release a native builder: {}", throwable.toString());
		}
	}

	/**
	 * Releases a builder created through the native entry points.
	 *
	 * <p>Public so the equivalence harness can drive {@code nativeCreateBuilder} directly and
	 * still clean up. Normal code goes through {@link NativeObjModel}, which owns its handle.</p>
	 */
	public static void freeNativeBuilder(long handle) {
		freeBuilder(handle);
	}

	static void freeResult(long handle) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeFreeResult(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not release a native result: {}", throwable.toString());
		}
	}

	@Nullable
	static float[] getBounds(long handle) {
		if (handle == 0L) {
			return null;
		}
		try {
			return nativeGetBounds(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not read model bounds: {}", throwable.toString());
			return null;
		}
	}

	static void applyTransform(long handle, int operation, double a, double b, double c) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeApplyTransform(handle, operation, a, b, c);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not apply a model transform: {}", throwable.toString());
		}
	}

	static void addTransform(long handle, int shaderType, double x, double y, double z, boolean flipped) {
		if (handle == 0L) {
			return;
		}
		try {
			nativeAddTransform(handle, shaderType, x, y, z, flipped);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not add a model placement: {}", throwable.toString());
		}
	}

	@Nullable
	static NativeObjResult build(long handle) {
		if (handle == 0L) {
			return null;
		}
		try {
			return nativeBuild(handle);
		} catch (Throwable throwable) {
			Init.LOGGER.warn("[wrobj] could not finish a native model: {}", throwable.toString());
			return null;
		}
	}

	/** The native library's own description, or null when it is not loaded. */
	@Nullable
	public static String getNativeVersion() {
		if (!isAvailable()) {
			return null;
		}
		try {
			return nativeVersionString();
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** Exposed for diagnostics: the library file name on this platform. */
	public static String getLibraryFileName() {
		return libraryFileName();
	}

	/** Exposed for diagnostics: the packaged directory name on this platform. */
	public static String getPlatformDirectory() {
		return platformDirectory();
	}
}
