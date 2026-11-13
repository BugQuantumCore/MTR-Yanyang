package org.mtr.render;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Objects;

/** A bounded FIFO that never silently discards accepted resource-generation work. */
final class BoundedWorkQueue<T> {

	private final ArrayDeque<T> queue = new ArrayDeque<>();
	private final int capacity;

	BoundedWorkQueue(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Queue capacity must be positive");
		}
		this.capacity = capacity;
	}

	synchronized boolean offer(T value) {
		Objects.requireNonNull(value);
		if (queue.size() >= capacity) {
			return false;
		}
		queue.addLast(value);
		return true;
	}

	@Nullable
	synchronized T poll() {
		return queue.pollFirst();
	}

	synchronized boolean isEmpty() {
		return queue.isEmpty();
	}
}
