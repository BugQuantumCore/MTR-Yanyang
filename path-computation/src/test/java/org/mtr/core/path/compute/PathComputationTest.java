package org.mtr.core.path.compute;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

class PathComputationTest {
	static RailGraph graph(long epoch) {
		return new RailGraph(epoch, new long[]{0, 10, 20, 30}, new long[4], new long[4],
			new int[]{0, 1, 2, 3, 3}, new int[]{1, 2, 3}, new int[]{0, 0, 0},
			new int[]{0, 0, 0}, new long[]{10, 10, 10}, new boolean[3]);
	}

	@Test
	void searchReturnsOriginalOrderedNodesAndOwnsItsInput() {
		long[] x = {0, 10};
		int[] targets = {1};
		RailGraph graph = new RailGraph(1, x, new long[2], new long[2], new int[]{0, 1, 1}, targets,
			new int[]{0}, new int[]{0}, new long[]{10}, new boolean[1]);
		x[1] = -100;
		targets[0] = 0;
		assertArrayEquals(new int[]{1}, new RailSearch().search(graph, 0, 1));
		assertArrayEquals(new int[0], new RailSearch().search(graph, 1, 0));
		assertArrayEquals(new int[0], new RailSearch().search(graph, 1, 1));
	}

	@Test
	void mergesRepeatedRequestsCachesAndRejectsOldEpoch() throws Exception {
		try (PathComputePool pool = new PathComputePool(2, 8, true)) {
			PathBatcher batcher = new PathBatcher(pool, graph(1));
			PathBatcher.Ticket first = batcher.request(0, 3);
			for (int i = 0; i < 1000; i++) assertSame(first, batcher.request(0, 3));
			batcher.flush();
			await(batcher, first);
			assertArrayEquals(new int[]{1, 2, 3}, first.result(1));
			assertSame(first, batcher.request(0, 3));
			assertNull(first.result(2));
			batcher.invalidate();
			assertNull(first.result(1));
			assertNull(batcher.request(0, 3));
			assertEquals(1, pool.metrics.computations.sum());
			assertEquals(1000, pool.metrics.merged.sum());
			assertEquals(1, pool.metrics.cacheHits.sum());
		}
	}

	@Test
	void saturatedQueueFallsBackWithoutBlockingAndWorkersSurviveFailure() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (PathComputePool pool = new PathComputePool(1, 1, true)) {
			assertTrue(pool.execute(() -> {
				started.countDown();
				try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			}));
			assertTrue(started.await(5, TimeUnit.SECONDS));
			assertTrue(pool.execute(() -> { throw new IllegalStateException("intentional test failure"); }));
			PathBatcher batcher = new PathBatcher(pool, graph(1));
			PathBatcher.Ticket ticket = batcher.request(0, 3);
			batcher.flush();
			batcher.collect();
			assertTrue(ticket.fallback());
			release.countDown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (pool.metrics.failures.sum() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
			assertEquals(1, pool.metrics.failures.sum());
			PathBatcher next = new PathBatcher(pool, graph(2));
			PathBatcher.Ticket second = next.request(0, 3);
			next.flush();
			await(next, second);
			assertArrayEquals(new int[]{1, 2, 3}, second.result(2));
		} finally {
			release.countDown();
		}
	}

	@Test
	void processorLimitsAndClosedPoolUseSafeFallback() {
		assertEquals(0, PathComputePool.workerCount(1, 0));
		assertEquals(0, PathComputePool.workerCount(2, 8));
		assertEquals(1, PathComputePool.workerCount(3, 8));
		assertEquals(8, PathComputePool.workerCount(32, 0));
		assertEquals(12, PathComputePool.workerCount(32, 100));
		PathComputePool pool = new PathComputePool(2, 8, true);
		pool.close();
		assertFalse(pool.execute(() -> fail("closed pool executed work")));
		assertTrue(pool.isTerminated());
	}

