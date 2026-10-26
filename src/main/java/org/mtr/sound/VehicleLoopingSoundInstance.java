package org.mtr.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

public class VehicleLoopingSoundInstance extends AbstractTickableSoundInstance {

	// Distance gate, mirroring LoopingSoundInstance's own 32-block gate but a little further out
	// as vehicle sounds stay audible past that. RenderVehicles calls playVehicleSound for every
	// car of every known vehicle on every frame with no range check, and vanilla 1.21.1
	// SoundEngine has no distance cull of its own — once the ~32-channel OpenAL pool is full it
	// silently drops further play() calls. Without this gate, far-away cars permanently occupy
	// channels (a moving car can hold up to ~8: motor, run, flange, shoe, noise, compressor, …)
	// and starve the vehicles the player can actually hear, which surfaces as missing tracks at
	// busy stations. Sounds parked beyond this distance free their channels for nearby cars.
	private static final int MAX_DISTANCE = 48;

	public VehicleLoopingSoundInstance(SoundEvent event) {
		super(event, SoundSource.BLOCKS, new SingleThreadedRandomSource(0));
		looping = true;
		delay = 0;
		volume = 0;
		pitch = 1;
	}

	public void setData(float volume, float pitch, BlockPos blockPos) {
		this.pitch = pitch == 0 ? 1 : pitch;
		this.volume = volume;
		x = blockPos.getX();
		y = blockPos.getY();
		z = blockPos.getZ();

		// Park the sound while the car is out of range: stop it if it is running (freeing the
		// OpenAL channel for nearby vehicles) and don't start it otherwise. The player position
		// can be briefly null during world changes; treat that as out of range.
		final LocalPlayer clientPlayerEntity = Minecraft.getInstance().player;
		final boolean outOfRange = clientPlayerEntity == null || clientPlayerEntity.blockPosition().distManhattan(blockPos) > MAX_DISTANCE;

		final SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		if (soundManager.isActive(this)) {
			if (volume <= 0 || outOfRange) {
				soundManager.stop(this);
			}
		} else {
			if (volume > 0 && !outOfRange) {
				looping = true;
				soundManager.play(this);
			}
		}
	}

	@Override
	public void tick() {
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public boolean canPlaySound() {
		return true;
	}

	public void dispose() {
		stop();
		Minecraft.getInstance().getSoundManager().stop(this);
	}
}
