package org.mtr.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class CachedFileProviderTest {

	@TempDir
	Path directory;

	@Test
	void evictionAndShutdownReleaseEachOwnedResourceOnce() throws Exception {
		final TestProvider provider = new TestProvider(directory);
		final TestResource expired = new TestResource(directory.resolve("expired"), -1000);
		final TestResource retained = new TestResource(directory.resolve("retained"), 60000);
		// Seed entries without waiting for the global creation throttle or depending on wall-clock sleeps.
		final Field field = CachedFileProvider.class.getDeclaredField("dataMap");
		field.setAccessible(true);
		@SuppressWarnings("unchecked") final Map<Long, TestResource> entries = (Map<Long, TestResource>) field.get(provider);
		entries.put(1L, expired);
		entries.put(2L, retained);

		provider.tick();
		assertEquals(1, expired.closeCount);
		assertEquals(0, retained.closeCount);
		assertFalse(entries.containsKey(1L));
		assertTrue(entries.containsKey(2L));

		provider.close();
		provider.close();
		assertEquals(1, expired.closeCount);
		assertEquals(1, retained.closeCount);
		assertTrue(entries.isEmpty());
		assertNull(provider.lookup(3L));
	}

	private static final class TestProvider extends CachedFileProvider<TestResource> {
		private TestProvider(Path path) {
			super(path);
		}

		private TestResource lookup(long key) {
			return get(key, path -> {
				throw new AssertionError("A closed provider must not create resources");
			});
		}
	}

	private static final class TestResource extends CachedFileResource {
		private int closeCount;

		private TestResource(Path path, int lifespan) {
			super(path, lifespan);
		}

		@Override
		protected byte[] generate(byte[] oldData) {
			return oldData;
		}

		@Override
		protected void dataUpdated(byte[] data) {
		}

		@Override
		public void close() {
			closeCount++;
		}
	}
}
