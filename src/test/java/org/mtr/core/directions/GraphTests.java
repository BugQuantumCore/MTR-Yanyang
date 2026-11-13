package org.mtr.core.directions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class GraphTests {

	@TempDir
	Path directory;

	@Test
	void walkingTransfersCoverTheFullRadiusAcrossGridBoundaries() {
		assertConnected(new Position(0, 0, 0), new Position(500, 0, 0));
		assertConnected(new Position(249, 0, 0), new Position(500, 0, 0));
		assertConnected(new Position(-1, 0, 0), new Position(250, 0, 0));
		assertConnected(new Position(0, 0, -500), new Position(0, 0, 0));
	}

	@Test
	void walkingTransfersStillRespectThreeDimensionalManhattanDistance() {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, directory, false);
		final Platform first = platform(simulator, new Position(0, 0, 0));
		final Platform second = platform(simulator, new Position(250, 1, 250));
		final Graph graph = new Graph(simulator);
		assertNull(graph.refresh(0));
		final var connections = graph.refresh(1);
		assertNotNull(connections);
		assertFalse(connections.containsKey(first.getId()));
		assertFalse(connections.containsKey(second.getId()));
	}

	private void assertConnected(Position firstPosition, Position secondPosition) {
		final Simulator simulator = new Simulator("test", new String[]{"test"}, directory, false);
		final Platform first = platform(simulator, firstPosition);
		final Platform second = platform(simulator, secondPosition);
		final Graph graph = new Graph(simulator);
		assertNull(graph.refresh(0));
		final var connections = graph.refresh(1);
		assertNotNull(connections);
		assertTrue(connections.containsKey(first.getId()));
		assertTrue(connections.get(first.getId()).containsKey(second.getId()));
		assertTrue(connections.get(second.getId()).containsKey(first.getId()));
	}

	private static Platform platform(Simulator simulator, Position position) {
		final Platform platform = new Platform(position, position, TransportMode.TRAIN, simulator);
		simulator.platforms.add(platform);
		return platform;
	}
}
