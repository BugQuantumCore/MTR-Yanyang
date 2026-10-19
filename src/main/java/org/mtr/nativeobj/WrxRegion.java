package org.mtr.nativeobj;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * counterpart; the JUnit suite round-trips the layout so the two sides can
 * never drift apart silently.
 *
 * <p>The region is four memory-mapped files in a per-session temp directory;
 * Java creates and maps them before {@code System.load}, and the native
 * library maps the same files — the "C++ reads Java's data directly in
 * memory" channel. No JNI, no per-byte copies.</p>
 */
public final class WrxRegion implements AutoCloseable {

	public static final int CONTROL_MAGIC = 0x57525831; // "WRX1"
	public static final int PROTOCOL_VERSION = 1;
	public static final int CONTROL_SIZE = 4096;

	public static final int FLAG_TRANSPORT_FFM = 1 << 0;
	public static final int FLAG_ERROR = 1 << 3;
	public static final int FLAG_VULKAN_READY = 1 << 2;

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
	private final MappedByteBuffer control;
	private final MappedByteBuffer build;
	private final MappedByteBuffer results;
	private final MappedByteBuffer frame;
	private final long buildCapacity;

	/**
	 * Create the four region files with their headers, plus the wrx.conf
	 * sidecar the native constructor boot reads, then map everything.
	 */
	public static WrxRegion create(Path directory, boolean transportFfm, long buildBytes, long resultsBytes, long frameBytes) throws IOException {
		Files.createDirectories(directory);
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

		Files.writeString(directory.resolve("wrx.conf"),
				"protocol=" + PROTOCOL_VERSION + "\ntransport=" + (transportFfm ? "ffm" : "shm") + "\n",
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
			return channel.map(FileChannel.MapMode.READ_WRITE, 0, size).order(ByteOrder.LITTLE_ENDIAN);
		}
	}

	// ---------------------------------------------------------------------------
	// control accessors
	// ---------------------------------------------------------------------------

	public int flags() {
		return control.getInt(OFF_FLAGS);
	}

	public int errorCode() {
		return control.getInt(OFF_ERROR);
	}

	public long nativeHeartbeat() {
		return control.getLong(OFF_NATIVE_HEARTBEAT);
	}

	public long buildSeq() {
		return control.getLong(OFF_BUILD_SEQ);
	}

	public long buildAck() {
		return control.getLong(OFF_BUILD_ACK);
	}

	public long buildResultSeq() {
		return control.getLong(OFF_BUILD_RESULT_SEQ);
	}

	public long frameSeq() {
		return control.getLong(OFF_FRAME_SEQ);
	}

	public long frameAck() {
		return control.getLong(OFF_FRAME_ACK);
	}

	public long frameErrors() {
		return control.getLong(OFF_FRAME_ERRORS);
	}

	// ---------------------------------------------------------------------------
	// build queue: Java writes. Wrap rule mirrored from native/src/wrx.cpp:
	// the writer only returns to zero when the reader has caught up, and the
	// reader resets whenever it sees readPos > writePos. Lossless across wraps.
	// ---------------------------------------------------------------------------

	public void publishBuildMessage(int type, byte[] payload) {
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
		NativeMemory.storeFence();
		build.putLong(0, writePos + total);
		control.putLong(OFF_BUILD_SEQ, build.getLong(OFF_BUILD_SEQ) + 1);
	}

	public boolean hasBuildSpace(int bytes) {
		final long readPos = build.getLong(8);
		final long writePos = build.getLong(0);
		return writePos + bytes <= buildCapacity || readPos == writePos;
	}

	public List<ResultMessage> consumeResults() {
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

	public long beginFrameWrite() {
		final long seq = frame.getLong(0) & ~1L;
		frame.putLong(0, seq | 1);
		return seq;
	}

	public void endFrameWrite(long seq) {
		NativeMemory.storeFence();
		frame.putLong(0, seq + 2);
		control.putLong(OFF_FRAME_SEQ, seq + 2);
		control.putLong(OFF_JAVA_HEARTBEAT, seq + 2);
	}

	/**
	 * Payload view for the frame writer. All offsets are relative to the
	 * payload (the seq word at index -8 is handled by begin/end).
	 */
	public ByteBuffer frameBuffer() {
		return frame;
	}

	@Override
	public void close() {
		NativeMemory.unmap(control);
		NativeMemory.unmap(build);
		NativeMemory.unmap(results);
		NativeMemory.unmap(frame);
	}

	/**
	 * JDK-adaptive memory helpers. JDK 22+ uses the FFM API and VarHandle
	 * fences; older JDKs fall back to sun.misc.Unsafe. Auto-detected once.
	 */
	static final class NativeMemory {

		private static final sun.misc.Unsafe UNSAFE;
		private static final MethodHandle CLEANER_JDK22;

		static {
			sun.misc.Unsafe unsafe = null;
			try {
				final Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
				field.setAccessible(true);
				unsafe = (sun.misc.Unsafe) field.get(null);
			} catch (ReflectiveOperationException ignored) {
				// JDK 24+ hides theUnsafe from static lookups; the FFM branch covers those
			}
			UNSAFE = unsafe;
			MethodHandle cleaner = null;
			if (unsafe == null) {
				// JDK 22+ (compiled with --release 21, resolved reflectively)
				try {
					final MethodHandles.Lookup lookup = MethodHandles.lookup();
					final Class<?> segmentClass = Class.forName("java.lang.foreign.MemorySegment");
					final Method ofBuffer = segmentClass.getMethod("ofBuffer", ByteBuffer.class);
					cleaner = lookup.unreflect(segmentClass.getMethod("close"))
							.bindTo(null); // bound per-segment below via asType trick; null placeholder
					// hold the ofBuffer method for the unmap path
					final MethodHandle ofBufferHandle = lookup.unreflect(ofBuffer);
					final MethodHandle closeHandle = lookup.unreflect(segmentClass.getMethod("close"));
					cleaner = MethodHandles.collectArguments(closeHandle, 0, ofBufferHandle);
					MethodHandles.lookup(); // keep lookup alive pattern
				} catch (ReflectiveOperationException ignored) {
					cleaner = null;
				}
			}
			CLEANER_JDK22 = cleaner;
		}

		static void storeFence() {
			if (UNSAFE != null) {
				UNSAFE.storeFence();
			} else {
				java.lang.invoke.VarHandle.storeStoreFence();
			}
		}

		static void unmap(MappedByteBuffer buffer) {
			if (UNSAFE != null) {
				UNSAFE.invokeCleaner(buffer);
			} else if (CLEANER_JDK22 != null) {
				try {
					CLEANER_JDK22.invokeExact(buffer);
				} catch (Throwable ignored) {
					// mapping cleaner falls back to the GC
				}
			}
		}
	}
}
