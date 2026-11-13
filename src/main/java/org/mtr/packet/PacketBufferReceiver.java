package org.mtr.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Reassembles bounded packet chunks; the completion callback consumes them synchronously. */
public final class PacketBufferReceiver {

	static final int MAX_CHUNKS = 2048;
	private static final int MAX_PENDING_PACKETS = 128;
	private static final long MAX_BUFFERED_BYTES = 64L * 1024 * 1024;
	private static final long ASSEMBLY_TIMEOUT_NANOS = 60_000_000_000L;
	private static final Object DEFAULT_SCOPE = new Object();
	private static final Map<PacketKey, PacketBufferReceiver> RECEIVED_PACKETS = new LinkedHashMap<>();
	private static long bufferedBytes;

	private int receivedCount;
	private int readIndex;
	private long byteCount;
	private long remainingBytes;
	private final long createdAt = System.nanoTime();
	private final ByteBuf[] byteBufArray;
	private final Consumer<PacketBufferReceiver> onComplete;

	private PacketBufferReceiver(int count, Consumer<PacketBufferReceiver> onComplete) {
		this.onComplete = onComplete;
		byteBufArray = new ByteBuf[count];
	}

	public boolean readBoolean() {
		return read(1).readBoolean();
	}

	public char readChar() {
		return read(2).readChar();
	}

	public int readInt() {
		return read(4).readInt();
	}

	public float readFloat() {
		return read(4).readFloat();
	}

	public long readLong() {
		return read(8).readLong();
	}

	public double readDouble() {
		return read(8).readDouble();
	}

	/** Validates collection lengths before allocating against their minimum encoded element size. */
	public int readCount(int minimumBytesPerElement) {
		if (minimumBytesPerElement <= 0) {
			throw new IllegalArgumentException("The minimum element size must be positive");
		}
		final int count = readInt();
		if (count < 0 || count > remainingBytes / minimumBytesPerElement) {
			throw new MalformedPacketException();
		}
		return count;
	}

	public String readString() {
		final int stringLength = readCount(Character.BYTES);
		final char[] characters = new char[stringLength];
		for (int i = 0; i < stringLength; i++) {
			characters[i] = readChar();
		}
		return new String(characters);
	}

	private ByteBuf read(int size) {
		while (readIndex < byteBufArray.length) {
			final ByteBuf buffer = byteBufArray[readIndex];
			// Older senders exposed their 32768-byte backing arrays, including
			// padding. Mirror their rollover threshold, never guess from NUL
			// contents: a real string may contain a NUL at a chunk boundary.
			final boolean legacyPadding = buffer.writerIndex() == 0x8000
					&& readIndex + 1 < byteBufArray.length
					&& buffer.readerIndex() - PacketBufferSender.HEADER_BYTES + size >= PacketBufferSender.MAX_PACKET_BYTES;
			if (!buffer.isReadable() || legacyPadding) {
				remainingBytes -= buffer.readableBytes();
				readIndex++;
				continue;
			}
			if (buffer.readableBytes() < size) {
				throw new MalformedPacketException();
			}
			remainingBytes -= size;
			return buffer;
		}
		throw new MalformedPacketException();
	}

	private void release() {
		for (int index = 0; index < byteBufArray.length; index++) {
			if (byteBufArray[index] != null) {
				byteBufArray[index].release();
				byteBufArray[index] = null;
			}
		}
	}

	public static void receive(byte[] bytes, Consumer<PacketBufferReceiver> onComplete, Consumer<Runnable> scheduler) {
		receive(DEFAULT_SCOPE, bytes, onComplete, scheduler);
	}

	/** Use a connection/player scope to prevent chunks from different peers sharing an ID. */
	public static void receive(Object scope, byte[] bytes, Consumer<PacketBufferReceiver> onComplete, Consumer<Runnable> scheduler) {
		if (bytes.length < PacketBufferSender.HEADER_BYTES || bytes.length > 0x8000) {
			return;
		}
		scheduler.accept(() -> {
			final ByteBuf buffer = Unpooled.copiedBuffer(bytes);
			boolean retained = false;
			PacketBufferReceiver completed = null;
			try {
				final long id = buffer.readLong();
				final int index = buffer.readInt();
				final int count = buffer.readInt();
				if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count) {
					return;
				}
				synchronized (RECEIVED_PACKETS) {
					final long now = System.nanoTime();
					final Iterator<PacketBufferReceiver> iterator = RECEIVED_PACKETS.values().iterator();
					while (iterator.hasNext()) {
						final PacketBufferReceiver pending = iterator.next();
						if (now - pending.createdAt >= ASSEMBLY_TIMEOUT_NANOS) {
							iterator.remove();
							bufferedBytes -= pending.byteCount;
							pending.release();
						}
					}
					final PacketKey key = new PacketKey(scope, id);
					PacketBufferReceiver pending = RECEIVED_PACKETS.get(key);
					if (pending == null) {
						if (RECEIVED_PACKETS.size() >= MAX_PENDING_PACKETS || bufferedBytes + bytes.length > MAX_BUFFERED_BYTES) {
							return;
						}
						pending = new PacketBufferReceiver(count, onComplete);
						RECEIVED_PACKETS.put(key, pending);
					}
					if (pending.byteBufArray.length != count || pending.byteBufArray[index] != null
							|| bufferedBytes + bytes.length > MAX_BUFFERED_BYTES) {
						return;
					}
					pending.byteBufArray[index] = buffer;
					pending.byteCount += bytes.length;
					bufferedBytes += bytes.length;
					retained = true;
					if (++pending.receivedCount == count) {
						RECEIVED_PACKETS.remove(key);
						bufferedBytes -= pending.byteCount;
						pending.remainingBytes = pending.byteCount - (long) PacketBufferSender.HEADER_BYTES * count;
						completed = pending;
					}
				}
				if (completed != null) {
					try {
						completed.onComplete.accept(completed);
					} catch (MalformedPacketException ignored) {
						// Malformed network input must not crash the client/server tick.
					} finally {
						completed.release();
					}
				}
			} finally {
				if (!retained) {
					buffer.release();
				}
			}
		});
	}

	/** Drop incomplete assemblies when their connection closes. */
	public static void clear(Object scope) {
		synchronized (RECEIVED_PACKETS) {
			final Iterator<Map.Entry<PacketKey, PacketBufferReceiver>> iterator = RECEIVED_PACKETS.entrySet().iterator();
			while (iterator.hasNext()) {
				final Map.Entry<PacketKey, PacketBufferReceiver> entry = iterator.next();
				if (java.util.Objects.equals(entry.getKey().scope(), scope)) {
					iterator.remove();
					bufferedBytes -= entry.getValue().byteCount;
					entry.getValue().release();
				}
			}
		}
	}

	private record PacketKey(Object scope, long id) {
	}

	private static final class MalformedPacketException extends IllegalArgumentException {
	}
}
