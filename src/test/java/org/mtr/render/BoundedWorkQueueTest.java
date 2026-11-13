package org.mtr.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BoundedWorkQueueTest {

	@Test
	void overflowPreservesAcceptedJobsAndAllowsRetry() {
		final BoundedWorkQueue<String> queue = new BoundedWorkQueue<>(2);
		assertTrue(queue.offer("station sign"));
		assertTrue(queue.offer("route map"));
		assertFalse(queue.offer("exit sign"));
		assertEquals("station sign", queue.poll());
		assertTrue(queue.offer("exit sign"));
		assertEquals("route map", queue.poll());
		assertEquals("exit sign", queue.poll());
		assertTrue(queue.isEmpty());
		assertNull(queue.poll());
	}
}
