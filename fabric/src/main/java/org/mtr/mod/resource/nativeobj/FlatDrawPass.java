package org.mtr.mod.resource.nativeobj;

import org.mtr.mapping.render.batch.MaterialProperties;

import java.util.Arrays;

/**
 * The flat draw pass: a per-frame draw list with no per-frame allocation and no JNI crossing
 * per GL call, reproducing the reference draw path's attribute state exactly.
 *
 * <h2>What this replaces and why</h2>
 *
 * <p>The native draw list routes every surviving GL call from C++ back into Java through a
 * {@code CallVoidMethod} upcall, which then calls LWJGL down into the driver again. Each GL
 * call therefore costs one upcall plus one downcall, where the reference path pays one
 * downcall - and an upcall costs several times what an LWJGL downcall does. On a scene with
 * thousands of vehicle draws per frame the native list loses more to the crossings than its
 * attribute cache saves, which showed up as frame stutter. The same economics mean a Java
 * loop with the same change-detection cache wins outright: it issues the same GL calls the
 * native cache issues, but every one of them is a plain LWJGL downcall.</p>
 *
 * <h2>Semantics</h2>
 *
 * <p>{@code BatchManager.RenderCall.draw} on the reference path, per vertex array:</p>
 *
 * <pre>{@code
 * vertexArray.bind();
 * queuedState.apply();                                          // colour, light, overlay, model matrix
 * vertexArray.materialProperties.vertexAttributeState.apply(); // material Kd colour, BRIGHT lightmap
 * vertexArray.draw();
 * }</pre>
 *
 * <p>The material state is applied second, so the material's {@code Kd} colour and the
 * full-bright lightmap the BRIGHT shader types carry win over the queued values. This pass
 * reproduces exactly that final state per draw: the effective colour is the material's when
 * it has one, the queued colour otherwise; the effective lightmap is the material's when it
 * has one, the queued per-item light otherwise. Applying the effective values directly is
 * equivalent, because no draw happens between the reference's two applications - only the
 * values in force at {@code glDrawElements} are observable.</p>
 *
 * <p>The writes also follow the reference's order around the vertex array: bind first, then
 * write, then draw. Current generic vertex attribute values are ambiguous driver territory -
 * Mesa keeps them as context state, but a driver that treats them as vertex array object
 * state would let a pre-bind write land in the wrong VAO and let the bind restore the VAO's
 * own stale values, which reads as a permanent wash or blackout on the model. Writing every
 * attribute while the target VAO is bound is correct under both readings.</p>
 *
 * <p>The queued light is per car, sampled at each car's world position, so it is applied per
 * item with change detection rather than once per pass. The previous native flush applied it
 * once per material run, which dimmed every car in a run to the first car's light.</p>
 *
 * <h2>Ordering</h2>
 *
 * <p>Items are grouped by material {@code equals}, like the reference's
 * {@code HashMap<MaterialProperties, List<RenderCall>>}, so each distinct material binds its
 * shader once per frame regardless of how the models interleave in the queue. Within a
 * material the queue order is preserved - a stable counting sort. Materials draw in the
 * reference's pass order: opaque, then cutout, then translucent last, with translucent
 * dropped entirely when the caller passes {@code renderTranslucent == false}.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>Every array here is persistent and grows by doubling. A frame costs one 64-byte pose
 * copy per item, and nothing else. The bucket registry resolves materials to integer bucket
 * ids once per model at registration, so the per-frame path compares integers and never
 * calls {@code equals} or {@code hashCode}.</p>
 *
 * <h2>State protection</h2>
 *
 * <p>This class emits GL calls through the {@link GlSink} and touches nothing else, so the
 * caller brackets the pass in {@code GlStateTracker} capture and restore exactly as the
 * reference's {@code OptimizedRenderer.render} does. The sink's {@code useCustomShader} and
 * {@code useFloatAttributeFallback} answers are read once per pass, after the capture, so
 * the gl4ES detection inside the tracker is fresh.</p>
 */
public final class FlatDrawPass {

	/** Pass index for materials that are neither translucent nor the cutout hack. */
	private static final int PASS_OPAQUE = 0;

	/** Pass index for the cutout hack materials (beacon beam translucency workaround). */
	private static final int PASS_CUTOUT = 1;

