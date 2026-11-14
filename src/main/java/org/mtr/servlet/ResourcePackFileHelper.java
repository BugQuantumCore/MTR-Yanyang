package org.mtr.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Filesystem-independent validation shared by the creator and its regression tests. */
final class ResourcePackFileHelper {

	private ResourcePackFileHelper() {
	}

	static Path exportPath(Path directory, String name, String timestamp) {
		validateFileName(name);
		validateFileName(timestamp);
		final Path root = directory.toAbsolutePath().normalize();
		final Path result = root.resolve(name + "-" + timestamp + ".zip").normalize();
		if (!root.equals(result.getParent())) {
			throw new IllegalArgumentException("The export must remain in the resource pack directory");
		}
		return result;
	}

	static byte[] readBounded(InputStream inputStream, long maximumBytes) throws IOException {
		if (maximumBytes < 0) {
			throw new IOException("Resource pack upload exceeds the total size limit");
		}
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		long remaining = maximumBytes;
		int count;
		while ((count = inputStream.read(buffer)) != -1) {
			if (count > remaining) {
				throw new IOException("Resource pack upload exceeds the size limit");
			}
			outputStream.write(buffer, 0, count);
			remaining -= count;
		}
		return outputStream.toByteArray();
	}

	static String zipEntryPath(String identifier) {
		final int separator = identifier.indexOf(':');
		if (separator <= 0 || separator != identifier.lastIndexOf(':')) {
			throw new IllegalArgumentException("Invalid resource identifier");
		}
		final String namespace = identifier.substring(0, separator);
		validateFileName(namespace);
		final String path = identifier.substring(separator + 1);
		for (final String segment : path.split("/", -1)) {
			validateFileName(segment);
		}
		return "assets/" + namespace + "/" + path;
	}

	private static void validateFileName(String name) {
		if (name.isBlank() || name.endsWith(".") || name.endsWith(" ")) {
			throw new IllegalArgumentException("A file name is required");
		}
		for (int index = 0; index < name.length(); index++) {
			final char character = name.charAt(index);
			if (character < 32 || character == 127 || "/\\:*?\"<>|".indexOf(character) >= 0) {
				throw new IllegalArgumentException("A file name cannot contain a path separator or reserved character");
			}
		}
	}
}
