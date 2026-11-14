package org.mtr.cache;

import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * A provider for a number of {@link CachedFileResource} objects.
 *
 * @param <T> the {@link CachedFileResource} implementation
 */
public abstract class CachedFileProvider<T extends CachedFileResource> implements AutoCloseable {

	private final Path cacheDirectory;
	private final Long2ObjectAVLTreeMap<T> dataMap = new Long2ObjectAVLTreeMap<>();
	private boolean closed;

	private static long nextCreationTime;

	public CachedFileProvider(Path cacheDirectory) {
		this.cacheDirectory = cacheDirectory;
		try {
			Files.createDirectories(cacheDirectory);
		} catch (IOException e) {
			MTR.LOGGER.error("", e);
		}
	}

	public final void tick() {
		final LongArrayList keysToRemove = new LongArrayList();
		dataMap.forEach((key, data) -> {
			if (data.canBeRemoved()) {
				keysToRemove.add(key);
			}
		});
		keysToRemove.forEach((long key) -> {
			final T removed = dataMap.remove(key);
			if (removed != null) {
				removed.close();
			}
		});
	}

	/** Call on the provider's owner thread, including when discarding a world. */
	@Override
	public final void close() {
		closed = true;
		dataMap.values().forEach(CachedFileResource::close);
		dataMap.clear();
	}

	@Nullable
	protected final T get(long key, Function<Path, T> createInstance) {
		if (closed) {
			return null;
		}
		final T data = dataMap.get(key);
		final long currentTime = System.currentTimeMillis();
		if (currentTime < nextCreationTime) {
			if (data == null) {
				return null;
			} else {
				data.get();
				return data;
			}
		} else {
			if (data == null) {
				nextCreationTime = currentTime + 100;
				final T newData = createInstance.apply(cacheDirectory);
				dataMap.put(key, newData);
				newData.get();
				return newData;
			} else {
				data.get();
				return data;
			}
		}
	}
}
