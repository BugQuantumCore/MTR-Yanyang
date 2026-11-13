package org.mtr.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ForwardedEndpointTest {

	@Test
	void acceptsOnlyPathsOnTheConfiguredLocalServer() {
		assertTrue(PacketForwardClientRequest.isLocalEndpoint("/"));
		assertTrue(PacketForwardClientRequest.isLocalEndpoint("/mtr/api/map/stations?dimension=overworld"));
		for (final String endpoint : new String[]{"", "@example.org/", "//example.org/", "https://example.org/", "/path#fragment", "/bad\npath", "/\\example.org/"}) {
			assertFalse(PacketForwardClientRequest.isLocalEndpoint(endpoint), endpoint);
		}
	}
}
