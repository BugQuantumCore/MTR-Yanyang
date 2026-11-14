package org.mtr.nativeobj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public final class WrxRegionTest {

        @TempDir
        Path directory;

        private WrxRegion create() throws Exception {
                return WrxRegion.create(directory.resolve("region"), directory.resolve("library"), false, 256, 256, 4096);
        }

        @Test
        public void publishesAlignedMessagesAndRenderThreadFrameOwnership() throws Exception {
                try (final WrxRegion region = create()) {
                        assertTrue(region.flagSet(WrxRegion.FLAG_RENDER_THREAD_FRAMES));
                        assertFalse(region.flagSet(WrxRegion.FLAG_TRANSPORT_FFM));
                        final byte[] payload = {1, 2, 3};
                        region.publishBuildMessage(WrxRegion.MSG_INSTANCE, payload);
                        assertEquals(1, region.buildSeq());
                        final ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(region.directory.resolve("build.bin"))).order(ByteOrder.LITTLE_ENDIAN);
                        assertEquals(16, bytes.getLong(0));
                        assertEquals(WrxRegion.MSG_INSTANCE, bytes.getInt(WrxRegion.QUEUE_HEADER_BYTES));
                        assertEquals(3, bytes.getInt(WrxRegion.QUEUE_HEADER_BYTES + 4));
                        final byte[] actual = new byte[3];
                        bytes.get(WrxRegion.QUEUE_HEADER_BYTES + WrxRegion.MESSAGE_HEADER_BYTES, actual);
                        assertArrayEquals(payload, actual);

                        final long seq = region.beginFrameWrite();
                        region.frameBuffer().putInt(8, WrxRegion.FRAME_MAGIC);
                        region.endFrameWrite(seq);
                        assertEquals(2, region.frameSeq());
                }
        }

        @Test
        public void rejectsOversizedMessageBeforePublishingAnyCursor() throws Exception {
                try (final WrxRegion region = create()) {
                        assertThrows(IllegalArgumentException.class, () -> region.publishBuildMessage(WrxRegion.MSG_LOAD_OBJ, new byte[256]));
                        assertEquals(0, region.buildSeq());
                }
        }

        @Test
        public void validatesNativeResultLengthsBeforeAllocation() throws Exception {
                try (final WrxRegion region = create()) {
                        final ByteBuffer bytes = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
                        bytes.putLong(0, 16);
                        bytes.putLong(16, 232);
                        bytes.putInt(24, WrxRegion.RES_MODELS);
                        bytes.putInt(28, Integer.MAX_VALUE);
                        Files.write(region.directory.resolve("results.bin"), bytes.array());
                        assertThrows(IllegalStateException.class, region::consumeResults);
                }
        }

        @Test
        public void copiesOneValidResultPayloadAndAcknowledgesIt() throws Exception {
                try (final WrxRegion region = create()) {
                        final ByteBuffer bytes = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
                        bytes.putLong(0, 16);
                        bytes.putLong(16, 232);
                        bytes.putInt(24, WrxRegion.RES_INSTANCE);
                        bytes.putInt(28, 4);
                        bytes.putInt(32, 42);
                        Files.write(region.directory.resolve("results.bin"), bytes.array());
                        final var messages = region.consumeResults();
                        assertEquals(1, messages.size());
                        assertEquals(WrxRegion.RES_INSTANCE, messages.getFirst().type());
                        assertEquals(42, ByteBuffer.wrap(messages.getFirst().payload()).order(ByteOrder.LITTLE_ENDIAN).getInt());
                        assertTrue(region.consumeResults().isEmpty());
                }
        }
}
