package org.mtr.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class PacketBufferSender {

	private int capacity;
	private ByteBuf currentByteBuf;
	private final ObjectArrayList<ByteBuf> byteBufObjects = new ObjectArrayList<>();
	private boolean sent;

	static final int HEADER_BYTES = 8 + 4 + 4;
	static final int MAX_PACKET_BYTES = 0x7FFF - HEADER_BYTES;

	public PacketBufferSender() {
		create();
	}

	public void writeBoolean(boolean data) {
		write(1);
		currentByteBuf.writeBoolean(data);
	}

	public void writeChar(char data) {
		write(2);
		currentByteBuf.writeChar(data);
	}

	public void writeInt(int data) {
		write(4);
		currentByteBuf.writeInt(data);
	}

	public void writeFloat(float data) {
		write(4);
		currentByteBuf.writeFloat(data);
	}

	public void writeLong(long data) {
		write(8);
		currentByteBuf.writeLong(data);
	}

	public void writeDouble(double data) {
		write(8);
		currentByteBuf.writeDouble(data);
	}

	public void writeString(String data) {
		writeInt(data.length());
		for (int index = 0; index < data.length(); index++) {
			writeChar(data.charAt(index));
		}
	}

	public void send(Consumer<byte[]> consumer, Consumer<Runnable> scheduler) {
		if (sent) {
			throw new IllegalStateException("Packet already sent");
		}
		sent = true;
		byteBufObjects.add(currentByteBuf);
		final long id = ThreadLocalRandom.current().nextLong();
		final ObjectArrayList<byte[]> packets = new ObjectArrayList<>(byteBufObjects.size());
		try {
			for (int i = 0; i < byteBufObjects.size(); i++) {
				final ByteBuf byteBuf = byteBufObjects.get(i);
				byteBuf.setLong(0, id);
				byteBuf.setInt(8, i);
				byteBuf.setInt(12, byteBufObjects.size());
				final byte[] bytes = new byte[byteBuf.writerIndex()];
				byteBuf.getBytes(0, bytes);
				packets.add(bytes);
			}
		} finally {
			byteBufObjects.forEach(ByteBuf::release);
			byteBufObjects.clear();
		}
		// One scheduled task also works with synchronous schedulers and avoids
		// recursive removeFirst() calls and O(chunkCount²) array shifts.
		scheduler.accept(() -> packets.forEach(consumer));
	}

	private void write(int size) {
		if (sent) {
			throw new IllegalStateException("Packet already sent");
		}
		if (capacity + size > MAX_PACKET_BYTES) {
			if (byteBufObjects.size() + 1 >= PacketBufferReceiver.MAX_CHUNKS) {
				currentByteBuf.release();
				byteBufObjects.forEach(ByteBuf::release);
				byteBufObjects.clear();
				sent = true;
				throw new IllegalArgumentException("Packet exceeds the chunk budget");
			}
			byteBufObjects.add(currentByteBuf);
			create();
			capacity = 0;
		}
		capacity += size;
	}

	private void create() {
		currentByteBuf = Unpooled.buffer(256, HEADER_BYTES + MAX_PACKET_BYTES);
		currentByteBuf.writeZero(HEADER_BYTES);
	}
}
