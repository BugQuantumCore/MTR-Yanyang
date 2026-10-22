package org.mtr.nativeobj;

import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Comparator;
import java.util.stream.Stream;

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
 * every fallback decision, and a failed boot is latched for the whole client
 * session so {@code MainRenderer.render}'s per-frame {@link #initialise()}
 * call can never turn into a re-extract/re-load/re-map spiral.</p>
 *
 * <p>Lifecycle: one boot per session; resource reloads free the native
 * models but keep the exchange alive ({@link #freeAllModelsForReload}); a
 * client disconnect tears the whole native side down
 * ({@link #shutdown()}), and a subsequent reconnect boots a fresh one.</p>
 */
public final class NativeRuntime {

        private static volatile @Nullable NativeRuntime instance;
        private static volatile boolean bootAttempted;
        private static volatile boolean teardownPending;
        /**
         * The JDK 22+ FFM arena that owns the library lookups and the
         * downcall address segments. Kept for the whole session: closing
         * it would invalidate every returned downcall handle (their
         * address segments share the arena's lifetime), so it closes
         * only at teardown.
         */
        private static volatile @Nullable Object ffmArena;

        private final WrxRegion region;
        private final VulkanHostAccess host;
        private final boolean sharedMemoryTransport;
        private final @Nullable MethodHandle submitBuilds;
        private final @Nullable MethodHandle submitFrame;
        private final @Nullable MethodHandle nativeShutdown;
        private final Path nativesDirectory;
        private final Set<Integer> liveModels = new HashSet<>();
        private final Map<Integer, PendingReply> pendingReplies = new ConcurrentHashMap<>();
        private final AtomicInteger requestIds = new AtomicInteger();
        private boolean ackBroken;

        private static final long BUILD_ACK_MILLIS = 10_000;
        private static final long FRAME_ACK_MILLIS = 50;
        private static final long FRAME_ACK_GRACE_MILLIS = 200;

        private NativeRuntime(WrxRegion region, VulkanHostAccess host, boolean sharedMemoryTransport, @Nullable MethodHandle submitBuilds, @Nullable MethodHandle submitFrame, @Nullable MethodHandle nativeShutdown, Path nativesDirectory) {
                this.region = region;
                this.host = host;
                this.sharedMemoryTransport = sharedMemoryTransport;
                this.submitBuilds = submitBuilds;
                this.submitFrame = submitFrame;
                this.nativeShutdown = nativeShutdown;
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

        public VulkanHostAccess host() {
                return host;
        }

        /**
         * Try to bring the pipeline up. Called once per frame from
         * {@code MainRenderer.render}; attempts are latched per session, so
         * this is a constant-time check after the first try.
         */
        public static void initialise() {
                if (instance != null || bootAttempted || !"true".equals(System.getProperty("mtr.nativeVulkan", "true"))) {
                        return;
                }
                try {
                        // 1. the graphics API must be Vulkan — auto-detected through VulkanMod
                        if (!isVulkanModLoaded()) {
                                return; // silently: GL is the normal path, nothing to latch
                        }
                        // From here on the environment claims Vulkan; whatever
                        // fails, do not retry this session (per-frame callers).
                        bootAttempted = true;

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

                        // 3. extract the library, create the region files and the
                        //    wrx.conf sidecar the native constructor boot reads
                        final Path nativesDirectory = extractNatives();
                        final Path regionDirectory = nativesDirectory.resolve("region");
                        final WrxRegion region = WrxRegion.create(regionDirectory, nativesDirectory, useFfm,
                                        64L << 20, 8L << 20,
                                        8L + WrxRegion.FRAME_ITEMS_OFFSET + (long) WrxRegion.FRAME_MAX_ITEMS * WrxRegion.FRAME_ITEM_BYTES);

                        // 4. load the library — the constructor boot (shm) or
                        //    wr_boot (ffm) takes it from here
                        System.load(nativesDirectory.resolve(libraryName()).toAbsolutePath().toString());
                        @Nullable MethodHandle submitBuilds = null;
                        @Nullable MethodHandle submitFrame = null;
                        @Nullable MethodHandle nativeShutdown = null;
                        if (useFfm) {
                                sharedFfmArena(); // created once; closes at teardown
                                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                                final MethodHandle boot = downcall(lookup, nativesDirectory, "wr_boot", int.class, Class.forName("java.lang.foreign.MemorySegment"));
                                final Object configSegment = utf8Segment(ffmArena, nativesDirectory.resolve("wrx.conf").toAbsolutePath().toString());
                                final int bootResult = (Integer) boot.invoke(configSegment);
                                if (bootResult != 0) {
                                        MTR.LOGGER.error("[wrvk] wr_boot failed with {}; the Java renderer stays active", bootResult);
                                        region.close();
                                        return;
                                }
                                submitBuilds = downcall(lookup, nativesDirectory, "wr_submit_builds", int.class);
                                submitFrame = downcall(lookup, nativesDirectory, "wr_submit_frame", int.class);
                        } else {
                                // the POSIX constructor boot / DllMain boot found
                                // wrx.conf next to the library and started the poller
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
                        // The shutdown downcall exists on every JDK >= 22
                        // (independent of the transport); the queue-based
                        // MSG_SHUTDOWN below covers the older JDKs.
                        if (isJdk22()) {
                                try {
                                        nativeShutdown = downcall(MethodHandles.lookup(), nativesDirectory, "wr_shutdown", int.class);
                                } catch (Throwable throwable) {
                                        MTR.LOGGER.warn("[wrvk] wr_shutdown downcall unavailable; the queue shutdown path will be used", throwable);
                                }
                        }

                        final NativeRuntime runtime = new NativeRuntime(region, host, !useFfm, submitBuilds, submitFrame, nativeShutdown, nativesDirectory);

                        // 5. shaders, then the Vulkan handles; both travel as
                        //    build messages so they are processed before any model
                        runtime.uploadShaders();
                        runtime.sendInitVulkan();

                        // 6. the recorder must have attached (the Vulkan loader
                        //    resolves through VulkanMod's instance); a missing
                        //    FLAG_VULKAN_READY here means the native side can
                        //    never record — fail fast and stay on the GL path
                        final long readyDeadline = System.currentTimeMillis() + 2000;
                        while (!region.flagSet(WrxRegion.FLAG_VULKAN_READY) && System.currentTimeMillis() < readyDeadline) {
                                NativeFrameRecorder.spin(250);
                        }
                        if (!region.flagSet(WrxRegion.FLAG_VULKAN_READY)) {
                                MTR.LOGGER.error("[wrvk] the native Vulkan recorder did not attach (instance handle or loader problem); the Java renderer stays active");
                                runtime.teardown();
                                return;
                        }

                        instance = runtime;
                        MTR.LOGGER.info("[wrvk] native Vulkan OBJ pipeline active ({} transport)",
                                        useFfm ? "Panama FFM" : "shared memory");
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native pipeline initialisation failed; the Java renderer stays active", throwable);
                        instance = null;
                }
        }

        private static MethodHandle downcall(MethodHandles.Lookup lookup, Path nativesDirectory, String name, Class<?> returnType, Class<?>... parameterTypes) throws Throwable {
                // JDK 22+ FFM, resolved reflectively because the mod targets JDK 21 bytecode.
                // The address segments the returned handles carry are bound to the
                // shared arena above, so it must outlive every call.
                final Class<?> symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup");
                final Class<?> arenaClass = Class.forName("java.lang.foreign.Arena");
                final Class<?> linkerClass = Class.forName("java.lang.foreign.Linker");
                final Class<?> methodTypeClass = Class.forName("java.lang.invoke.MethodType");

                final Object libraryLookup = symbolLookupClass.getMethod("libraryLookup", String.class, arenaClass)
                                .invoke(null, nativesDirectory.resolve(libraryName()).toAbsolutePath().toString(), ffmArena);
                final Object optional = symbolLookupClass.getMethod("find", String.class).invoke(libraryLookup, name);
                if (optional == null || !(boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
                        throw new IllegalStateException("symbol " + name + " not found in " + libraryName());
                }
                final Object address = optional.getClass().getMethod("get").invoke(optional);
                final Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
                final Object methodType = methodTypeClass.getMethod("methodType", Class.class, Class[].class).invoke(null, returnType, parameterTypes);
                return (MethodHandle) linkerClass.getMethod("downcallHandle", Class.forName("java.lang.foreign.MemorySegment"), methodTypeClass).invoke(linker, address, methodType);
        }

        /** The shared FFM arena, created once per session on JDK 22+. */
        @Nullable
        private static Object sharedFfmArena() throws Throwable {
                Object arena = ffmArena;
                if (arena == null) {
                        arena = Class.forName("java.lang.foreign.Arena").getMethod("ofShared").invoke(null);
                        ffmArena = arena;
                }
                return arena;
            }

        private static void closeFfmArena() {
                final Object arena = ffmArena;
                ffmArena = null;
                if (arena != null) {
                        try {
                                arena.getClass().getMethod("close").invoke(arena);
                        } catch (Throwable ignored) {
                        }
                }
        }

        /**
         * A {@code MemorySegment} holding a NUL-terminated UTF-8 C string
         * inside the shared FFM arena, through the JDK 22+ FFM API
         * ({@code SegmentAllocator.allocateFrom}).
         */
        private static Object utf8Segment(Object arena, String value) throws Throwable {
                return arena.getClass().getMethod("allocateFrom", String.class).invoke(arena, value);
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
                return os.contains("win") ? "wrvk.dll" : os.contains("mac") || os.contains("darwin") ? "libwrvk.dylib" : "libwrvk.so";
        }

        /**
         * Extract {@code native/<platform>/<library>} from the mod jar into a
         * temp directory, under exactly the name {@link System#load} expects.
         */
        private static Path extractNatives() throws IOException {
                final String os = System.getProperty("os.name").toLowerCase();
                final String arch = System.getProperty("os.arch");
                final String platform = (os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux")
                                + "-" + (arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64");
                final String libraryName = libraryName();
                final Path directory = Files.createTempDirectory("mtr-native-vulkan-" + platform + "-");
                directory.toFile().deleteOnExit();
                final String resourcePath = "/native/" + platform + "/" + libraryName;
                try (final InputStream stream = NativeRuntime.class.getResourceAsStream(resourcePath)) {
                        if (stream == null) {
                                throw new IOException("native library not found in the jar: " + resourcePath);
                        }
                        Files.copy(stream, directory.resolve(libraryName));
                }
                return directory;
        }

        // ---------------------------------------------------------------------------
        // message helpers
        // ---------------------------------------------------------------------------

        /** Publish a build message and wait for the native side to process it. */
        void publish(byte[] message) {
                region.publishBuildMessage(messageType(message), message);
                drainBuilds();
        }

        private static int messageType(byte[] message) {
                return ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        }

        private void drainBuilds() {
                if (sharedMemoryTransport) {
                        if (ackBroken) {
                                return;
                        }
                        // the poller picks this up within its hot interval; wait for the ack
                        final long seq = region.buildSeq();
                        final long deadline = System.currentTimeMillis() + BUILD_ACK_MILLIS;
                        while (region.buildAck() < seq && System.currentTimeMillis() < deadline) {
                                NativeFrameRecorder.spin(50);
                        }
                        if (region.buildAck() < seq) {
                                // One lost ack desynchronises the counters forever
                                // (ack would stay below seq for every later
                                // message), so stop waiting at all instead of
                                // stalling every future publish for 10 seconds.
                                ackBroken = true;
                                MTR.LOGGER.warn("[wrvk] build ack timed out after {}ms; stop waiting for build acks this session", BUILD_ACK_MILLIS);
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
                publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(WrxRegion.MSG_LOAD_SHADERS).put(bodyBytes).array());
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
        // replies: pending-replies routing so concurrent loaders cannot lose
        // each other's answers (model parses run on virtual worker threads)
        // ---------------------------------------------------------------------------

        private record PendingReply(int type, byte[] payload) {
        }

        /** Drain the results queue into the pending map; safe from any thread. */
        private void drainResults() {
                for (final WrxRegion.ResultMessage message : region.consumeResults()) {
                        final ByteBuffer payload = ByteBuffer.wrap(message.payload()).order(ByteOrder.LITTLE_ENDIAN);
                        if (payload.remaining() >= 4) {
                                pendingReplies.put(payload.getInt(0), new PendingReply(message.type(), message.payload()));
                        }
                }
        }

        /**
         * Wait for the reply to a request id. Every result — matching or not —
         * is kept in the pending map, so concurrent callers never discard each
         * other's answers.
         */
        @Nullable
        ByteBuffer awaitResult(int requestId, long timeoutMillis) {
                final long deadline = System.currentTimeMillis() + timeoutMillis;
                PendingReply reply = pendingReplies.remove(requestId);
                while (reply == null && System.currentTimeMillis() < deadline && instance != null) {
                        drainResults();
                        reply = pendingReplies.remove(requestId);
                        if (reply == null) {
                                NativeFrameRecorder.spin(250);
                        }
                }
                if (reply == null) {
                        MTR.LOGGER.error("[wrvk] native build reply for request {} timed out", requestId);
                        return null;
                }
                if (reply.type() == WrxRegion.RES_ERROR) {
                        MTR.LOGGER.error("[wrvk] native build reported error for request {}", requestId);
                        return null;
                }
                // skip the echoed request id; the caller reads the payload body
                final ByteBuffer payload = ByteBuffer.wrap(reply.payload()).order(ByteOrder.LITTLE_ENDIAN);
                payload.position(4);
                return payload;
        }

        // ---------------------------------------------------------------------------
        // model registry (leak safety: every id passes through these)
        // ---------------------------------------------------------------------------

        synchronized void trackModel(int modelId) {
                liveModels.add(modelId);
        }

        private int nextRequestId() {
                return requestIds.incrementAndGet();
        }

        synchronized void freeAllModels() {
                if (liveModels.isEmpty()) {
                        return;
                }
                final int requestId = nextRequestId();
                final ByteBuffer body = ByteBuffer.allocate(8 + 4 * liveModels.size()).order(ByteOrder.LITTLE_ENDIAN);
                body.putInt(requestId);
                body.putInt(liveModels.size());
                for (final int modelId : liveModels) {
                        body.putInt(modelId);
                }
                final byte[] bodyBytes = new byte[body.position()];
                body.flip();
                body.get(bodyBytes);
                liveModels.clear();
                publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(WrxRegion.MSG_FREE_MODELS).put(bodyBytes).array());
                // No reply is expected (request ids are monotonic); the build
                // ack inside publish() is the completion handshake.
        }

        // ---------------------------------------------------------------------------
        // frame submission + teardown
        // ---------------------------------------------------------------------------

        /** Render-thread frame drain: write items, then ring the doorbell. */
        public void submitFrame(NativeFrameRecorder.FrameData frame) {
                final long seq = region.beginFrameWrite();
                if (seq < 0) {
                        return; // closed between disable() and teardown
                }
                frame.writeTo(region.frameBuffer());
                region.endFrameWrite(seq);
                if (sharedMemoryTransport) {
                        final long published = region.frameSeq();
                        final long deadline = System.currentTimeMillis() + FRAME_ACK_MILLIS;
                        while (region.frameAck() < published && System.currentTimeMillis() < deadline) {
                                NativeFrameRecorder.spin(25);
                        }
                        if (region.frameAck() < published) {
                                // The poller is either wedged or gone. Recording
                                // into VulkanMod's command buffer from the native
                                // thread while this thread resumes MC's own GL
                                // recording would race the buffer, so: stop the
                                // pipeline, then wait the grace period for the
                                // ack processFrame always writes (success or
                                // failure) before returning to MC rendering.
                                MTR.LOGGER.error("[wrvk] frame ack missed; disabling the native pipeline for this session");
                                disable();
                                final long graceDeadline = System.currentTimeMillis() + FRAME_ACK_GRACE_MILLIS;
                                while (region.frameAck() < published && System.currentTimeMillis() < graceDeadline) {
                                        NativeFrameRecorder.spin(50);
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
         * Mark the pipeline dead. New work stops immediately; the region is
         * torn down either inline (when called from the render thread, which
         * is between drains) or by the render thread at the start of the next
         * frame — never from a worker thread that might be mid-queue.
         */
        static void disable() {
                // Drop the instance so no new work starts. The region stays
                // mapped until the render thread finalises the teardown
                // (tickTeardown) — never from a worker thread that might be
                // mid-queue, and never between a drain's begin/end frame write.
                instance = null;
                teardownPending = true;
        }

        /**
         * Full teardown: free native models, tell the native side to stop
         * (wr_shutdown downcall when the FFM API is available, otherwise the
         * queue-based MSG_SHUTDOWN handshake), unmap, and best-effort delete
         * the temp directory. Safe to call repeatedly.
         */
        public static void shutdown() {
                instance = null;
                teardownPending = true;
                tickTeardown();
        }

        /**
         * Runs on the render thread each frame (from MainRenderer.render):
         * finalises a deferred teardown. Constant-time when nothing pends.
         */
        public static void tickTeardown() {
                if (!teardownPending) {
                        return;
                }
                teardownPending = false;
                final NativeRuntime runtime = instance;
                instance = null;
                if (runtime == null) {
                        return;
                }
                runtime.teardown();
        }

        private void teardown() {
                try {
                        freeAllModels();
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] model free failed during teardown", throwable);
                }
                pendingReplies.clear();
                try {
                        if (nativeShutdown != null) {
                                nativeShutdown.invoke();
                        } else {
                                // JDK < 22: shutdown through the build queue — the
                                // poller acks the message, leaves its loop, and
                                // tears the server down outside every lock
                                region.publishBuildMessage(WrxRegion.MSG_SHUTDOWN, new byte[0]);
                                final long seq = region.buildSeq();
                                final long deadline = System.currentTimeMillis() + 2000;
                                while (region.buildAck() < seq && System.currentTimeMillis() < deadline) {
                                        NativeFrameRecorder.spin(50);
                                }
                        }
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native shutdown signalling failed", throwable);
                }
                try {
                        region.close();
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] region close failed during shutdown", throwable);
                }
                closeFfmArena();
                deleteRecursively(nativesDirectory);
        }

        private static void deleteRecursively(Path directory) {
                // best effort only — on Windows the loaded library keeps its
                // file locked until the process exits, and the OS temp cleaner
                // covers the rest
                try (final Stream<Path> stream = Files.walk(directory)) {
                        stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                                try {
                                        Files.delete(path);
                                } catch (IOException ignored) {
                                }
                        });
                } catch (IOException ignored) {
                }
        }
}
