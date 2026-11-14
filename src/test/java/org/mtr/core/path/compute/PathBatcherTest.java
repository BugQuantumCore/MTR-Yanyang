package org.mtr.core.path.compute;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class PathBatcherTest {

	@Test
	void rejectedBatchFallsBackImmediatelyAndCanBeRetried() throws InterruptedException {
		final CountDownLatch workerStarted = new CountDownLatch(1);
		final CountDownLatch releaseWorker = new CountDownLatch(1);
		try (final PathComputePool pool = new PathComputePool(1, 1, false)) {
			try {
				Assertions.assertTrue(pool.execute(() -> {
					workerStarted.countDown();
					try {
						releaseWorker.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}));
				Assertions.assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
				Assertions.assertTrue(pool.execute(() -> {}));
				final RailGraph graph = new RailGraph(1, new long[]{0, 1}, new long[2], new long[2],
						new int[]{0, 1, 1}, new int[]{1}, new int[]{0}, new int[]{0}, new long[]{1}, new boolean[]{false});
				final PathBatcher batcher = new PathBatcher(pool, graph);
				final PathBatcher.Ticket rejected = batcher.request(0, 1);
				batcher.flush();
				Assertions.assertTrue(rejected.fallback());
				Assertions.assertFalse(rejected.ready());
				final PathBatcher.Ticket retry = batcher.request(0, 1);
				Assertions.assertNotSame(rejected, retry);
				Assertions.assertFalse(retry.fallback());
			} finally {
				releaseWorker.countDown();
			}
		}
	}
}
