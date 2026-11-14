package org.mtr.packet;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.Items;
import org.mtr.data.TicketSystem;

public final class PacketAddBalance extends PacketHandler {

	private final int index;

	private static final int EMERALD_TO_DOLLAR = 10;
	public static final int PURCHASE_OPTION_COUNT = 10;

	public PacketAddBalance(PacketBufferReceiver packetBufferReceiver) {
		index = packetBufferReceiver.readInt();
	}

	public PacketAddBalance(int index) {
		this.index = index;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeInt(index);
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayer serverPlayerEntity) {
		final int emeralds = getEmeraldAmount(index);
		if (emeralds == 0 || serverPlayerEntity.getInventory().countItem(Items.EMERALD) < emeralds) {
			return;
		}
		final ServerLevel serverWorld = serverPlayerEntity.serverLevel();
		// The server thread owns both inventory and balance; charge before granting credit.
		ContainerHelper.clearOrCountMatchingItems(serverPlayerEntity.getInventory(), itemStack -> itemStack.getItem().equals(Items.EMERALD), emeralds, false);
		TicketSystem.addBalance(serverWorld, serverPlayerEntity, getAddBalanceAmount(index));
		serverWorld.playSound(null, serverPlayerEntity.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 1, 1);
	}

	public static int getAddBalanceAmount(int index) {
		return getEmeraldAmount(index) * (EMERALD_TO_DOLLAR + (index >= 0 && index < PURCHASE_OPTION_COUNT ? index : 0));
	}

	public static int getEmeraldAmount(int index) {
		return index >= 0 && index < PURCHASE_OPTION_COUNT ? 1 << index : 0;
	}
}
