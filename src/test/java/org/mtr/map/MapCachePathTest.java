package org.mtr.map;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MapCachePathTest {

	@Test
	void preservesExistingWorldAndNestedDimensionLayout() {
		final Path game = Path.of("game").toAbsolutePath().normalize();
		assertEquals(game.resolve("config/cache/map/world-id/minecraft/overworld"), MapCachePath.resolve(game, "world-id", "minecraft/overworld"));
		assertEquals(game.resolve("config/cache/map/晏阳/custom/nested/dimension"), MapCachePath.resolve(game, "晏阳", "custom/nested/dimension"));
	}

	@Test
	void rejectsServerControlledPathTraversalAndAbsoluteNames() {
		final Path game = Path.of("game");
		for (final String id : new String[]{"", ".", "..", ".. ", "world.", "../outside", "/absolute", "C:\\outside", "a/b", "a\\b", "a\0b"}) {
			assertThrows(IllegalArgumentException.class, () -> MapCachePath.resolve(game, id, "minecraft/overworld"), id);
		}
		for (final String dimension : new String[]{"", "../outside", "minecraft/../../outside", "minecraft/.. /outside", "/absolute", "minecraft//world", "minecraft/./world", "minecraft/..\\outside"}) {
			assertThrows(IllegalArgumentException.class, () -> MapCachePath.resolve(game, "world", dimension), dimension);
		}
	}
}
