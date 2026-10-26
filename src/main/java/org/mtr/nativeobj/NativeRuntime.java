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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
 * <p>Protocol v2 ("zero-Java model rendering"): textures live in a native
 * pool uploaded once ({@code NativeTextureManager}), and the frame recorder
 * only appends raw pose matrices — the C++ recorder owns every matrix,
 * colour and texture computation. See {@code docs/NATIVE_VULKAN.md}.</p>
 *
 * <p>Activation is fully automatic and conservative: the pipeline turns on
 * only when VulkanMod is present and every Vulkan handle resolves, and any
 * failure at any stage leaves the fork's Java renderer exactly as it was,
 * with a single log line explaining why. There is no user toggle — the
 * graphics API being Vulkan IS the switch (VulkanMod detection). One rule
 * bounds the whole lifecycle: the attach happens at boot time only (the
 * first resource reload / first frame window), never mid-session — while
 * Beryl's shaders are driving the frame the boot is latched OFF for the
 * session (see {@link #initialise()}), because attaching to VulkanMod's
 * live device in the transition frame after Beryl hands the frame back
 * crashes at the driver level with no recoverable report.</p>
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
         * The runtime parked by {@link #disable()} / {@link #shutdown()} for the
         * render thread's deferred teardown. The previous flow nulled
         * {@link #instance} before {@link #tickTeardown()} could read it, so
         * the "deferred teardown" was unreachable dead code and every disable
         * leaked the native poller thread, its mappings, the native models
         * and the temp directory for the rest of the process.
         */
        private static volatile @Nullable NativeRuntime teardownTarget;
        /** One-shot log for the session-long Beryl deferral latch (the skip itself repeats silently). */
        private static volatile boolean berylSkipLogged;
        /**
         * The {@code mtr.nativeVulkan} property, read once at class init — the
         * per-frame gate in {@link #initialise()} stays a constant-time field read.
         */
        private static final boolean NATIVE_VULKAN_ENABLED = "true".equals(System.getProperty("mtr.nativeVulkan", "true"));
        /**
         * VulkanMod presence, probed once at class init. The deferral paths in
         * {@link #initialise()} (no VulkanMod, or Beryl active — which never
         * latch {@link #bootAttempted}) used to repeat the {@code Class.forName}
         * plus two FabricLoader {@code Method.invoke} calls on EVERY frame.
         */
        private static final boolean VULKAN_MOD_LOADED = isVulkanModLoaded();
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
                if (instance != null || bootAttempted || !NATIVE_VULKAN_ENABLED) {
                        return;
                }
                // Hoisted out of the try so the catch-all below can unmap the
                // region on every failure path between creation and publication.
                WrxRegion region = null;
                try {
                        // 1. the graphics API must be Vulkan — auto-detected through VulkanMod
                        if (!VULKAN_MOD_LOADED) {
                                return; // silently: GL is the normal path, nothing to latch
                        }

                        // Beryl replaces every entity-family vanilla shader with its own
                        // G-buffer pipelines and drives the frame through its own render
                        // passes. Recording MTR geometry straight into VulkanMod's command
                        // buffer (the native path) would bypass Beryl entirely — unshaded,
                        // and against render passes the native pipeline was never built
                        // for. The Java renderer draws MTR models through Beryl's
                        // own pipelines instead: correctly positioned AND shaded
                        // by Beryl.
                        //
                        // This LATCHES the boot for the rest of the session: attaching the
                        // native Vulkan recorder MID-SESSION — in the very frame Beryl
                        // hands the frame back to VulkanMod, while its render passes and
                        // command buffers are still mid-transition — has proven fatal at
                        // the driver level (the process dies outright: no Minecraft crash
                        // report and no JVM hs_err; user-verified on VulkanMod 0.6.7 +
                        // Beryl 0.2.0-alpha, 2026-10-26). A session that starts with Beryl
                        // shaders on therefore keeps the Java renderer for good, even after
                        // the shaders are toggled off; shutdown() re-arms the latch on
                        // disconnect so a fresh world join boots the native pipeline
                        // cleanly when shaders are off. (A Beryl jar with shaders OFF never
                        // reaches this branch and keeps the always-on behaviour.)
                        if (org.mtr.client.VulkanCompat.isBerylActive()) {
                                bootAttempted = true;
                                if (!berylSkipLogged) {
                                        berylSkipLogged = true;
                                        MTR.LOGGER.info("[wrvk] Beryl shader pipeline detected; the native Vulkan recorder stays off for this session (a mid-session attach after Beryl hands the frame back crashes at the driver level) — the Java renderer draws MTR geometry through Beryl's pipelines; reconnect with shaders off to boot the native pipeline");
                                }
                                return;
                        }

                        // From here on the environment claims Vulkan and Beryl is not
                        // driving the frame; whatever fails, do not retry this session
                        // (per-frame callers).
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
                        region = WrxRegion.create(regionDirectory, nativesDirectory, useFfm,
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
                        // MSG_SHUTDOWN below covers the older JDKs. The shared
                        // arena must exist for the library lookup even when the
                        // transport is shared memory (it is only created inside
                        // the FFM branch above) — without it the reflective
                        // SymbolLookup.libraryLookup receives a null Arena and
                        // the downcall is spurned with an NPE in the log on every
                        // shared-memory boot (the arena closes again at teardown).
                        if (isJdk22()) {
                                try {
                                        sharedFfmArena();
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
                        // A failure between region creation and publication (library
                        // load, FFM downcalls, shader upload, heartbeat wait, queue
                        // overflow) must not leak the mapping — and, once the library
                        // started its poller, that poller thread for the whole process
                        // lifetime. Ask the poller to stop best-effort before
                        // unmapping; each step is guarded so a failure here can never
                        // mask the original exception above.
                        if (region != null) {
                                try {
                                        region.publishBuildMessage(WrxRegion.MSG_SHUTDOWN, new byte[0]);
                                        final long seq = region.buildSeq();
                                        final long deadline = System.currentTimeMillis() + 2000;
                                        while (region.buildAck() < seq && System.currentTimeMillis() < deadline) {
                                                NativeFrameRecorder.spin(50);
                                        }
                                } catch (Throwable ignored) {
                                        // best effort — the poller may never have started
                                }
                                try {
                                        region.close();
                                } catch (Throwable ignored) {
                                }
                        }
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
                } catch (Throwable e) {
                        // broadened from ReflectiveOperationException: the probe now also
                        // runs at class init (VULKAN_MOD_LOADED), where an escaping
                        // throwable would fail the whole class instead of just this
                        // probe — every failure still means "not loaded" (GL path)
                        return false;
                }
        }

        private static String libraryName() {
                final String os = System.getProperty("os.name").toLowerCase();
                return os.contains("win") ? "wrvk.dll"
                                : os.contains("mac") || os.contains("darwin") || os.contains("ios") || os.contains("ipados") ? "libwrvk.dylib"
                                : "libwrvk.so";
        }

        /**
         * The full platform key: the jar layout under {@code native/} names
         * eight targets — windows-x64, windows-arm64, linux-x64, linux-arm64,
         * macos-arm64, android-arm64, ios-arm64 and ipados-arm64.
         *
         * <p>Android and iOS are the two JVM-on-mobile families where the
         * {@code os.name} / {@code os.arch} probes lie: Android JVMs report
         * {@code "Linux"}+{@code "aarch64"} even when they are desktop-style
         * OpenJDK builds running inside a launcher (Pojav family), and iOS
         * JVMs report a spoofed {@code "Darwin"}/{@code "Mac OS X"} because
         * the JVM itself is a macOS build. The probes below resolve the real
         * family: {@code /system/build.prop} exists only on Android devices,
         * {@code /var/mobile} (the app sandbox root) exists only on iOS and
         * iPadOS, and {@code uname -m} reports the device model ("iPad…",
         * "iPhone…") there. Every probe is a best effort — a miss simply
         * falls through to the desktop family, and a wrong pick fails the
         * resource lookup, which disables the native path with a log line
         * instead of crashing (see {@link #extractNatives}).</p>
         */
        private static String detectPlatform() {
                final String os = System.getProperty("os.name").toLowerCase();
                final String arch = System.getProperty("os.arch", "");
                final String archSuffix = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";

                if (os.contains("win")) {
                        return "windows-" + archSuffix;
                }

                if (os.contains("mac") || os.contains("darwin") || os.contains("ios") || os.contains("ipados")) {
                        if (isIosFamily()) {
                                return (isIpad() ? "ipados-" : "ios-") + archSuffix;
                        }
                        return "macos-" + archSuffix;
                }

                if (os.contains("android") || isAndroid()) {
                        return "android-" + archSuffix;
                }

                return "linux-" + archSuffix;
        }

        /**
         * True when the JVM runs on Android — either on ART itself (Dalvik / an
         * Android vendor string) or as a desktop OpenJDK inside an Android
         * launcher, where {@code /system/build.prop} is the reliable mark: it
         * exists on every Android device and never on a desktop Linux.
         */
        private static boolean isAndroid() {
                final String vmName = System.getProperty("java.vm.name", "").toLowerCase();
                final String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase();
                if (vmName.contains("dalvik") || vmVendor.contains("android")) {
                        return true;
                }
                try {
                        return Files.isReadable(Path.of("/system", "build.prop"));
                } catch (Throwable ignored) {
                        return false;
                }
        }

        /**
         * True when the JVM runs on iOS or iPadOS. {@code /var/mobile} is the
         * app sandbox root on both — it never exists on macOS. A JVM that
         * spells {@code os.name} as "iOS"/"iPadOS" also lands here.
         */
        private static boolean isIosFamily() {
                try {
                        return Files.isDirectory(Path.of("/var", "mobile"))
                                        || Files.isDirectory(Path.of("/private", "var", "mobile"));
                } catch (Throwable ignored) {
                        return false;
                }
        }

        /**
         * True on iPad specifically: {@code uname -m} reports the Apple
         * device model on iOS/iPadOS ("iPad13,4", "iPhone14,2", …) while on
         * macOS it reports the cpu ("arm64"). The binaries are identical —
         * this pick only decides which jar resource ({@code ipados-arm64} vs
         * {@code ios-arm64}) is extracted — so a failed probe defaults to
         * iPhone and still loads on an iPad.
         */
        private static boolean isIpad() {
                try {
                        final Process process = new ProcessBuilder("uname", "-m").start();
                        final String machine = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().toLowerCase();
                        process.waitFor();
                        return machine.startsWith("ipad");
                } catch (Throwable ignored) {
                        // no /bin/uname in the sandbox or spawning is denied — treat as iPhone
                }
                return false;
        }

        /**
         * Extract {@code native/<platform>/<library>} from the mod jar into a
         * temp directory, under exactly the name {@link System#load} expects.
         */
        private static Path extractNatives() throws IOException {
                final String platform = detectPlatform();
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

        /**
         * Publish a build message and wait for the native side to process it.
         *
         * <p>Wire format: every caller builds {@code [u32 type][body]}, but the
         * queue header already carries the type — the native reader expects the
         * payload to start with the body itself (requestId, instance, key or
         * count at payload offset 0), so the 4-byte type prefix is stripped
         * here and never reaches the payload.</p>
         */
        void publish(byte[] message) {
                region.publishBuildMessage(messageType(message), Arrays.copyOfRange(message, 4, message.length));
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
                        try {
                                // v2: the models referencing the pool textures are gone —
                                // retire the pool entries too (keys stay assigned; the next
                                // register re-uploads)
                                NativeTextureManager.INSTANCE.freePool();
                        } catch (Throwable throwable) {
                                MTR.LOGGER.error("[wrvk] texture pool free failed during resource reload", throwable);
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
                // Park the runtime for the deferred teardown BEFORE dropping the
                // instance — the previous flow nulled instance first, so
                // tickTeardown's read always saw null and the teardown never ran.
                // The region stays mapped until the render thread finalises the
                // teardown (tickTeardown) — never from a worker thread that might
                // be mid-queue, and never between a drain's begin/end frame write.
                final NativeRuntime runtime = instance;
                if (runtime != null) {
                        parkForTeardown(runtime);
                }
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
                final NativeRuntime runtime = instance;
                if (runtime != null) {
                        parkForTeardown(runtime);
                }
                instance = null;
                teardownPending = true;
                // A disconnect tears this native session down for good — re-arm
                // the boot latch so a later reconnect boots a fresh pipeline
                // instead of inheriting the dead session.
                bootAttempted = false;
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
                // Consume the slot disable()/shutdown() parked. The live instance
                // reference is deliberately left alone: a fresh boot (possible
                // once shutdown() re-armed the latch) must never be killed here
                // by a stale pending flag.
                final NativeRuntime runtime = teardownTarget;
                teardownTarget = null;
                if (runtime == null) {
                        return;
                }
                runtime.teardown();
        }

        /**
         * Park {@code runtime} in the deferred-teardown slot for the render
         * thread's {@link #tickTeardown()}. When a previous disable parked a
         * runtime that was never consumed (two disables inside one frame),
         * that one is torn down best-effort right here so two runtimes can
         * never pile up and leak.
         */
        private static void parkForTeardown(NativeRuntime runtime) {
                final NativeRuntime parked = teardownTarget;
                if (parked != null && parked != runtime) {
                        try {
                                parked.teardown();
                        } catch (Throwable throwable) {
                                MTR.LOGGER.error("[wrvk] deferred teardown of a parked runtime failed", throwable);
                        }
                }
                teardownTarget = runtime;
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
