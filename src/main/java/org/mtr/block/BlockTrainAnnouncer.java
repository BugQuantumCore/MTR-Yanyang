package org.mtr.block;

import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.mtr.MTR;
import org.mtr.client.IDrawing;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.registry.BlockEntityTypes;

import java.util.Arrays;
import java.util.stream.Collectors;

public class BlockTrainAnnouncer extends BlockTrainSensorBase {

	private static final Long2ObjectAVLTreeMap<ObjectArrayList<Runnable>> QUEUE = new Long2ObjectAVLTreeMap<>();

	public BlockTrainAnnouncer(BlockBehaviour.Properties settings) {
		super(settings);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new TrainAnnouncerBlockEntity(blockPos, blockState);
	}

	public static void processQueue() {
		final long currentMillis = System.currentTimeMillis();
		while (!QUEUE.isEmpty() && QUEUE.firstLongKey() <= currentMillis) {
			// Remove before running: a failed announcement must not poison every later tick.
			final ObjectArrayList<Runnable> tasks = QUEUE.remove(QUEUE.firstLongKey());
			for (final Runnable task : tasks) {
				try {
					task.run();
				} catch (RuntimeException e) {
					MTR.LOGGER.warn("Failed to play a queued train announcement", e);
				}
			}
		}
	}

	public static void clearQueue() {
		QUEUE.clear();
	}

	public static class TrainAnnouncerBlockEntity extends BlockEntityBase implements Utilities {

		@Getter
		private String message = "";
		@Getter
		private String soundId = "";
		@Getter
		private int delay;
		private long lastAnnouncedMillis;
		private static final int ANNOUNCE_COOLDOWN_MILLIS = 20000;
		private static final String KEY_MESSAGE = "message";
		private static final String KEY_SOUND_ID = "sound_id";
		private static final String KEY_DELAY = "delay";

		public TrainAnnouncerBlockEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_ANNOUNCER.get(), pos, state);
		}

		@Override
		protected void readNbt(CompoundTag nbtCompound) {
			super.readNbt(nbtCompound);
			message = nbtCompound.getString(KEY_MESSAGE);
			soundId = nbtCompound.getString(KEY_SOUND_ID);
			delay = nbtCompound.getInt(KEY_DELAY);
		}

		@Override
		protected void writeNbt(CompoundTag nbtCompound) {
			super.writeNbt(nbtCompound);
			nbtCompound.putString(KEY_MESSAGE, message);
			nbtCompound.putString(KEY_SOUND_ID, soundId);
			nbtCompound.putInt(KEY_DELAY, delay);
		}

		public void setData(LongAVLTreeSet filterRouteIds, boolean stoppedOnly, boolean movingOnly, String message, String soundId, int delay) {
			this.message = message;
			this.soundId = soundId;
			this.delay = delay;
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		public void announce() {
			final long currentMillis = System.currentTimeMillis();
			if (currentMillis - lastAnnouncedMillis >= ANNOUNCE_COOLDOWN_MILLIS) {
				final ObjectArrayList<Runnable> tasks = QUEUE.computeIfAbsent(currentMillis + (long) delay * MILLIS_PER_SECOND, ignored -> new ObjectArrayList<>());
				// Capture values, not this block entity, for delayed playback.
				final String announcementMessage = message;
				final ResourceLocation soundLocation = soundId.isEmpty() ? null : ResourceLocation.tryParse(soundId);
				if (!announcementMessage.isEmpty()) {
					tasks.add(() -> IDrawing.narrateOrAnnounce(Utilities.formatName(announcementMessage), Arrays.stream(announcementMessage.split("\\|")).map(Component::literal).collect(Collectors.toCollection(ObjectArrayList::new))));
				}
				if (soundLocation != null) {
					tasks.add(() -> {
						final LocalPlayer clientPlayerEntity = Minecraft.getInstance().player;
						if (clientPlayerEntity != null) {
							clientPlayerEntity.playSound(SoundEvent.createVariableRangeEvent(soundLocation), 1000, 1);
						}
					});
				}
				lastAnnouncedMillis = currentMillis;
			}
		}
	}
}
