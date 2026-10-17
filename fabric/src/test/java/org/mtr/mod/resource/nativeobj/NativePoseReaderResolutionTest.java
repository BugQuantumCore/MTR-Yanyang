package org.mtr.mod.resource.nativeobj;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.mapper.GraphicsHolder;

import java.lang.invoke.MethodHandle;

/**
 * Pins the pose-matrix chain resolution to being name-independent.
 *
 * <h2>What these tests prevent</h2>
 *
 * <p>The pose reader once resolved its Minecraft classes by their yarn names through
 * {@code Class.forName}. Those names exist only in the loom development runtime: a production
 * client runs the intermediary names, so every production load threw
 * {@code ClassNotFoundException}, the pose reader resolved to null, and every model was
 * silently dropped at queue time - while every test stayed green, because the tests run in
 * exactly the environment where those names do exist. That is the whole reason this fixture
 * uses synthetic classes with meaningless names rather than the real ones: it reproduces the
 * production condition inside a development-environment test.</p>
 *
 * <p>The resolution is now structural. The stack class comes from the field's declared type,
 * and {@code peek} and {@code getPositionMatrix} are found by shape. These tests prove that
 * property directly: the names here are all intermediary-style gibberish, and the chain still
 * has to resolve and produce the right matrix.</p>
 */
public final class NativePoseReaderResolutionTest {

	/**
	 * A stack shape with intermediary-style names: exactly what production provides.
	 *
	 * <p>{@code method_23317} is the no-argument peek, {@code method_23318} is the
	 * depth-taking overload, and the nested class is the entry holding a JOML
	 * {@code Matrix4f} under an equally meaningless name. None of the names is used by the
	 * resolver; only the shapes are.</p>
	 */
	public static final class class_7777 {

		public class_7776 method_23317() {
			return new class_7776();
		}

		public class_7776 method_23318(int depth) {
			return new class_7776();
		}

		public void method_23319() {
		}

		public boolean method_23320() {
			return false;
		}

		public static final class class_7776 {

			public org.joml.Matrix4f method_22984() {
				return new org.joml.Matrix4f(
						0, 1, 2, 3,
						4, 5, 6, 7,
						8, 9, 10, 11,
						12, 13, 14, 15);
			}

			public org.joml.Matrix3f method_22985() {
				return new org.joml.Matrix3f();
			}
		}
	}

	/** An entry shape with two indistinguishable candidates: the resolver must refuse to guess. */
	public static final class AmbiguousStack {

		public AmbiguousEntry method_31337() {
			return new AmbiguousEntry();
		}

		public static final class AmbiguousEntry {

			public org.joml.Matrix4f first() {
				return new org.joml.Matrix4f();
			}

			public org.joml.Matrix4f second() {
				return new org.joml.Matrix4f();
			}
		}
	}

	/** An entry with no Matrix4f getter at all. */
	public static final class NormalOnlyStack {

		public NormalOnlyEntry method_31338() {
			return new NormalOnlyEntry();
		}

		public static final class NormalOnlyEntry {

			public org.joml.Matrix3f method_22985() {
				return new org.joml.Matrix3f();
			}
		}
	}

	/** A stack whose entry has a position matrix but which exposes no peek-shaped method. */
	public static final class NoPeekStack {

		public void method_23319() {
		}

		public static final class Entry {

			public org.joml.Matrix4f method_22984() {
				return new org.joml.Matrix4f();
			}
		}
	}

	@Test
	public void resolvesUnderMeaninglessNames() throws Throwable {
		final MethodHandle chain = NativeModelAccess.resolveMatrixChain(class_7777.class);
		Assertions.assertNotNull(chain, "the chain must resolve when every name is meaningless");

		final float[] out = new float[16];
		chain.invoke(new class_7777(), out);
		// JOML's Matrix4f.get(float[]) writes the elements in the constructor's own order
		// (arr[0]=m00, arr[1]=m01, arr[4]=m10, arr[15]=m33), and the constructor above passes
		// 0..15 in exactly that order, so every element of out must equal its index. Asserting
			// all sixteen pins the convention unambiguously, which one or two spot checks would not.
		for (int i = 0; i < out.length; i++) {
			Assertions.assertEquals((float) i, out[i], 1.0E-6f, "arr[" + i + "]");
		}
	}

	@Test
	public void refusesToGuessBetweenTwoCandidates() {
		Assertions.assertNull(NativeModelAccess.resolveMatrixChain(AmbiguousStack.class),
				"two no-argument Matrix4f getters must disable the chain rather than pick one");
	}

	@Test
	public void refusesUnrecognisedEntryShapes() {
		Assertions.assertNull(NativeModelAccess.resolveMatrixChain(NormalOnlyStack.class),
				"an entry with only a Matrix3f getter must not resolve");
	}

	@Test
	public void refusesUnrecognisedStackShapes() {
		Assertions.assertNull(NativeModelAccess.resolveMatrixChain(NoPeekStack.class),
				"a stack with no peek-shaped method must not resolve");
	}

	/**
	 * The real chain must resolve in this runtime, and the mapping jar must keep the field.
	 *
	 * <p>This runs against the yarn names here and against the intermediary names in a
	 * production client, which is the point: the same code path has to work through both. The
	 * field lookup also fails loudly if a future mapping jar renames {@code matrixStack},
	 * rather than degrading to the silent no-render state this class of bug produces.</p>
	 */
	@Test
	public void theRealStackResolvesInThisRuntime() throws ReflectiveOperationException {
		final Class<?> stackClass = GraphicsHolder.class.getDeclaredField("matrixStack").getType();
		Assertions.assertNotNull(NativeModelAccess.resolveMatrixChain(stackClass),
				"the real MatrixStack shape must resolve under this runtime's names");
	}
}