	/** Pass index for translucent materials. Drawn last, and dropped when translucency is off. */
	private static final int PASS_TRANSLUCENT = 2;

	/** {@code OverlayTexture.getDefaultUvMapped()}, which the queued state always carries. */
	private static final int OVERLAY_DEFAULT = (10 << 16) | 10;

	/** Attribute locations, matching {@code VertexAttributeType}'s declarations. */
	private static final int ATTR_COLOR = 1;
	private static final int ATTR_UV_OVERLAY = 3;
	private static final int ATTR_UV_LIGHTMAP = 4;
	private static final int ATTR_MATRIX_MODEL = 6;

	/** Grows one bucket array. */
	private static int[] growInts(int[] array, int required) {
		int capacity = array.length;
		while (capacity < required) {
			capacity = Math.max(capacity * 2, 16);
		}
		return Arrays.copyOf(array, capacity);
	}

	/** Grows the pose store, whose element is a group of sixteen floats. */
	private static float[] growPoses(float[] array, int requiredPoses) {
		int capacityPoses = array.length >> 4;
		while (capacityPoses < requiredPoses) {
			capacityPoses = Math.max(capacityPoses * 2, 16);
		}
		return Arrays.copyOf(array, capacityPoses << 4);
	}

	/** Grows an object array. */
	private static MaterialProperties[] growMaterials(MaterialProperties[] array, int required) {
		int capacity = array.length;
		while (capacity < required) {
			capacity = Math.max(capacity * 2, 16);
		}
		return Arrays.copyOf(array, capacity);
	}

	/** Grows a boolean array. */
	private static boolean[] growBooleans(boolean[] array, int required) {
		int capacity = array.length;
		while (capacity < required) {
			capacity = Math.max(capacity * 2, 16);
		}
		return Arrays.copyOf(array, capacity);
	}

	/**
	 * The GL calls the pass makes, on the caller's side.
	 *
	 * <p>The production sink calls LWJGL directly; a test sink records. The signatures mirror
	 * the reference's own emission choices, including the gl4ES float fallback for integer
	 * attributes and the shader-pack model-view uniform fallback for the model matrix.</p>
	 */
	public interface GlSink {

		/** {@code glVertexAttrib4f(location, x, y, z, w)}. */
		void attrib4f(int location, float x, float y, float z, float w);

		/** {@code glVertexAttribI2i(location, x, y)}. */
		void attribI2i(int location, int x, int y);

		/** The gl4ES float fallback for {@link #attribI2i}. */
		void attrib2f(int location, float x, float y);

		/** {@code glBindVertexArray(vao)}. */
		void bindVertexArray(int vao);

		/** {@code glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0)}. */
		void drawElements(int count);

		/**
		 * The shader-pack fallback for the model matrix: sets the current shader's model view
		 * matrix to the given pose and rebinds it, which is what the reference's
		 * {@code VertexAttributeState.apply} does when the custom shader cannot be used.
		 */
		void setModelViewMatrix(float[] matrix, int offset);

		/** True when the custom MTR shader is usable; read once per pass. */
		default boolean useCustomShader() {
			return true;
		}

		/** True when integer vertex attributes are unusable (gl4ES); read once per pass. */
		default boolean useFloatAttributeFallback() {
			return false;
		}
	}

	/** Sets up and tears down a material's shader, texture and blend state around a run. */
	public interface MaterialBinder {

		/** {@code ShaderManager.setupShaderBatchState(material)}. */
		void setup(MaterialProperties material);

		/** {@code ShaderManager.cleanupShaderBatchState()}. */
		void cleanup();
	}

	// -----------------------------------------------------------------------
	// Bucket registry, resolved once per material
	// -----------------------------------------------------------------------

	private MaterialProperties[] bucketMaterial = new MaterialProperties[0];
	private int[] bucketPass = new int[0];
	private int[] bucketColor = new int[0];
	private boolean[] bucketHasColor = new boolean[0];
	private int[] bucketLight = new int[0];
	private boolean[] bucketHasLight = new boolean[0];
	private int bucketCount;

	/** Bucket ids ordered by (pass, first registration), so runs draw in pass order. */
	private int[] bucketOrder = new int[0];
	private int bucketOrderCount;

	// -----------------------------------------------------------------------
	// Per-frame item storage, reused every frame
	// -----------------------------------------------------------------------

