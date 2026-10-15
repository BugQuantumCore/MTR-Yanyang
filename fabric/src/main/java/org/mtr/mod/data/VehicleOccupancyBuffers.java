package org.mtr.mod.data;

import org.mtr.core.data.Position;
import org.mtr.core.data.VehiclePosition;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/** Rotates the core's previous/current occupancy windows without changing their read order. */
public final class VehicleOccupancyBuffers {

	private VehicleOccupancyBuffers() {
	}

	public static void rotate(ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> buffers) {
		final Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>> retired = buffers.isEmpty() ? null : buffers.remove(0);
		boolean retained = false;
		for (int i = 0; i < buffers.size(); i++) {
			retained |= buffers.get(i) == retired;
		}
		if (!retained && retired instanceof VehicleOccupancyMap) {
			((VehicleOccupancyMap) retired).resetForNextTick();
			buffers.add(retired);
		} else {
			buffers.add(new VehicleOccupancyMap());
		}
	}

	public interface Owner {
		void mtr$invalidateVehicleOccupancy();
	}
}
