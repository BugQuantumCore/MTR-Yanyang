package org.mtr.map;

import java.nio.file.Path;

/** Resolves untrusted server world IDs without allowing writes outside the map cache. */
final class MapCachePath {

	private MapCachePath() {
	}

	static Path resolve(Path gameDirectory, String uniqueWorldId, String dimensionId) {
		validateSegment(uniqueWorldId);
		final Path cacheRoot = gameDirectory.toAbsolutePath().normalize().resolve("config/cache/map");
		Path result = cacheRoot.resolve(uniqueWorldId);
		for (final String segment : dimensionId.split("/", -1)) {
			validateSegment(segment);
			result = result.resolve(segment);
		}
		result = result.normalize();
		if (!result.startsWith(cacheRoot)) {
			throw new IllegalArgumentException("Map cache path escapes its directory");
		}
		return result;
	}

	private static void validateSegment(String segment) {
		if (segment.isBlank() || segment.endsWith(".") || segment.endsWith(" ")) {
			throw new IllegalArgumentException("Invalid map cache path segment");
		}
		for (int index = 0; index < segment.length(); index++) {
			final char character = segment.charAt(index);
			if (character < 32 || character == 127 || "/\\:*?\"<>|".indexOf(character) >= 0) {
				throw new IllegalArgumentException("Invalid map cache path character");
			}
		}
	}
}