	private int[] itemBucket = new int[0];
	private int[] itemVao = new int[0];
	private int[] itemIndexCount = new int[0];
	private int[] itemLight = new int[0];
	private int[] itemColor = new int[0];
	private float[] itemPose = new float[0];
	private int itemCount;

	/** The scattered per-run order, filled by a stable counting sort at render. */
	private int[] sortedVao = new int[0];
	private int[] sortedIndexCount = new int[0];
	private int[] sortedLight = new int[0];
	private int[] sortedColor = new int[0];
	private float[] sortedPose = new float[0];

	/** One run per live bucket in {@link #bucketOrder} order. */
	private int[] runBucket = new int[0];
	private int[] runStart = new int[0];
	private int[] runCount = new int[0];

	/** Scratch for the counting sort, indexed by bucket id. */
	private int[] bucketTally = new int[0];
	private int[] bucketCursor = new int[0];

	/** The pose currently in the attribute slots, for change detection. */
	private final float[] lastPose = new float[16];
	private boolean hasLastPose;
	private boolean lastColorValid;
	private int lastColor;
	private boolean lastLightValid;
	private int lastLight;
	private boolean lastVaoValid;
	private int lastVao;

	// -----------------------------------------------------------------------
	// Statistics, mirroring the native draw list's counters
	// -----------------------------------------------------------------------

	private long flushes;
	private long arraysDrawn;
	private long drawCalls;
	private long callsIssued;
	private long callsSkipped;

	/**
	 * Registers a material and returns its bucket id.
	 *
	 * <p>Called once per distinct material per model registration, not per frame. The
	 * material's own attribute state - its {@code Kd} colour and the full-bright lightmap the
	 * BRIGHT shader types carry - is read here, so the per-frame path never dereferences the
	 * material's fields again.</p>
	 */
	public int registerBucket(MaterialProperties material) {
		final int id = bucketCount;
		bucketCount++;
		bucketMaterial = growMaterials(bucketMaterial, bucketCount);
		bucketPass = growInts(bucketPass, bucketCount);
		bucketColor = growInts(bucketColor, bucketCount);
		bucketHasColor = growBooleans(bucketHasColor, bucketCount);
		bucketLight = growInts(bucketLight, bucketCount);
		bucketHasLight = growBooleans(bucketHasLight, bucketCount);
		bucketTally = growInts(bucketTally, bucketCount);
		bucketCursor = growInts(bucketCursor, bucketCount);

		bucketMaterial[id] = material;
		// The reference's drawAll iterates the opaque map, then the cutout map, then the
		// translucent map, so the pass index is the material's own split.
		bucketPass[id] = material.translucent ? PASS_TRANSLUCENT : material.cutoutHack ? PASS_CUTOUT : PASS_OPAQUE;
		final Integer color = material.vertexAttributeState.color;
		if (color != null) {
			bucketColor[id] = color;
			bucketHasColor[id] = true;
		} else {
			bucketHasColor[id] = false;
		}
		final Integer lightmapUV = material.vertexAttributeState.lightmapUV;
		if (lightmapUV != null) {
			bucketLight[id] = lightmapUV;
			bucketHasLight[id] = true;
		} else {
			bucketHasLight[id] = false;
		}

		// Insert at the end of this pass's segment, which keeps bucketOrder sorted by
		// (pass, first registration) without a sort.
		int insertAt = bucketOrderCount;
		for (int i = 0; i < bucketOrderCount; i++) {
			if (bucketPass[bucketOrder[i]] > bucketPass[id]) {
				insertAt = i;
				break;
			}
		}
		bucketOrder = growInts(bucketOrder, bucketOrderCount + 1);
		System.arraycopy(bucketOrder, insertAt, bucketOrder, insertAt + 1, bucketOrderCount - insertAt);
		bucketOrder[insertAt] = id;
		bucketOrderCount++;
		return id;
	}

	/** Number of registered buckets, for diagnostics. */
	int bucketCount() {
		return bucketCount;
	}

