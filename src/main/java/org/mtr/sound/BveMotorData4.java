package org.mtr.sound;

import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Locale;

public final class BveMotorData4 extends BveMotorDataBase { // 4 for BVE4 and OpenBVE

	private final Channel[] channels = new Channel[4];
	private final int soundCount;

	public BveMotorData4(String baseName) {
		for (int i = 0; i < channels.length; ++i) {
			channels[i] = new Channel();
		}

		String textContent = BveVehicleSoundConfig.readResource(ResourceLocation.parse(baseName + "/train.dat"));
		String[] lines = textContent.split("[\\r\\n]+");
		String section = "";
		for (final String line : lines) {
			final String lineTrim = line.trim().toLowerCase(Locale.ENGLISH);
			if (StringUtils.isEmpty(lineTrim)) {
				continue;
			}
			if (lineTrim.startsWith("#")) {
				section = lineTrim.substring(1).trim().toLowerCase(Locale.ENGLISH);
				continue;
			}
			switch (section) {
				case "motor_p1":
				case "motor_p2":
				case "motor_b1":
				case "motor_b2":
					int listIndex = (section.charAt(6) == 'p' ? 0 : 2) + (section.charAt(7) == '1' ? 0 : 1);
					String[] tokens = lineTrim.split(",");
					channels[listIndex].soundIds.add(Integer.parseInt(tokens[0]));
					channels[listIndex].pitches.add(Float.parseFloat(tokens[1]) / 100F);
					channels[listIndex].volumes.add(Float.parseFloat(tokens[2]) / 128F);
					channels[listIndex].maxSoundId = Math.max(channels[listIndex].maxSoundId, Integer.parseInt(tokens[0]));
					channels[listIndex].maxEntryId++;
					break;
			}
		}

		int maxSoundId = -1;
		for (final Channel channel : channels) {
			maxSoundId = Math.max(maxSoundId, channel.maxSoundId);
		}
		soundCount = maxSoundId + 1;
	}

	@Override
	public int getSoundCount() {
		return soundCount;
	}

	@Override
	public float getPitch(int index, float speed, float power) {
		if (power == 0) {
			return 0;
		}
		int offset = power > 0 ? 0 : 2;
		int entryIndex = (int) (speed / 0.2F);
		if (index == getSafeInt(channels[offset].soundIds, Math.min(channels[offset].maxEntryId, entryIndex))) {
			return getSafeFloat(channels[offset].pitches, entryIndex);
		}
		if (index == getSafeInt(channels[offset + 1].soundIds, Math.min(channels[offset + 1].maxEntryId, entryIndex))) {
			return getSafeFloat(channels[offset + 1].pitches, entryIndex);
		}
		return 0;
	}

	@Override
	public float getVolume(int index, float speed, float power) {
		if (power == 0) {
			return 0;
		}
		int offset = power > 0 ? 0 : 2;
		int entryIndex = (int) (speed / 0.2F);
		if (index == getSafeInt(channels[offset].soundIds, Math.min(channels[offset].maxEntryId, entryIndex))) {
			return getSafeFloat(channels[offset].volumes, entryIndex) * Math.abs(power);
		}
		if (index == getSafeInt(channels[offset + 1].soundIds, Math.min(channels[offset + 1].maxEntryId, entryIndex))) {
			return getSafeFloat(channels[offset + 1].volumes, entryIndex) * Math.abs(power);
		}
		return 0;
	}

	// Both accessors guard against EMPTY channel lists: a sound.cfg that defines [Motor]
	// entries while train.dat lacks the #motor_p1/p2/b1/b2 rows leaves the braking channels
	// (or all of them) empty, and Math.clamp(index, 0, -1) throws IllegalArgumentException —
	// reachable every frame from RenderVehicles.render, i.e. a client render crash on a
	// malformed pack. Typed names avoid the erasure clash a getSafe(List<Integer>, int) /
	// getSafe(List<Float>, int) overload pair would have.
	private static int getSafeInt(ArrayList<Integer> list, int index) {
		if (list.isEmpty()) {
			return -1; // Never matches a real sound id, so the whole channel is skipped.
		}
		return list.get(Math.clamp(index, 0, list.size() - 1));
	}

	private static float getSafeFloat(ArrayList<Float> list, int index) {
		if (list.isEmpty()) {
			return 0; // A zero pitch/volume silences the channel.
		}
		return list.get(Math.clamp(index, 0, list.size() - 1));
	}

	public static class Channel {
		public final ArrayList<Integer> soundIds = new ArrayList<>();
		public final ArrayList<Float> pitches = new ArrayList<>();
		public final ArrayList<Float> volumes = new ArrayList<>();
		public int maxEntryId = -1;
		public int maxSoundId = -1;
	}
}
