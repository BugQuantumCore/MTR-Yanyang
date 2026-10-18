package org.mtr.mod.resource.nativeobj;

import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link FlatDrawPass} against a recording sink, so the draw path's decisions can be
 * verified without a GL context.
 *
 * <h2>What is under test</h2>
 *
 * <p>The pass's whole value is its equivalence to the reference draw path while issuing fewer
 * GL calls, and the decisions that make that true - which value wins when the material's own
 * state and the queued state disagree, when a value is re-sent and when it is suppressed, in
 * what order the materials draw and which are dropped - are all visible in the stream of calls
 * the sink receives. A recorder makes that stream assertable directly.</p>
 *
 * <p>The recorded calls are compared against what the reference's {@code RenderCall.draw}
 * sequence - queued state, then the material's state, then the draw - leaves in force at each
 * {@code glDrawElements}, which is the equivalence the pass claims.</p>
 */
final class FlatDrawPassTest {

	/** One recorded GL call, with its arguments in the order the sink received them. */
	private static final class Call {

		final String name;
		final Object[] args;

		Call(String name, Object... args) {
			this.name = name;
			this.args = args;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof Call)) {
				return false;
			}
			final Call other = (Call) object;
			if (!name.equals(other.name) || args.length != other.args.length) {
				return false;
			}
			for (int i = 0; i < args.length; i++) {
				final Object left = args[i];
				final Object right = other.args[i];
				final boolean equal;
				if (left instanceof float[] && right instanceof float[]) {
					equal = Arrays.equals((float[]) left, (float[]) right);
				} else {
					equal = Objects.equals(left, right);
				}
				if (!equal) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			return name.hashCode() * 31 + args.length;
		}