	/**
	 * Queues one draw of a registered bucket.
	 *
	 * <p>The pose is copied into the shared store here, so the caller may reuse its scratch
	 * array immediately. The copy is the one per-item cost a frame pays.</p>
	 */
	public void queue(int bucket, int vao, int indexCount, int light, int color, float[] pose, int poseOffset) {
		if (bucket < 0 || bucket >= bucketCount || indexCount <= 0) {
			return;
		}
		if (itemCount + 1 > (itemPose.length >> 4)) {
			final int required = itemCount + 1;
			itemBucket = growInts(itemBucket, required);
			itemVao = growInts(itemVao, required);
			itemIndexCount = growInts(itemIndexCount, required);
			itemLight = growInts(itemLight, required);
			itemColor = growInts(itemColor, required);
			itemPose = growPoses(itemPose, required);
		}
		final int index = itemCount;
		itemBucket[index] = bucket;
		itemVao[index] = vao;
		itemIndexCount[index] = indexCount;
		itemLight[index] = light;
		itemColor[index] = color;
		System.arraycopy(pose, poseOffset, itemPose, index << 4, 16);
		itemCount++;
	}

	/** Items queued and not yet rendered. */
	public int queuedCount() {
		return itemCount;
	}

	/**
	 * Draws everything queued, and empties the queue.
	 *
	 * @param renderTranslucent false drops translucent materials entirely, which is what the
	 *                          reference's {@code drawAll} does when it is not asked for them
	 * @param binder            sets up and tears down each material's shader state
	 * @param sink              the GL entry points to emit through
	 */
	public void render(boolean renderTranslucent, MaterialBinder binder, GlSink sink) {
		if (itemCount == 0) {
			return;
		}
		flushes++;

		// Tally per bucket, then lay the runs out over the bucket order so materials draw in
		// pass order and translucent materials draw last.
		Arrays.fill(bucketTally, 0, bucketCount, 0);
		for (int i = 0; i < itemCount; i++) {
			bucketTally[itemBucket[i]]++;
		}

		runBucket = growInts(runBucket, bucketOrderCount);
		runStart = growInts(runStart, bucketOrderCount);
		runCount = growInts(runCount, bucketOrderCount);
		int runs = 0;
		int total = 0;
		for (int i = 0; i < bucketOrderCount; i++) {
			final int bucket = bucketOrder[i];
			final int count = bucketTally[bucket];
			if (count == 0) {
				continue;
			}
			if (bucketPass[bucket] == PASS_TRANSLUCENT && !renderTranslucent) {
				// Zeroing the tally here is also what tells the scatter to drop these items.
				bucketTally[bucket] = 0;
				continue;
			}
			runBucket[runs] = bucket;
			runStart[runs] = total;
			runCount[runs] = count;
			runs++;
			total += count;
		}

		if (total == 0) {
			itemCount = 0;
			return;
		}

		// Stable counting sort: scatter the items into the runs, preserving queue order
		// within each bucket, which is the reference's per-material list order.
		sortedVao = growInts(sortedVao, total);
		sortedIndexCount = growInts(sortedIndexCount, total);
		sortedLight = growInts(sortedLight, total);
		sortedColor = growInts(sortedColor, total);
		sortedPose = growPoses(sortedPose, total);
		for (int r = 0; r < runs; r++) {
			bucketCursor[runBucket[r]] = runStart[r];
		}
		for (int i = 0; i < itemCount; i++) {
			final int bucket = itemBucket[i];
			if (bucketTally[bucket] == 0) {
				continue;
			}
			final int destination = bucketCursor[bucket];
			bucketCursor[bucket] = destination + 1;
			sortedVao[destination] = itemVao[i];
			sortedIndexCount[destination] = itemIndexCount[i];
			sortedLight[destination] = itemLight[i];
			sortedColor[destination] = itemColor[i];
			System.arraycopy(itemPose, i << 4, sortedPose, destination << 4, 16);
		}
		itemCount = 0;

		final boolean customShader = sink.useCustomShader();
		final boolean floatFallback = sink.useFloatAttributeFallback();
		for (int r = 0; r < runs; r++) {
			final int bucket = runBucket[r];
			final int start = runStart[r];
			final int end = start + runCount[r];
			binder.setup(bucketMaterial[bucket]);
			try {
				drawRun(bucket, start, end, customShader, floatFallback, sink);
			} finally {
				binder.cleanup();
			}
		}
	}

