package org.mtr.servlet;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ResourcePackFileHelperTest {

	@Test
	void exportStaysInsideResourcePackDirectoryAndPreservesUnicodeNames() {
		final Path directory = Path.of("game", "resourcepacks").toAbsolutePath().normalize();
		assertEquals(directory.resolve("晏阳 Model Pack-20261114-120000.zip"), ResourcePackFileHelper.exportPath(directory, "晏阳 Model Pack", "20261114-120000"));
		for (final String name : new String[]{"", " ", ".", "..", "../outside", "..\\outside", "/absolute", "C:\\outside", "folder/pack", "folder\\pack", "bad\0name"}) {
			assertThrows(IllegalArgumentException.class, () -> ResourcePackFileHelper.exportPath(directory, name, "timestamp"), name);
		}
	}

	@Test
	void zipEntryPathsRejectTraversalAndAbsolutePaths() {
		assertEquals("assets/mtr/models/train/body.obj", ResourcePackFileHelper.zipEntryPath("mtr:models/train/body.obj"));
		for (final String identifier : new String[]{"mtr:../outside", "mtr:.. /outside", "mtr:models/../../outside", "mtr:/absolute", "mtr:C:\\outside", "mtr:models\\..\\outside", "../mtr:model.obj", "mtr:", "mtr:models//body.obj"}) {
			assertThrows(IllegalArgumentException.class, () -> ResourcePackFileHelper.zipEntryPath(identifier), identifier);
		}
	}

	@Test
	void boundedReadsAcceptExactLimitAndRejectOversizedContent() throws IOException {
		final byte[] bytes = new byte[]{1, 2, 3, 4};
		assertArrayEquals(bytes, ResourcePackFileHelper.readBounded(new ByteArrayInputStream(bytes), bytes.length));
		assertArrayEquals(new byte[0], ResourcePackFileHelper.readBounded(new ByteArrayInputStream(new byte[0]), 0));
		assertThrows(IOException.class, () -> ResourcePackFileHelper.readBounded(new ByteArrayInputStream(bytes), bytes.length - 1));
		assertThrows(IOException.class, () -> ResourcePackFileHelper.readBounded(new ByteArrayInputStream(bytes), 0));
		assertThrows(IOException.class, () -> ResourcePackFileHelper.readBounded(new ByteArrayInputStream(bytes), -1));
	}
}
