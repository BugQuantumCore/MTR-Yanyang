package org.mtr.nativeobj;

import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lifecycle of the native Vulkan OBJ pipeline.
 *
 * <p>Activation is fully automatic and conservative: the pipeline turns on
 * only when VulkanMod is present and every Vulkan handle resolves, and any
 * failure at any stage leaves the fork's Java renderer exactly as it was,
 * with a single log line explaining why. There is no user toggle — the
 * graphics API being Vulkan IS the switch (VulkanMod detection).</p>
 *
 * <p>The communication transport follows the "Java 与 C++ 通信方式" setting:
 * shared memory by default, Panama FFM on JDK 22+. {@link #isJdk22()} drives
 * every fallback decision.</p>
 */
public final class NativeRuntime {

	private static volatile @Nullable NativeRuntime instance;

	private final WrxRegion region;
	private final VulkanHostAccess host;
	private final boolean sharedMemoryTransport;
	private final @Nullable MethodHandle submitBuilds;
	private final @Nullable MethodHandle submitFrame;
	private final Path nativesDirectory;
	private final Set<Integer> liveModels = new HashSet<>();
	private final AtomicInteger requestIds = new AtomicInteger();

	private NativeRuntime(WrxRegion region, VulkanHostAccess host, boolean sharedMemoryTransport, @Nullable MethodHandle submitBuilds, @Nullable MethodHandle submitFrame, Path nativesDirectory) {
		this.region = region;
		this.host = host;
		this.sharedMemoryTransport = sharedMemoryTransport;
		this.submitBuilds = submitBuilds;
		this.submitFrame = submitFrame;
		this.nativesDirectory = nativesDirectory;
	}

	public static boolean isJdk22() {
		return Runtime.version().feature() >= 22;
	}

	/** True when the native pipeline is active for this session. */
	public static boolean isActive() {
		return instance != null;
	}

	@Nullable
	public static NativeRuntime getInstance() {
		return instance;
	}

	/**
	 * Try to bring the pipeline up. Idempotent; every failure mode logs once
	 * and leaves the Java renderer in charge.
	 */
	public static void initialise() {
		if (instance != null || !"true".equals(System.getProperty("mtr.nativeVulkan", "true"))) {
			return;
		}
		try {
			// 1. the graphics API must be Vulkan — auto-detected through VulkanMod
			if (!isVulkanModLoaded()) {
				return; // silently: GL is the normal path
			}
			final VulkanHostAccess host = VulkanHostAccess.resolve();
			if (host == null) {
				return; // already logged
			}

			// 2. transport from the setting, with the JDK-22 rule applied
			final boolean wantsFfm = Config.getClient().getNativeCommMethod() == org.mtr.config.NativeCommMethod.PANAMA_FFM;
			final boolean useFfm = wantsFfm && isJdk22();
			if (wantsFfm && !isJdk22()) {
				MTR.LOGGER.warn("[wrvk] Panama FFM needs JDK 22+ (this client runs JDK {}); falling back to shared memory", Runtime.version().feature());
			}

			// 3. extract the natives and create the exchange region
			final Path nativesDirectory = extractNatives();
			final WrxRegion region = WrxRegion.create(
					nativesDirectory.resolve("region"), useFfm,
					64L << 20, 8L << 20,
					8L + WrxRegion.FRAME_ITEMS_OFFSET + (long) WrxRegion.FRAME_MAX_ITEMS * WrxRegion.FRAME_ITEM_BYTES);

			// 4. load the library — the constructor boot (shm) or wr_boot (ffm) takes it from here
			System.load(nativesDirectory.resolve(libraryName()).toAbsolutePath().toString());
			@Nullable MethodHandle submitBuilds = null;
			@Nullable MethodHandle submitFrame = null;
			if (useFfm) {
				final MethodHandles.Lookup lookup = MethodHandles.lookup();
				final MethodHandle boot = downcall(lookup, nativesDirectory, "wr_boot", int.class, byte[].class);
				final byte[] configBytes = (nativesDirectory.resolve("region").resolve("wrx.conf").toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8);
				final int bootResult = (int) boot.invokeExact(configBytes);
				if (bootResult != 0) {
					MTR.LOGGER.error("[wrvk] wr_boot failed with {}; the Java renderer stays active", bootResult);
					region.close();
					return;
				}
				submitBuilds = downcall(lookup, nativesDirectory, "wr_submit_builds", int.class);
				submitFrame = downcall(lookup, nativesDirectory, "wr_submit_frame", int.class);
			} else {
				// the POSIX constructor boot / DllMain boot found wrx.conf next to the library
				final long deadline = System.currentTimeMillis() + 3000;
				while (region.nativeHeartbeat() == 0 && System.currentTimeMillis() < deadline) {
					Thread.sleep(10);
				}
				if (region.nativeHeartbeat() == 0) {
					MTR.LOGGER.error("[wrvk] the native poller did not start within 3s; the Java renderer stays active");
					region.close();
					return;
				}
			}

			instance = new NativeRuntime(region, host, !useFfm, submitBuilds, submitFrame, nativesDirectory);

			// 5. shaders, then the Vulkan handles
			instance.uploadShaders();
			instance.sendInitVulkan();
			MTR.LOGGER.info("[wrvk] native Vulkan OBJ pipeline active ({} transport, {} meshes pending)",
					useFfm ? "Panama FFM" : "shared memory", instance.allModels.size());
		} catch (Throwable throwable) {
			MTR.LOGGER.error("[wrvk] native pipeline initialisation failed; the Java renderer stays active", throwable);
			instance = null;
		}
	}

	private static MethodHandle downcall(MethodHandles.Lookup lookup, Path nativesDirectory, String name, Class<?> returnType) throws Throwable {
		// JDK 22+ FFM, resolved reflectively because the mod targets JDK 21 bytecode
		final Class<?> symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup");
		final Class<?> arenaClass = Class.forName("java.lang.foreign.Arena");
		final Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
		final Class<?> segmentClass = Class.forName("java.lang.foreign.MemorySegment");
		final Class<?> methodTypeClass = Class.forName("java.lang.invoke.MethodType");

		final Object arena = arenaClass.getMethod("ofShared").invoke(null);
		final Object libraryLookup = symbolLookupClass.getMethod("libraryLookup", String.class, arenaClass)
				.invoke(null, nativesDirectory.resolve(libraryName()).toAbsolutePath().toString(), arena);
		final Object optional = symbolLookupClass.getMethod("find", String.class).invoke(libraryLookup, name);
		if (optional == null || !(boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
			throw new IllegalStateException("symbol " + name + " not found in " + libraryName());
		}
		final Object address = optional.getClass().getMethod("get").invoke(optional);
		final Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
		final Object methodType = methodTypeClass.getMethod("methodType", Class.class).invoke(null, returnType);
		return (MethodHandle) linkerClass.getMethod("downcallHandle", segmentClass, methodTypeClass).invoke(linker, address, methodType);
	}

	private static boolean isVulkanModLoaded() {
		// fabric loader is on the class path for the fabric builds; the neoforge
		// build fails this probe softly and the GL path stays active
		try {
			final Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
			final Object loader = loaderClass.getMethod("getInstance").invoke(null);
			final Object mods = loaderClass.getMethod("isModLoaded", String.class).invoke(loader, "vulkanmod");
			return Boolean.TRUE.equals(mods);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	private static String libraryName() {
		final String os = System.getProperty("os.name").toLowerCase();
		final String arch = System.getProperty("os.arch");
		return os.contains("win") ? "wrvk.dll" : os.contains("mac") || os.contains("darwin") ? "libwrvk.dylib" : "libwrvk.so";
	}

	/** Extract native/&lt;platform&gt;/wrvk.* from the mod jar into a temp directory. */
	private static Path extractNatives() throws IOException {
		final String os = System.getProperty("os.name").toLowerCase();
		final String arch = System.getProperty("os.arch");
		final String platform = (os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux")
				+ "-" + (arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64");
		final String extension = os.contains("win") ? "wrvk.dll" : os.contains("mac") || os.contains("darwin") ? "wrvk.dylib" : "wrvk.so";
		final Path directory = Files.createTempDirectory("mtr-native-vulkan-" + platform + "-");
		directory.toFile().deleteOnExit();
		final String resourcePath = "/native/" + platform + "/" + extension;
		try (final InputStream stream = NativeRuntime.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IOException("native library not found in the jar: " + resourcePath);
			}
			Files.copy(stream, directory.resolve(extension));
		}
		return directory;
	}

	// ---------------------------------------------------------------------------
	// message helpers
	// ---------------------------------------------------------------------------

	void publish(byte[] message) {
		region.publishBuildMessage(messageType(message), message);
		drainBuilds();
	}

	private static int messageType(byte[] message) {
		return ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
	}

	private void drainBuilds() {
		if (sharedMemoryTransport) {
			// the poller picks this up within its hot interval; wait for the ack
			final long seq = region.buildSeq();
			final long deadline = System.currentTimeMillis() + 10_000;
			while (region.buildAck() < seq && System.currentTimeMillis() < deadline) {
				NativeFrameRecorder.spin(50);
			}
		} else if (submitBuilds != null) {
			try {
				submitBuilds.invoke();
			} catch (Throwable throwable) {
				MTR.LOGGER.error("[wrvk] FFM submit_builds failed", throwable);
				disable();
			}
		}
	}

	private void uploadShaders() throws IOException {
		// blob: u32 count, then per shader u32 key, u32 byteCount, [u32 vertSize][vert][frag]
		final String[][] pairs = {
				{"0", "entity_cutout.vert.spv", "entity_cutout.frag.spv"},
				{"1", "entity_translucent.vert.spv", "entity_translucent.frag.spv"},
				{"2", "beacon_beam.vert.spv", "beacon_beam.frag.spv"},
		};
		buffer.putInt(WrxRegion.MSG_LOAD_SHADERS).putInt(0); // type placeholder for messageType(); patched below
		// NOTE: publish() reads the type from the message itself, so the layout
		// must start with the type word; the byte[] built here starts with it.
		final ByteBuffer body = ByteBuffer.allocate(256 * 1024).order(ByteOrder.LITTLE_ENDIAN);
		body.putInt(pairs.length);
		for (final String[] pair : pairs) {
			final byte[] vert = readResource("/shaders/spirv/" + pair[1]);
			final byte[] frag = readResource("/shaders/spirv/" + pair[2]);
			body.putInt(Integer.parseInt(pair[0]));
			body.putInt(4 + vert.length + frag.length);
			body.putInt(vert.length);
			body.put(vert);
			body.put(frag);
		}
		final byte[] bodyBytes = new byte[body.position()];
		body.flip();
		body.get(bodyBytes);
		final byte[] message = ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(WrxRegion.MSG_LOAD_SHADERS).put(bodyBytes).array();
		publish(message);
	}

	private static byte[] readResource(String path) throws IOException {
		try (final InputStream stream = NativeRuntime.class.getResourceAsStream(path)) {
			if (stream == null) {
				throw new IOException("missing shader resource " + path);
			}
			return stream.readAllBytes();
		}
	}

	private void sendInitVulkan() {
		final ByteBuffer body = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		body.putLong(host.instanceHandle());
		body.putLong(host.physicalDeviceHandle());
		body.putLong(host.deviceHandle());
		body.putLong(host.queueHandle());
		body.putLong(host.boundRenderPass());
		body.putInt(host.queueFamily());
		body.putInt(host.colorFormat());
		body.putInt(130); // depth format: unused by the pipeline, logged only
		body.putInt(1);   // sample count: VulkanMod's main pass is not multisampled
		body.putInt(128); // maxPushConstants: the guaranteed minimum
		final byte[] bodyBytes = new byte[body.position()];
		body.flip();
		body.get(bodyBytes);
		publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(WrxRegion.MSG_INIT_VULKAN).put(bodyBytes).array());
	}

	// ---------------------------------------------------------------------------
	// model registry (leak safety: every id passes through these)
	// ---------------------------------------------------------------------------

	synchronized void trackModel(int modelId) {
		liveModels.add(modelId);
	}

	synchronized void freeAllModels() {
		if (liveModels.isEmpty()) {
			return;
		}
		final ByteBuffer body = ByteBuffer.allocate(8 + 4L * liveModels.size()).order(ByteOrder.LITTLE_ENDIAN);
		body.putInt(requestIds.incrementAndGet());
		body.putInt(liveModels.size());
		for (final int modelId : liveModels) {
			body.putInt(modelId);
		}
		final byte[] bodyBytes = new byte[body.position()];
		body.flip();
		body.get(bodyBytes);
		publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
				.putInt(WrxRegion.MSG_FREE_MODELS).put(bodyBytes).array());
		liveModels.clear();
	}

	// ---------------------------------------------------------------------------
	// frame submission + teardown
	// ---------------------------------------------------------------------------

	/** Render-thread frame drain: write items, then ring the doorbell. */
	public void submitFrame(NativeFrameRecorder.FrameData frame) {
		final long seq = region.beginFrameWrite();
		frame.writeTo(region.frameBuffer());
		region.endFrameWrite(seq);
		if (sharedMemoryTransport) {
			final long deadline = System.currentTimeMillis() + 2;
			while (region.frameAck() < region.frameSeq() && System.currentTimeMillis() < deadline) {
				NativeFrameRecorder.spin(25);
			}
			if (region.frameAck() < region.frameSeq()) {
				// bounded miss: the frame is skipped, never wedged; repeated
				// misses escalate to a clean disable + the Java path
				if (region.frameErrors() > 128) {
					MTR.LOGGER.error("[wrvk] repeated frame losses; disabling the native pipeline for this session");
					disable();
				}
			}
		} else if (submitFrame != null) {
			try {
				submitFrame.invoke();
			} catch (Throwable throwable) {
				MTR.LOGGER.error("[wrvk] FFM submit_frame failed", throwable);
				disable();
			}
		}
	}

	public VulkanHostAccess host() {
		return host;
	}

	/**
	 * Free every native model without tearing the exchange down — called at
	 * the top of CustomResourceLoader.reload(), where every loader is about to
	 * be replaced. The pipeline stays active; the next builds refill it.
	 */
	public static void freeAllModelsForReload() {
		final NativeRuntime runtime = instance;
		if (runtime != null) {
			try {
				runtime.freeAllModels();
			} catch (Throwable throwable) {
				MTR.LOGGER.error("[wrvk] model free failed during resource reload", throwable);
			}
		}
	}

	/**
	 * Disable and free. Called from the render thread on resource reload and
	 * world unload, and from any error path; safe to call repeatedly.
	 */
	public static void shutdown() {
		final NativeRuntime runtime = instance;
		instance = null;
		if (runtime == null) {
			return;
		}
		try {
			runtime.freeAllModels();
		} catch (Throwable throwable) {
			MTR.LOGGER.error("[wrvk] model free failed during shutdown", throwable);
		}
		try {
			runtime.region.close();
		} catch (Throwable throwable) {
			MTR.LOGGER.error("[wrvk] region close failed during shutdown", throwable);
		}
	}

	private static void disable() {
		shutdown();
	}

	/** Test hook: the region directory for protocol verification. */
	public WrxRegion regionForTests() {
		return region;
	}
}