	/**
	 * Draws one material's run.
	 *
	 * <p>The change-detection caches reset per run rather than per frame: the reference
	 * re-applies its state unconditionally and so never depends on a value surviving a
	 * shader bind, and resetting here costs at most a handful of writes per run.</p>
	 *
	 * <p>Every attribute write happens AFTER the vertex array is bound, which is the order
	 * {@code RenderCall.draw} uses: bind, apply the queued state, apply the material state,
	 * draw. That order matters because current generic vertex attribute values are ambiguous
	 * driver territory: Mesa keeps them as context state, but a driver is free to treat them
	 * as vertex array object state, where writing them before a bind lands in the wrong VAO
	 * and the newly bound VAO restores its own stale values instead. Writing only while the
	 * target VAO is bound is correct under both readings, and costs nothing measurable: the
	 * overlay and the material lightmap re-establish once per array switch, and the per-item
	 * colour, light and pose caches are simply invalidated by the switch.</p>
	 */
	private void drawRun(int bucket, int start, int end, boolean customShader, boolean floatFallback, GlSink sink) {
		hasLastPose = false;
		lastColorValid = false;
		lastLightValid = false;
		lastVaoValid = false;

		final boolean hasMaterialColor = bucketHasColor[bucket];
		final int materialColor = bucketColor[bucket];
		final boolean hasMaterialLight = bucketHasLight[bucket];
		final int materialLight = bucketLight[bucket];

		for (int i = start; i < end; i++) {
			// Every car of one model shares the same vertex arrays, so a run frequently holds
			// long stretches of one array; re-binding the array already in place is a no-op.
			if (!lastVaoValid || lastVao != sortedVao[i]) {
				sink.bindVertexArray(sortedVao[i]);
				lastVao = sortedVao[i];
				lastVaoValid = true;
				callsIssued++;

				// The bind may have restored this array's own current attribute values, so
				// everything the pass writes has to be re-established after it - the same
				// re-application RenderCall.draw performs per array.
				writeOverlay(floatFallback, sink);
				if (hasMaterialLight) {
					writeMaterialLightmap(materialLight, floatFallback, sink);
					lastLight = materialLight;
					lastLightValid = true;
				} else {
					lastLightValid = false;
				}
				lastColorValid = false;
				hasLastPose = false;
			} else {
				callsSkipped++;
			}

			final int effectiveColor = hasMaterialColor ? materialColor : sortedColor[i];
			if (!lastColorValid || lastColor != effectiveColor) {
				writeColor(effectiveColor, sink);
				lastColor = effectiveColor;
				lastColorValid = true;
			} else {
				callsSkipped++;
			}

			if (!hasMaterialLight) {
				// The queued light is per car, sampled at the car's position, so it is
				// applied per item with change detection.
				if (!lastLightValid || lastLight != sortedLight[i]) {
					writeQueuedLightmap(sortedLight[i], floatFallback, sink);
					lastLight = sortedLight[i];
					lastLightValid = true;
				} else {
					callsSkipped++;
				}
			}

			if (customShader) {
				if (!poseEquals(i)) {
					writePose(i, sink);
				} else {
					callsSkipped += 4;
				}
			} else {
				// The shader-pack fallback sends the pose through the current shader's model
				// view matrix, exactly as the reference's VertexAttributeState.apply does.
				sink.setModelViewMatrix(sortedPose, i << 4);
				callsIssued++;
			}

			sink.drawElements(sortedIndexCount[i]);
			drawCalls++;
			arraysDrawn++;
		}
	}

	// -----------------------------------------------------------------------
	// Emission, one GL call each
	// -----------------------------------------------------------------------

	/** Colour attribute: four bytes, most significant first, each divided by 255. */
	private void writeColor(int color, GlSink sink) {
		sink.attrib4f(ATTR_COLOR,
				((color >>> 24) & 0xFF) / 255F,
				((color >>> 16) & 0xFF) / 255F,
				((color >>> 8) & 0xFF) / 255F,
				(color & 0xFF) / 255F);
		callsIssued++;
	}

	/**
	 * The queued lightmap.
	 *
	 * <p>The reference stores the queued light through
	 * {@code Utilities.exchangeLightmapUVBits} and then writes the exchanged value's high
	 * half first, which composes to the original packed value's low half first. The halves
	 * are Minecraft's own: block light in the low half, sky light in the high half, each
	 * already scaled by sixteen.</p>
	 */
	private void writeQueuedLightmap(int light, boolean floatFallback, GlSink sink) {
		final int x = (short) light;
		final int y = (short) (light >>> 16);
		if (floatFallback) {
			sink.attrib2f(ATTR_UV_LIGHTMAP, x, y);
		} else {
			sink.attribI2i(ATTR_UV_LIGHTMAP, x, y);
		}
		callsIssued++;
	}