	@Test
	void invalidationBeforeWorkerCompletionAndShutdownDiscardQueuedResults() throws Exception {
		CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
		PathComputePool pool = new PathComputePool(1, 8, true);
		try {
			pool.execute(() -> {
				started.countDown();
				try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			});
			assertTrue(started.await(5, TimeUnit.SECONDS));
			PathBatcher old = new PathBatcher(pool, graph(1));
			PathBatcher.Ticket stale = old.request(0, 3);
			old.flush();
			old.invalidate();
			assertTrue(stale.fallback());
			assertNull(stale.result(1));
			PathBatcher next = new PathBatcher(pool, graph(2));
			PathBatcher.Ticket queued = next.request(0, 3);
			next.flush();
			pool.close();
			next.collect();
			assertTrue(queued.fallback());
			assertTrue(pool.isTerminated());
			assertEquals(0, pool.metrics.computations.sum());
		} finally {
			release.countDown();
			pool.close();
		}
	}

	@Test
	void workersAreIdleWithoutWorkAndLeaveNoThreadsAfterClose() throws Exception {
		PathComputePool pool = new PathComputePool(2, 8, true);
		try {
			PathBatcher batcher = new PathBatcher(pool, graph(1));
			PathBatcher.Ticket ticket = batcher.request(0, 3);
			batcher.flush();
			await(batcher, ticket);
			var bean = ManagementFactory.getThreadMXBean();
			long[] workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("TSC Path Worker-")).mapToLong(Thread::getId).toArray();
			assertTrue(workers.length > 0);
			long cpu = 0;
			for (long worker : workers) cpu += bean.getThreadCpuTime(worker);
			Thread.sleep(100);
			long after = 0;
			for (long worker : workers) {
				after += bean.getThreadCpuTime(worker);
				assertEquals(Thread.State.WAITING, bean.getThreadInfo(worker).getThreadState());
			}
			assertTrue(after - cpu < TimeUnit.MILLISECONDS.toNanos(20), "Idle workers must not spin");
		} finally { pool.close(); }
		assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("TSC Path Worker-") && t.isAlive()));
	}

	@Test
	void requestStorageIsBoundedAndOwnerThreadIsEnforced() throws Exception {
		try (PathComputePool pool = new PathComputePool(2, 8, true)) {
			PathBatcher batcher = new PathBatcher(pool, chain(1024));
			int accepted = 0;
			for (int i = 0; i < 1024; i++) if (batcher.request(i, 1023) != null) accepted++;
			assertTrue(accepted > 0 && accepted < 1024);
			java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
			Thread wrongOwner = new Thread(() -> {
				try { batcher.collect(); } catch (Throwable e) { error.set(e); }
			});
			wrongOwner.start();
			wrongOwner.join(5000);
			assertInstanceOf(IllegalStateException.class, error.get());
		}
	}

	@Test
	void laterShortBatchCannotPublishAheadOfEarlierLongBatch() throws Exception {
		try (PathComputePool pool = new PathComputePool(2, 8, true)) {
			PathBatcher batcher = new PathBatcher(pool, chain(8192));
			PathBatcher.Ticket[] tickets = new PathBatcher.Ticket[9];
			for (int i = 0; i < 8; i++) tickets[i] = batcher.request(0, 8100 + i);
			tickets[8] = batcher.request(8100, 8101);
			batcher.flush();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			do {
				batcher.collect();
				if (tickets[8].ready()) for (int i = 0; i < 8; i++) assertTrue(tickets[i].ready());
				Thread.sleep(1);
			} while (!tickets[8].ready() && System.nanoTime() < deadline);
			assertTrue(tickets[8].ready());
		}
	}

	private static RailGraph chain(int size) {
		long[] x = new long[size], duration = new long[size - 1];
		int[] offsets = new int[size + 1], targets = new int[size - 1];
		for (int i = 0; i < size; i++) { x[i] = i; offsets[i] = Math.min(i, size - 1); }
		offsets[size] = size - 1;
		for (int i = 0; i < size - 1; i++) { targets[i] = i + 1; duration[i] = 10; }
		return new RailGraph(1, x, new long[size], new long[size], offsets, targets, new int[size - 1], new int[size - 1], duration, new boolean[size - 1]);
	}

	static void await(PathBatcher batcher, PathBatcher.Ticket ticket) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!ticket.ready() && !ticket.fallback() && System.nanoTime() < deadline) {
			batcher.collect();
			Thread.sleep(1);
		}
		assertTrue(ticket.ready());
	}
}
