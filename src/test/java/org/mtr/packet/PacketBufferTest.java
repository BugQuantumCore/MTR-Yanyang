package org.mtr.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class PacketBufferTest {

	@Test
	void sendsOnlyWrittenBytesAndPreservesAllPrimitiveTypes() {
		final PacketBufferSender sender = new PacketBufferSender();
		sender.writeBoolean(true);
		sender.writeChar('\0');
		sender.writeInt(-123);
		sender.writeFloat(1.5F);
		sender.writeLong(Long.MIN_VALUE);
		sender.writeDouble(-2.75);
		final List<byte[]> packets = new ArrayList<>();
		sender.send(packets::add, Runnable::run);
		assertEquals(1, packets.size());
		assertEquals(PacketBufferSender.HEADER_BYTES + 1 + 2 + 4 + 4 + 8 + 8, packets.getFirst().length);
		final AtomicInteger completed = new AtomicInteger();
		PacketBufferReceiver.receive(new Object(), packets.getFirst(), receiver -> {
			assertTrue(receiver.readBoolean());
			assertEquals('\0', receiver.readChar());
			assertEquals(-123, receiver.readInt());
			assertEquals(1.5F, receiver.readFloat());
			assertEquals(Long.MIN_VALUE, receiver.readLong());
			assertEquals(-2.75, receiver.readDouble());
			completed.incrementAndGet();
		}, Runnable::run);
		assertEquals(1, completed.get());
		assertThrows(IllegalStateException.class, () -> sender.send(packets::add, Runnable::run));
	}

	@Test
	void nulAtTheEndOfAChunkRemainsPartOfTheString() {
		final String text = "x".repeat((PacketBufferSender.MAX_PACKET_BYTES - 4) / 2 - 1) + '\0' + "tail";
		final PacketBufferSender sender = new PacketBufferSender();
		sender.writeString(text);
		sender.writeLong(0x1122334455667788L);
		final List<byte[]> packets = new ArrayList<>();
		sender.send(packets::add, Runnable::run);
		assertEquals(2, packets.size());
		assertTrue(packets.stream().allMatch(bytes -> bytes.length <= 0x7FFF));
		final AtomicInteger completed = new AtomicInteger();
		final Object scope = new Object();
		// Network ordering is not required by the reassembler.
		for (int index = packets.size() - 1; index >= 0; index--) {
			PacketBufferReceiver.receive(scope, packets.get(index), receiver -> {
				assertEquals(text, receiver.readString());
				assertEquals(0x1122334455667788L, receiver.readLong());
				completed.incrementAndGet();
			}, Runnable::run);
		}
		assertEquals(1, completed.get());
	}

	@Test
	void legacyPaddingDoesNotBecomeAStringCharacterOrInteger() {
		final String text = "x".repeat((PacketBufferSender.MAX_PACKET_BYTES - 4) / 2 - 1) + '\0' + "tail";
		final LegacySender sender = new LegacySender();
		sender.writeInt(text.length());
		for (int index = 0; index < text.length(); index++) {
			sender.writeChar(text.charAt(index));
		}
		sender.writeInt(0x12345678);
		final AtomicInteger completed = new AtomicInteger();
		final Object scope = new Object();
		for (byte[] chunk : sender.finish()) {
			PacketBufferReceiver.receive(scope, chunk, receiver -> {
				assertEquals(text, receiver.readString());
				assertEquals(0x12345678, receiver.readInt());
				completed.incrementAndGet();
			}, Runnable::run);
		}
		assertEquals(1, completed.get());
	}

	@Test
	void duplicateChunksDoNotCompleteAnAssemblyEarly() {
		final Object scope = new Object();
		final AtomicInteger completed = new AtomicInteger();
		final byte[] first = chunk(42, 0, 2, 10);
		PacketBufferReceiver.receive(scope, first, receiver -> {
			assertEquals(10, receiver.readInt());
			assertEquals(20, receiver.readInt());
			completed.incrementAndGet();
		}, Runnable::run);
		PacketBufferReceiver.receive(scope, first, receiver -> fail("Duplicate replaced callback"), Runnable::run);
		assertEquals(0, completed.get());
		PacketBufferReceiver.receive(scope, chunk(42, 1, 2, 20), receiver -> fail("Later chunk replaced callback"), Runnable::run);
		assertEquals(1, completed.get());
	}

	@Test
	void peerScopesCannotSpliceEachOthersChunks() {
		final Object firstPeer = new Object();
		final Object secondPeer = new Object();
		final AtomicInteger completed = new AtomicInteger();
		PacketBufferReceiver.receive(firstPeer, chunk(7, 0, 2, 1), receiver -> completed.incrementAndGet(), Runnable::run);
		PacketBufferReceiver.receive(secondPeer, chunk(7, 1, 2, 2), receiver -> completed.incrementAndGet(), Runnable::run);
		assertEquals(0, completed.get());
		PacketBufferReceiver.clear(firstPeer);
		PacketBufferReceiver.clear(secondPeer);
	}

	@Test
	void rejectsInvalidHeadersAndOversizedStrings() {
		final Object scope = new Object();
		final AtomicInteger completed = new AtomicInteger();
		PacketBufferReceiver.receive(scope, new byte[15], receiver -> completed.incrementAndGet(), Runnable::run);
		PacketBufferReceiver.receive(scope, chunk(11, -1, 1, 0), receiver -> completed.incrementAndGet(), Runnable::run);
		PacketBufferReceiver.receive(scope, chunk(12, 0, Integer.MAX_VALUE, 0), receiver -> completed.incrementAndGet(), Runnable::run);
		PacketBufferReceiver.receive(scope, chunk(13, 1, 1, 0), receiver -> completed.incrementAndGet(), Runnable::run);
		PacketBufferReceiver.receive(scope, chunk(14, 0, 1, Integer.MAX_VALUE), receiver -> {
			receiver.readString();
			completed.incrementAndGet();
		}, Runnable::run);
		assertEquals(0, completed.get());
	}

	@Test
	void rejectsNegativeAndOversizedCollectionCountsBeforeAllocation() {
		final AtomicInteger completed = new AtomicInteger();
		for (int count : new int[]{-1, Integer.MAX_VALUE, 1}) {
			PacketBufferReceiver.receive(new Object(), chunk(20, 0, 1, count), receiver -> {
				receiver.readCount(Long.BYTES);
				completed.incrementAndGet();
			}, Runnable::run);
		}
		assertEquals(0, completed.get());
	}

	@Test
	void validatesMinimumSizeCountsAcrossChunksIncludingEmptyCollections() {
		final int count = PacketBufferSender.MAX_PACKET_BYTES / Integer.BYTES + 1;
		final PacketBufferSender sender = new PacketBufferSender();
		sender.writeInt(count);
		for (int index = 0; index < count; index++) {
			sender.writeString("");
		}
		sender.writeInt(0);
		final List<byte[]> packets = new ArrayList<>();
		sender.send(packets::add, Runnable::run);
		assertEquals(2, packets.size());
		final Object scope = new Object();
		final AtomicInteger completed = new AtomicInteger();
		for (byte[] packet : packets) {
			PacketBufferReceiver.receive(scope, packet, receiver -> {
				assertEquals(count, receiver.readCount(Integer.BYTES));
				for (int index = 0; index < count; index++) {
					assertEquals("", receiver.readString());
				}
				assertEquals(0, receiver.readCount(Long.BYTES));
				completed.incrementAndGet();
			}, Runnable::run);
		}
		assertEquals(1, completed.get());
	}

	@Test
	void countBoundsExcludeConsumedBytesAndRejectInvalidElementSizes() {
		final PacketBufferSender sender = new PacketBufferSender();
		sender.writeLong(0);
		sender.writeInt(1);
		final List<byte[]> packets = new ArrayList<>();
		sender.send(packets::add, Runnable::run);
		final AtomicInteger completed = new AtomicInteger();
		PacketBufferReceiver.receive(new Object(), packets.getFirst(), receiver -> {
			assertEquals(0, receiver.readLong());
			assertThrows(IllegalArgumentException.class, () -> receiver.readCount(0));
			assertThrows(IllegalArgumentException.class, () -> receiver.readCount(-1));
			receiver.readCount(Long.BYTES);
			completed.incrementAndGet();
		}, Runnable::run);
		assertEquals(0, completed.get());
	}

	private static byte[] chunk(long id, int index, int count, int value) {
		final ByteBuf buffer = Unpooled.buffer(20);
		try {
			buffer.writeLong(id).writeInt(index).writeInt(count).writeInt(value);
			final byte[] bytes = new byte[buffer.writerIndex()];
			buffer.getBytes(0, bytes);
			return bytes;
		} finally {
			buffer.release();
		}
	}

	/** Reproduces the previous sender's rollover and backing-array padding. */
	private static final class LegacySender {
		private final List<ByteBuf> chunks = new ArrayList<>();
		private ByteBuf current;
		private int payloadBytes;

		private void reserve(int size) {
			if (current == null || payloadBytes + size >= PacketBufferSender.MAX_PACKET_BYTES) {
				current = Unpooled.buffer();
				current.writeZero(PacketBufferSender.HEADER_BYTES);
				chunks.add(current);
				payloadBytes = 0;
			}
			payloadBytes += size;
		}

		void writeInt(int value) {
			reserve(4);
			current.writeInt(value);
		}

		void writeChar(char value) {
			reserve(2);
			current.writeChar(value);
		}

		List<byte[]> finish() {
			final List<byte[]> result = new ArrayList<>();
			for (int index = 0; index < chunks.size(); index++) {
				final ByteBuf buffer = chunks.get(index);
				buffer.setLong(0, 99).setInt(8, index).setInt(12, chunks.size());
				result.add(Arrays.copyOf(buffer.array(), buffer.capacity()));
				buffer.release();
			}
			return result;
		}
	}
}