	/**
	 * The material's own lightmap.
	 *
	 * <p>The reference's two-argument {@code VertexAttributeState} constructor does not
	 * exchange the halves, and {@code apply} writes the high half first, so the material
	 * value's halves arrive in the opposite order to the queued one. Both halves of the
	 * BRIGHT full-bright constant are 240, so the order is not observable there, but the
	 * reference's order is reproduced regardless.</p>
	 */
	private void writeMaterialLightmap(int light, boolean floatFallback, GlSink sink) {
		final int x = (short) (light >>> 16);
		final int y = (short) light;
		if (floatFallback) {
			sink.attrib2f(ATTR_UV_LIGHTMAP, x, y);
		} else {
			sink.attribI2i(ATTR_UV_LIGHTMAP, x, y);
		}
		callsIssued++;
	}

	/** The overlay attribute, whose default is symmetric under the exchange. */
	private void writeOverlay(boolean floatFallback, GlSink sink) {
		final int x = (short) (OVERLAY_DEFAULT >>> 16);
		final int y = (short) OVERLAY_DEFAULT;
		if (floatFallback) {
			sink.attrib2f(ATTR_UV_OVERLAY, x, y);
		} else {
			sink.attribI2i(ATTR_UV_OVERLAY, x, y);
		}
		callsIssued++;
	}

	/**
	 * The pose, as four column vec4 attributes.
	 *
	 * <p>The array is the column-major order {@code Matrix4f.get(float[])} produces, which
	 * is the order {@code Utilities.store} writes and therefore the order the four attributes
	 * expect: the first attribute carries the first column.</p>
	 */
	private void writePose(int index, GlSink sink) {
		final int base = index << 4;
		for (int row = 0; row < 4; row++) {
			sink.attrib4f(ATTR_MATRIX_MODEL + row,
					sortedPose[base + row * 4],
					sortedPose[base + row * 4 + 1],
					sortedPose[base + row * 4 + 2],
					sortedPose[base + row * 4 + 3]);
		}
		System.arraycopy(sortedPose, base, lastPose, 0, 16);
		hasLastPose = true;
		callsIssued += 4;
	}

	private boolean poseEquals(int index) {
		if (!hasLastPose) {
			return false;
		}
		final int base = index << 4;
		for (int i = 0; i < 16; i++) {
			if (Float.floatToRawIntBits(sortedPose[base + i]) != Float.floatToRawIntBits(lastPose[i])) {
				return false;
			}
		}
		return true;
	}

	// -----------------------------------------------------------------------
	// Lifetime and reporting
	// -----------------------------------------------------------------------

	/** Drops every registration. Called when models are rebuilt, so their materials change. */
	public void reset() {
		bucketCount = 0;
		bucketOrderCount = 0;
		itemCount = 0;
		hasLastPose = false;
		lastColorValid = false;
		lastLightValid = false;
		lastVaoValid = false;
	}

	/** A one-line summary of the pass's work, in the native draw list's format. */
	public String describeStatistics() {
		final long total = callsIssued + callsSkipped;
		final double skippedPercent = total == 0 ? 0.0 : callsSkipped * 100.0 / total;
		return String.format(
				"flat draw pass: %d flush(es), %d array(s) drawn, %d draw call(s), "
						+ "%d vertex state call(s) issued and %d skipped (%.1f%%), %d bucket(s) live",
				flushes, arraysDrawn, drawCalls, callsIssued, callsSkipped, skippedPercent, bucketCount);
	}

	/** The raw counters, in the order documented here. */
	long[] rawStatistics() {
		return new long[]{flushes, arraysDrawn, callsSkipped, callsIssued, drawCalls, bucketCount, itemCount};
	}

	/** Zeroes the counters, so a measurement can be scoped to a window. */
	void resetStatistics() {
		flushes = 0;
		arraysDrawn = 0;
		drawCalls = 0;
		callsIssued = 0;
		callsSkipped = 0;
	}
}
