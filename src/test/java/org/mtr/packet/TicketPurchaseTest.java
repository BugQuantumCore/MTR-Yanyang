package org.mtr.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TicketPurchaseTest {

	@Test
	void onlyAdvertisedPurchaseOptionsHaveAValue() {
		for (final int invalid : new int[]{Integer.MIN_VALUE, -1, 10, 31, Integer.MAX_VALUE}) {
			assertEquals(0, PacketAddBalance.getEmeraldAmount(invalid));
			assertEquals(0, PacketAddBalance.getAddBalanceAmount(invalid));
		}
		for (int option = 0; option < PacketAddBalance.PURCHASE_OPTION_COUNT; option++) {
			assertEquals(1 << option, PacketAddBalance.getEmeraldAmount(option));
			assertEquals((1 << option) * (10 + option), PacketAddBalance.getAddBalanceAmount(option));
		}
	}
}
