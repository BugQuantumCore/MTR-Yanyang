package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Client;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerRailVisibilityTest {

	@Test
	void matchesOriginalScanAcrossMovementRadiusDisconnectAndReconnect() {
		final Fixture fixture = new Fixture();
		fixture.addRail(-300, 64, -100, 700);
		fixture.addRail(10000, 90, 1000, 200);
		final RecordingClient first = fixture.client("first", -200, 64, -100, 300);
		final RecordingClient second = fixture.client("second", 10000, 90, 1000, 100);
		fixture.verifyTick(true);
		fixture.verifyTick(false); // Cached visibility still sends keep callbacks every tick.
		first.move(10000, 90, 1000, 100);
		second.move(-200, 10000, -100, 100); // Y participates in closeTo.
		fixture.verifyTick(false);
		first.move(10000, 90, 1000, 0);
		fixture.verifyTick(true);
		fixture.clients.remove(first);
		fixture.verifyTick(false);
		final RecordingClient replacement = new RecordingClient("replacement", first.uuid);
		replacement.move(10000, 90, 1000, 100);
		fixture.clients.add(replacement);
		fixture.verifyTick(false);
		fixture.clients.clear();
		fixture.verifyTick(false);
	}

	@Test
	void preservesClientOrderAndHandlesTopologyChangesWithoutSync() {
		final Fixture fixture = new Fixture();
		final Rail original = fixture.addRail(0, 64, 0, 30);
		final RecordingClient first = fixture.client("first", 0, 64, 0, 100);
		final RecordingClient second = fixture.client("second", 5, 64, 0, 100);
		fixture.verifyTick(false);
		fixture.clients.clear();
		fixture.clients.add(second);
		fixture.clients.add(first);
		fixture.verifyTick(true);
		fixture.data.rails.remove(original);
		fixture.addRail(10, 64, 0, 40); // Same count, unknown identity must work immediately.
		fixture.verifyTick(false);
		fixture.verifyTick(true);
		fixture.addRail(50, 64, 0, 40);
		fixture.verifyTick(false);
		fixture.visibility.invalidate();
		fixture.verifyTick(false);
	}

	@Test
	void matchesOriginalPredicateAtCellEdgesAndForOverflowRailsAndRadii() {
		final Fixture fixture = new Fixture();
		fixture.addRail(-256, 64, -256, 512);
		fixture.addRail(-1000000, 64, 0, 2000000); // Larger than the cell budget.
		fixture.addRail(1L << 42, 64, 0, 100); // Outside packed grid coordinate range.
		final RecordingClient client = fixture.client("edge", -256, 64, -256, 0);
		for (final double radius : new double[]{0, 1, 256, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
			client.radius = radius;
			fixture.verifyTick(false);
		}
		client.move(1L << 42, 64, 0, 10);
		fixture.verifyTick(true);
	}

	@Test
	void randomizedVisibleCallbacksEqualOriginalScan() {
		final Fixture fixture = new Fixture();
		final Random random = new Random(1912026);
		for (int i = 0; i < 300; i++) {
			fixture.addRail(random.nextInt(10000) - 5000, random.nextInt(200), random.nextInt(10000) - 5000, random.nextInt(1000) + 1);
		}
		for (int i = 0; i < 8; i++) {
			fixture.client("client" + i, 0, 64, 0, 500);
		}
		for (int tick = 0; tick < 25; tick++) {
			for (final Client value : fixture.clients) {
				((RecordingClient) value).move(random.nextInt(10000) - 5000, random.nextInt(200), random.nextInt(10000) - 5000, random.nextInt(1500));
			}
			fixture.verifyTick((tick & 1) == 0);
			fixture.verifyTick(false);
		}
	}

	@Test
	void handlesRailReorderingAndRepeatedCallsWithinTheSamePhase() {
		final Fixture fixture = new Fixture();
		final Rail near = fixture.addRail(0, 64, 0, 30);
		final Rail far = fixture.addRail(1000, 64, 0, 30);
		fixture.client("near", 10, 64, 0, 20);
		fixture.client("far", 1010, 64, 0, 20);
		fixture.verifyTick(false);
		fixture.actual.clear();
		fixture.visibility.beginRailTick();
		try {
			fixture.visibility.updateClients(far, false);
			fixture.visibility.updateClients(near, true);
			fixture.visibility.updateClients(far, true);
		} finally {
			fixture.visibility.endRailTick();
		}
		assertEquals(List.of("far:" + far.getHexId() + ":false", "near:" + near.getHexId() + ":true", "far:" + far.getHexId() + ":true"), fixture.actual);
		fixture.data.rails.clear();
		fixture.data.rails.add(far);
		fixture.data.rails.add(near);
		fixture.verifyTick(false);
		fixture.verifyTick(true); // The following phase uses the repaired traversal order.
	}

	@Test
	void snapshotsClientInputsOncePerTickAndFallsBackOutsideRailPhase() {
		final Fixture fixture = new Fixture();
		for (int i = 0; i < 100; i++) {
			fixture.addRail(i * 100, 64, 0, 10);
		}
		final RecordingClient client = fixture.client("client", 0, 64, 0, 300);
		fixture.visibility.beginRailTick();
		for (final Rail rail : fixture.data.rails) {
			fixture.visibility.updateClients(rail, false);
		}
		fixture.visibility.endRailTick();
		assertEquals(1, client.positionReads);
		assertEquals(1, client.radiusReads);
		fixture.actual.clear();
		client.move(9900, 64, 0, 20);
		final Rail lastRail = fixture.data.rails.toArray(new Rail[0])[99];
		fixture.visibility.updateClients(lastRail, true);
		assertEquals(List.of("client:" + lastRail.getHexId() + ":true"), fixture.actual);
	}

	private static final class Fixture {
		private final Data data = new Data() {};
		private final ObjectArraySet<Client> clients = new ObjectArraySet<>();
		private final ServerRailVisibility visibility = new ServerRailVisibility(data, clients);
		private final List<String> actual = new ArrayList<>();

		private Rail addRail(long x, long y, long z, long length) {
			final Rail rail = Rail.newRail(new Position(x, y, z), Angle.E, new Position(x + length, y, z), Angle.W,
					Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 0, false, true, true, false, false, TransportMode.TRAIN);
			data.rails.add(rail);
			return rail;
		}

		private RecordingClient client(String name, long x, long y, long z, double radius) {
			final RecordingClient client = new RecordingClient(name, UUID.randomUUID());
			client.output = actual;
			client.move(x, y, z, radius);
			clients.add(client);
			return client;
		}

		private void verifyTick(boolean needsUpdate) {
			actual.clear();
			final List<String> expected = new ArrayList<>();
			for (final Rail rail : data.rails) {
				for (final Client value : clients) {
					final RecordingClient client = (RecordingClient) value;
					client.output = actual;
					if (rail.closeTo(client.position, client.radius)) {
						expected.add(client.name + ":" + rail.getHexId() + ":" + needsUpdate);
					}
				}
			}
			visibility.beginRailTick();
			try {
				for (final Rail rail : data.rails) {
					visibility.updateClients(rail, needsUpdate);
				}
			} finally {
				visibility.endRailTick();
			}
			assertEquals(expected, actual);
		}
	}

	private static final class RecordingClient extends Client {
		private final String name;
		private Position position;
		private double radius;
		private int positionReads;
		private int radiusReads;
		private List<String> output;

		private RecordingClient(String name, UUID uuid) {
			super(uuid);
			this.name = name;
		}

		private void move(long x, long y, long z, double radius) {
			position = new Position(x, y, z);
			this.radius = radius;
		}

		@Override
		public Position getPosition() {
			positionReads++;
			return position;
		}

		@Override
		public double getUpdateRadius() {
			radiusReads++;
			return radius;
		}

		@Override
		public void update(Rail rail, boolean needsUpdate) {
			output.add(name + ":" + rail.getHexId() + ":" + needsUpdate);
		}
	}
}