		@Override
		public String toString() {
			final StringBuilder builder = new StringBuilder(name).append('(');
			for (final Object argument : args) {
				builder.append(argument).append(", ");
			}
			return builder.append(')').toString();
		}
	}

	/** A sink that records every call, with the two policy answers the test controls. */
	private static final class RecordingSink implements FlatDrawPass.GlSink {

		final List<Call> calls = new ArrayList<>();
		boolean customShader = true;
		boolean floatAttributeFallback;

		@Override
		public void attrib4f(int location, float x, float y, float z, float w) {
			calls.add(new Call("attrib4f", location, x, y, z, w));
		}

		@Override
		public void attribI2i(int location, int x, int y) {
			calls.add(new Call("attribI2i", location, x, y));
		}

		@Override
		public void attrib2f(int location, float x, float y) {
			calls.add(new Call("attrib2f", location, x, y));
		}

		@Override
		public void bindVertexArray(int vao) {
			calls.add(new Call("bindVertexArray", vao));
		}

		@Override
		public void drawElements(int count) {
			calls.add(new Call("drawElements", count));
		}

		@Override
		public void setModelViewMatrix(float[] matrix, int offset) {
			final float[] copied = new float[16];
			System.arraycopy(matrix, offset, copied, 0, 16);
			calls.add(new Call("modelView", (Object) copied));
		}

		@Override
		public boolean useCustomShader() {
			return customShader;
		}

		@Override
		public boolean useFloatAttributeFallback() {
			return floatAttributeFallback;
		}

		List<Call> named(String name) {
			final List<Call> result = new ArrayList<>();
			for (final Call call : calls) {
				if (call.name.equals(name)) {
					result.add(call);
				}
			}
			return result;
		}
	}

	/** Records the materials whose shader state was set up, in run order. */
	private static final class RecordingBinder implements FlatDrawPass.MaterialBinder {

		final List<MaterialProperties> setups = new ArrayList<>();

		@Override
		public void setup(MaterialProperties material) {
			setups.add(material);
		}

		@Override
		public void cleanup() {
		}
	}

	private static final float[] IDENTITY = {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1,
	};

	private static final float[] TRANSLATED = {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			4, 5, 6, 1,
	};

	private static MaterialProperties cutout() {
		return new MaterialProperties(OptimizedModel.ShaderType.CUTOUT, new Identifier("test", "cutout.png"), null);
	}

	private static MaterialProperties cutoutBright(int color) {
		return new MaterialProperties(OptimizedModel.ShaderType.CUTOUT_BRIGHT, new Identifier("test", "bright.png"), color);
	}

	private static MaterialProperties translucent() {
		return new MaterialProperties(OptimizedModel.ShaderType.TRANSLUCENT, new Identifier("test", "translucent.png"), null);
	}

	private static MaterialProperties cutoutGlowing() {
		return new MaterialProperties(OptimizedModel.ShaderType.CUTOUT_GLOWING, new Identifier("test", "glowing.png"), null);
	}

	/** Queues one item with the identity pose. */
	private static void queue(FlatDrawPass pass, int bucket, int vao, int count, int light) {
		pass.queue(bucket, vao, count, light, 0xFFFFFFFF, IDENTITY, 0);
	}

	@Test
	void materialStateWinsOverQueuedStateForBrightMaterials() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutoutBright(0x3366CCFF));
		// Two cars at two very different sampled lights: the material's full-bright lightmap
		// must replace both, which is what applying the material state after the queued state
		// achieves on the reference path.
		queue(pass, bucket, 10, 6, 0x00F000A0);
		queue(pass, bucket, 10, 6, 0x00000000);

		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);

		assertEquals(List.of(
				new Call("attribI2i", 3, 10, 10),
				// The material's lightmap is written high half first, which is the order the
				// reference's two-argument VertexAttributeState uses. Both halves of the
				// full-bright constant are 240.
				new Call("attribI2i", 4, 240, 240),
				// The Kd colour, not the queued ARGB white.
				new Call("attrib4f", 1, 0x33 / 255F, 0x66 / 255F, 0xCC / 255F, 1F),
				new Call("attrib4f", 6, 1F, 0F, 0F, 0F),
				new Call("attrib4f", 7, 0F, 1F, 0F, 0F),
				new Call("attrib4f", 8, 0F, 0F, 1F, 0F),
				new Call("attrib4f", 9, 0F, 0F, 0F, 1F),
				new Call("bindVertexArray", 10),
				new Call("drawElements", 6),
				// The second car: same colour, same light, same pose, same array - everything
				// except the draw itself is suppressed.
				new Call("drawElements", 6)), sink.calls);
	}

	@Test
	void queuedLightFollowsEachItemForPlainMaterials() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		// Three cars: two share one light, the third has another. The queued light is per car
		// on the reference path, so the pass must not collapse it to the first item's value.
		queue(pass, bucket, 20, 3, 0x00F000A0);
		queue(pass, bucket, 20, 3, 0x00F000A0);
		queue(pass, bucket, 20, 3, 0x00A000F0);

		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);

		// One queued lightmap write per distinct value: the queued value's low half is written
		// first, which is what the reference's exchange-then-high-half-first composes to.
		assertEquals(List.of(
				new Call("attribI2i", 4, 0x00A0, 0x00F0),
				new Call("attribI2i", 4, 0x00F0, 0x00A0)), sink.named("attribI2i")
				.stream().filter(call -> call.args[0].equals(4)).toList());

		// The queued colour, once, because it does not change.
		assertEquals(List.of(new Call("attrib4f", 1, 1F, 1F, 1F, 1F)), sink.named("attrib4f")
				.stream().filter(call -> call.args[0].equals(1)).toList());
	}

	@Test
	void materialsDrawInPassOrderAndTranslucentIsDroppedWhenDisabled() {
		final FlatDrawPass pass = new FlatDrawPass();
		final MaterialProperties opaque = cutout();
		final MaterialProperties translucent = translucent();
		final MaterialProperties glowing = cutoutGlowing();
		final int translucentBucket = pass.registerBucket(translucent);
		final int glowingBucket = pass.registerBucket(glowing);
		final int opaqueBucket = pass.registerBucket(opaque);

		// Queue in an interleaved order that is not the draw order.
		queue(pass, translucentBucket, 30, 3, 0);
		queue(pass, opaqueBucket, 31, 4, 0);
		queue(pass, glowingBucket, 32, 5, 0);

		final RecordingBinder withTranslucent = new RecordingBinder();
		pass.render(true, withTranslucent, new RecordingSink());
		// Opaque, then the cutout hack, then translucent last - the reference's drawAll order -
		// regardless of the queue order.
		assertEquals(List.of(opaque, glowing, translucent), withTranslucent.setups);

		// With translucency off, the translucent items are dropped entirely: no shader setup,
		// no draw, and the queue is still consumed.
		final RecordingBinder withoutTranslucent = new RecordingBinder();
		final RecordingSink sink = new RecordingSink();
		queue(pass, translucentBucket, 30, 3, 0);
		queue(pass, opaqueBucket, 31, 4, 0);
		pass.render(false, withoutTranslucent, sink);
		assertEquals(List.of(opaque), withoutTranslucent.setups);
		assertEquals(1, sink.named("drawElements").size());
		assertEquals(0, pass.queuedCount());
	}

	@Test
	void poseChangesAreWrittenAndRepeatsAreSuppressed() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		pass.queue(bucket, 40, 7, 0x00F000F0, 0xFFFFFFFF, IDENTITY, 0);
		pass.queue(bucket, 41, 8, 0x00F000F0, 0xFFFFFFFF, IDENTITY, 0);
		pass.queue(bucket, 40, 7, 0x00F000F0, 0xFFFFFFFF, TRANSLATED, 0);

		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);

		// The identity pose is written as four column vec4s, the repeat costs nothing, and the
		// translated pose is written again - with its fourth column carrying the translation.
		assertEquals(List.of(
				new Call("attrib4f", 6, 1F, 0F, 0F, 0F),
				new Call("attrib4f", 7, 0F, 1F, 0F, 0F),
				new Call("attrib4f", 8, 0F, 0F, 1F, 0F),
				new Call("attrib4f", 9, 0F, 0F, 0F, 1F),
				new Call("attrib4f", 6, 1F, 0F, 0F, 0F),
				new Call("attrib4f", 7, 0F, 1F, 0F, 0F),
				new Call("attrib4f", 8, 0F, 0F, 1F, 0F),
				new Call("attrib4f", 9, 4F, 5F, 6F, 1F)), sink.named("attrib4f")
				.stream().filter(call -> (Integer) call.args[0] >= 6).toList());

		// Two of the three draws bind; the third re-binds the first array.
		assertEquals(List.of(
				new Call("bindVertexArray", 40),
				new Call("bindVertexArray", 41),
				new Call("bindVertexArray", 40)), sink.named("bindVertexArray"));
	}

	@Test
	void sharedVertexArraysBindOncePerRun() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		// Three cars of the same model: the same vertex array three times over.
		for (int i = 0; i < 3; i++) {
			pass.queue(bucket, 50, 9, 0x00F000F0, 0xFFFFFFFF, TRANSLATED, 0);
		}

		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);

		assertEquals(List.of(new Call("bindVertexArray", 50)), sink.named("bindVertexArray"));
		assertEquals(3, sink.named("drawElements").size());
	}

	@Test
	void shaderPackFallbackSendsPosesThroughTheModelViewMatrix() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		pass.queue(bucket, 60, 3, 0x00F000F0, 0xFFFFFFFF, IDENTITY, 0);
		pass.queue(bucket, 60, 3, 0x00F000F0, 0xFFFFFFFF, IDENTITY, 0);

		final RecordingSink sink = new RecordingSink();
		sink.customShader = false;
		pass.render(true, new RecordingBinder(), sink);

		// No matrix attribute rows at all; one model view uniform per item, repeats included,
		// because the reference re-sets the uniform for every array.
		assertTrue(sink.named("attrib4f").stream().filter(call -> (Integer) call.args[0] >= 6).findAny().isEmpty());
		final List<Call> uniforms = sink.named("modelView");
		assertEquals(2, uniforms.size());
		assertArrayEquals(IDENTITY, (float[]) uniforms.get(0).args[0], 0F);
		assertArrayEquals(IDENTITY, (float[]) uniforms.get(1).args[0], 0F);
	}

	@Test
	void gl4esFallbackWritesIntegerAttributesAsFloats() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		queue(pass, bucket, 70, 3, 0x00F000A0);

		final RecordingSink sink = new RecordingSink();
		sink.floatAttributeFallback = true;
		pass.render(true, new RecordingBinder(), sink);

		// The lightmap and the overlay go through the two-float entry point, with the same
		// half values the integer path would have written.
		assertEquals(List.of(
				new Call("attrib2f", 3, 10F, 10F),
				new Call("attrib2f", 4, (float) 0x00A0, (float) 0x00F0)), sink.named("attrib2f"));
	}

	@Test
	void queueOrderIsPreservedWithinAMaterial() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int first = pass.registerBucket(cutout());
		final int second = pass.registerBucket(cutoutBright(0xFFFFFFFF));
		// Interleave two materials, then check each run keeps its items in queue order.
		queue(pass, first, 80, 3, 0x00F000A0);
		queue(pass, second, 81, 4, 0);
		queue(pass, first, 80, 3, 0x00A000F0);
		queue(pass, second, 81, 4, 0);

		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);

		final List<Integer> drawOrder = new ArrayList<>();
		for (final Call call : sink.named("drawElements")) {
			drawOrder.add((Integer) call.args[0]);
		}
		// The first material's cars keep their queue order, then the second's.
		assertEquals(List.of(3, 3, 4, 4), drawOrder);
	}

	@Test
	void rendersAreRepeatableAndStatisticsAccumulate() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		queue(pass, bucket, 90, 3, 0x00F000F0);
		pass.render(true, new RecordingBinder(), new RecordingSink());
		assertEquals(0, pass.queuedCount());
		// A second frame with the same bucket works, and the counters cover both.
		queue(pass, bucket, 90, 3, 0x00F000F0);
		pass.render(true, new RecordingBinder(), new RecordingSink());
		assertEquals(2, pass.rawStatistics()[0]);
		assertEquals(2, pass.rawStatistics()[4]);

		pass.resetStatistics();
		assertEquals(0, pass.rawStatistics()[0]);
	}

	@Test
	void resetDropsRegistrationsSoStaleItemsAreIgnored() {
		final FlatDrawPass pass = new FlatDrawPass();
		pass.registerBucket(cutout());
		pass.reset();
		assertEquals(0, pass.bucketCount());
		// Items queued against a stale bucket id are dropped rather than drawn.
		queue(pass, 0, 100, 3, 0);
		assertEquals(0, pass.queuedCount());
		pass.render(true, new RecordingBinder(), new RecordingSink());
	}

	@Test
	void emptyQueueDoesNotFlush() {
		final FlatDrawPass pass = new FlatDrawPass();
		pass.registerBucket(cutout());
		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);
		assertTrue(sink.calls.isEmpty());
		assertEquals(0, pass.rawStatistics()[0]);
	}

	@Test
	void arrayGrowthKeepsEveryItem() {
		final FlatDrawPass pass = new FlatDrawPass();
		final int bucket = pass.registerBucket(cutout());
		final int items = 5000;
		for (int i = 0; i < items; i++) {
			pass.queue(bucket, 110 + i, 3, 0x00F000F0, 0xFFFFFFFF, TRANSLATED, 0);
		}
		final RecordingSink sink = new RecordingSink();
		pass.render(true, new RecordingBinder(), sink);
		assertEquals(items, sink.named("drawElements").size());
	}
}
