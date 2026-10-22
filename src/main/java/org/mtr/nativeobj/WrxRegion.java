package org.mtr.nativeobj;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java-side mirror of native/include/wrx/wrx.hpp. Every constant has a C++
 * counterpart; the layout here and the {@code static_assert}s there keep the
 * two sides from drifting apart silently.
 *
 * <p>The region is four memory-mapped files in a per-session temp directory;
 * Java creates and maps them before {@code System.load}, and the native
 * library maps the same files — the "C++ reads Java's data directly in
 * memory" channel. No JNI, no per-byte copies.</p>
 *
 * <p>The native side locates the region through the {@code wrx.conf} sidecar,
 * which must sit <b>next to the library</b> (the constructor-time boot on the
 * POSIX side and the DllMain boot on Windows both resolve it relative to the
 * loaded library file) and must contain a {@code directory=<abs region dir>}
 * line — see {@code wrx_region.cpp:parseConfig}. {@link #create} writes it
 * into {@code libraryDirectory} accordingly.</p>
 *
 * <p>Threading: the native protocol is SPSC on both queues (Java writes the
 * build queue, C++ writes the results queue), but several Java threads — the
 * render thread plus the model loaders' virtual workers — publish and
 * consume, so every operation is {@code synchronized} here. The locks are
 * never held across the native boundary.</p>
 */
public final class WrxRegion implements AutoCloseable {

        public static final int CONTROL_MAGIC = 0x57525831; // "WRX1"
        public static final int PROTOCOL_VERSION = 1;
        public static final int CONTROL_SIZE = 4096;

        public static final int FLAG_TRANSPORT_FFM = 1 << 0;
        public static final int FLAG_POLLER_STARTED = 1 << 1;
        public static final int FLAG_VULKAN_READY = 1 << 2;
        public static final int FLAG_ERROR = 1 << 3;
        public static final int FLAG_SHUTDOWN = 1 << 4;

        // control.bin offsets
        public static final int OFF_MAGIC = 0;
        public static final int OFF_VERSION = 4;
        public static final int OFF_FLAGS = 8;
        public static final int OFF_ERROR = 12;
        public static final int OFF_NATIVE_HEARTBEAT = 16;
        public static final int OFF_JAVA_HEARTBEAT = 24;
        public static final int OFF_BUILD_SEQ = 32;
        public static final int OFF_BUILD_ACK = 40;
        public static final int OFF_BUILD_RESULT_SEQ = 48;
        public static final int OFF_BUILD_RESULT_ACK = 56;
        public static final int OFF_FRAME_SEQ = 64;
        public static final int OFF_FRAME_ACK = 72;
        public static final int OFF_FRAME_ERRORS = 80;

        // bump queues: [u64 writePos][u64 readPos][u64 capacity][payload...]
        public static final int QUEUE_HEADER_BYTES = 24;
        public static final int MESSAGE_HEADER_BYTES = 8;

        public static final int MSG_INIT_VULKAN = 1;
        public static final int MSG_LOAD_SHADERS = 2;
        public static final int MSG_LOAD_OBJ = 3;
        public static final int MSG_INSTANCE = 4;
        public static final int MSG_FREE_MODELS = 5;
        /**
         * Shutdown request through the build queue itself — the only teardown
         * channel that works on every JDK, because it needs nothing but the
         * mapped region. The C++ poller observes the flag, acks the message
         * through the regular build ack, and then tears the server down
         * outside every lock (see wrx_server.cpp).
         */
        public static final int MSG_SHUTDOWN = 6;

        public static final int RES_MODELS = 1;
        public static final int RES_INSTANCE = 2;
        public static final int RES_ERROR = 3;

        // frame.bin payload (after the 8-byte seq word) — matches C++ FrameHeader
        public static final int FRAME_MAGIC = 0x46524D31; // "FRM1"
        public static final int FRAME_MAX_ITEMS = 65536;
        public static final int FRAME_MAX_TEXTURES = 1024;
        public static final int FRAME_HEADER_BYTES = 176;
        public static final int FRAME_ITEM_BYTES = 128;
        public static final int FRAME_TEXTURE_BYTES = 40;
        public static final int FRAME_TEXTURES_OFFSET = FRAME_HEADER_BYTES;
        public static final int FRAME_ITEMS_OFFSET = FRAME_HEADER_BYTES + FRAME_MAX_TEXTURES * FRAME_TEXTURE_BYTES;

        public static final int VERTEX_BYTES = 24;

        public final Path directory;
        private MappedByteBuffer control;
        private MappedByteBuffer build;
        private MappedByteBuffer results;
        private MappedByteBuffer frame;
        private final long buildCapacity;
        private boolean closed;

        /**
         * Create the four region files with their headers, then write the
         * wrx.conf sidecar where the native constructor boot looks for it:
         * next to the library, with the region directory spelled out.
         *
         * @param directory         the region directory (the four .bin files)
         * @param libraryDirectory  the directory the native library is extracted
         *                           into; wrx.conf must live here
         * @param transportFfm      {@code true} when the Panama FFM transport
         *                           drives the server synchronously (no poller)
         */
        public static WrxRegion create(Path directory, Path libraryDirectory, boolean transportFfm, long buildBytes, long resultsBytes, long frameBytes) throws IOException {
                Files.createDirectories(directory);
                Files.createDirectories(libraryDirectory);
                final Path controlPath = directory.resolve("control.bin");
                final Path buildPath = directory.resolve("build.bin");
                final Path resultsPath = directory.resolve("results.bin");
                final Path framePath = directory.resolve("frame.bin");

                try (final RandomAccessFile file = new RandomAccessFile(controlPath.toFile(), "rw")) {
                        file.setLength(CONTROL_SIZE);
                        file.seek(0);
                        writeIntLittleEndian(file, CONTROL_MAGIC);
                        writeIntLittleEndian(file, PROTOCOL_VERSION);
                        writeIntLittleEndian(file, transportFfm ? FLAG_TRANSPORT_FFM : 0);
                }

                // The sidecar contract of wrx_region.cpp:parseConfig — the
                // directory key is mandatory and the protocol must match.
                Files.writeString(libraryDirectory.resolve("wrx.conf"),
                                "protocol=" + PROTOCOL_VERSION + "\n"
                                                + "transport=" + (transportFfm ? "ffm" : "shm") + "\n"
                                                + "directory=" + directory.toAbsolutePath() + "\n",
                                StandardCharsets.UTF_8);

                return new WrxRegion(directory, controlPath, buildPath, resultsPath, framePath, buildBytes, resultsBytes, frameBytes);
        }

        private static void writeIntLittleEndian(RandomAccessFile file, int value) throws IOException {
                file.write(value & 0xFF);
                file.write(value >>> 8 & 0xFF);
                file.write(value >>> 16 & 0xFF);
                file.write(value >>> 24 & 0xFF);
        }

        private WrxRegion(Path directory, Path controlPath, Path buildPath, Path resultsPath, Path framePath, long buildBytes, long resultsBytes, long frameBytes) throws IOException {
                this.directory = directory;
                control = map(controlPath, CONTROL_SIZE);
                build = map(buildPath, buildBytes);
                results = map(resultsPath, resultsBytes);
                frame = map(framePath, frameBytes);
                buildCapacity = buildBytes - QUEUE_HEADER_BYTES;
                build.putLong(16, buildCapacity);
                results.putLong(16, resultsBytes - QUEUE_HEADER_BYTES);
        }

        private static MappedByteBuffer map(Path path, long size) throws IOException {
                try (final RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
                        if (file.length() < size) {
                                file.setLength(size);
                        }
                        final FileChannel channel = file.getChannel();
                        final MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);
                        return buffer;
                }
        }

        // ---------------------------------------------------------------------------
        // control accessors
        // ---------------------------------------------------------------------------

        public synchronized int flags() {
                return closed ? 0 : control.getInt(OFF_FLAGS);
        }

        public boolean flagSet(int flag) {
                return (flags() & flag) != 0;
        }

        public synchronized int errorCode() {
                return closed ? 0 : control.getInt(OFF_ERROR);
        }

        public synchronized long nativeHeartbeat() {
                return closed ? 0 : control.getLong(OFF_NATIVE_HEARTBEAT);
        }

        public synchronized long buildSeq() {
                return closed ? 0 : control.getLong(OFF_BUILD_SEQ);
        }

        public synchronized long buildAck() {
                return closed ? 0 : control.getLong(OFF_BUILD_ACK);
        }

        public synchronized long frameSeq() {
                return closed ? 0 : control.getLong(OFF_FRAME_SEQ);
        }

        public synchronized long frameAck() {
                return closed ? 0 : control.getLong(OFF_FRAME_ACK);
        }

        public synchronized long frameErrors() {
                return closed ? 0 : control.getLong(OFF_FRAME_ERRORS);
        }

        // ---------------------------------------------------------------------------
        // build queue: Java writes. Wrap rule mirrored from native/src/wrx.cpp:
        // the writer only returns to zero when the reader has caught up, and the
        // reader resets whenever it sees readPos > writePos. Lossless across wraps.
        // ---------------------------------------------------------------------------

        public synchronized void publishBuildMessage(int type, byte[] payload) {
                if (closed) {
                        return;
                }
                final int total = MESSAGE_HEADER_BYTES + (payload.length + 7 & ~7);
                final long readPos = build.getLong(8);
                long writePos = build.getLong(0);
                if (writePos + total > buildCapacity) {
                        if (readPos != writePos) {
                                throw new IllegalStateException("Build queue overflow at " + writePos + " (reader at " + readPos + ")");
                        }
                        writePos = 0;
                }
                final int position = QUEUE_HEADER_BYTES + (int) writePos;
                build.putInt(position, type);
                build.putInt(position + 4, payload.length);
                build.position(position + MESSAGE_HEADER_BYTES);
                build.put(payload);
                for (int i = payload.length; i < total - MESSAGE_HEADER_BYTES; i++) {
                        build.put((byte) 0);
                }
                VarHandle.storeStoreFence();
                build.putLong(0, writePos + total);
                // The sequence counter lives in control.bin — reading it from
                // the build queue's own payload bytes (offset 32 of the mapped
                // build file) produced garbage every call.
                control.putLong(OFF_BUILD_SEQ, control.getLong(OFF_BUILD_SEQ) + 1);
        }

        public synchronized List<ResultMessage> consumeResults() {
                if (closed) {
                        return List.of();
                }
                final List<ResultMessage> messages = new ArrayList<>();
                long readPos = results.getLong(8);
                final long writePos = results.getLong(0);
                if (readPos > writePos) {
                        readPos = 0;
                        results.putLong(8, 0);
                }
                while (readPos + MESSAGE_HEADER_BYTES <= writePos) {
                        final int position = QUEUE_HEADER_BYTES + (int) readPos;
                        final int type = results.getInt(position);
                        final int size = results.getInt(position + 4);
                        final byte[] payload = new byte[size];
                        for (int i = 0; i < size; i++) {
                                payload[i] = results.get(position + MESSAGE_HEADER_BYTES + i);
                        }
                        readPos += MESSAGE_HEADER_BYTES + (size + 7 & ~7);
                        results.putLong(8, readPos);
                        control.putLong(OFF_BUILD_RESULT_ACK, control.getLong(OFF_BUILD_RESULT_ACK) + 1);
                        messages.add(new ResultMessage(type, payload));
                }
                return messages;
        }

        public record ResultMessage(int type, byte[] payload) {
        }

        // ---------------------------------------------------------------------------
        // frame slot: seqlock writer. beginFrameWrite marks the slot odd, the
        // caller fills the payload through frameBuffer(), endFrameWrite publishes.
        // ---------------------------------------------------------------------------

        public synchronized long beginFrameWrite() {
                if (closed) {
                        return -1;
                }
                final long seq = frame.getLong(0) & ~1L;
                frame.putLong(0, seq | 1);
                return seq;
        }

        public synchronized void endFrameWrite(long seq) {
                if (closed) {
                        return;
                }
                VarHandle.storeStoreFence();
                frame.putLong(0, seq + 2);
                control.putLong(OFF_FRAME_SEQ, seq + 2);
                control.putLong(OFF_JAVA_HEARTBEAT, seq + 2);
        }

        /**
         * Payload view for the frame writer. All offsets are relative to the
         * payload (the seq word at index -8 is handled by begin/end).
         */
        public synchronized ByteBuffer frameBuffer() {
                return frame;
        }

        @Override
        public synchronized void close() {
                if (closed) {
                        return;
                }
                closed = true;
                NativeMemory.unmap(control);
                NativeMemory.unmap(build);
                NativeMemory.unmap(results);
                NativeMemory.unmap(frame);
                control = null;
                build = null;
                results = null;
                frame = null;
        }

        /**
         * JDK-adaptive memory helpers. Fences go through the public
         * {@link VarHandle} API (available since Java 9, no reflection
         * needed); unmap uses {@code Unsafe.invokeCleaner} when the
         * {@code theUnsafe} field is resolvable, and otherwise leaves the
         * mapping to the buffer's own GC-registered cleaner — an explicitly
         * unmapped region is a nicety, never a correctness requirement.
         */
        static final class NativeMemory {

                private static final sun.misc.Unsafe UNSAFE;

                static {
                        sun.misc.Unsafe unsafe = null;
                        try {
                                final Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                                field.setAccessible(true);
                                unsafe = (sun.misc.Unsafe) field.get(null);
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // Newer JDKs may hide theUnsafe from this lookup; the GC's
                                // mapping cleaner then owns the unmap and nothing is leaked.
                        }
                        UNSAFE = unsafe;
                }

                static void unmap(MappedByteBuffer buffer) {
                        if (UNSAFE != null) {
                                try {
                                        UNSAFE.invokeCleaner(buffer);
                                } catch (Throwable ignored) {
                                        // fall through to the GC cleaner
                                }
                        }
                }
        }
}
