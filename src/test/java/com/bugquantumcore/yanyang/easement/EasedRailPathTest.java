package com.bugquantumcore.yanyang.easement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EasedRailPath} 的纯数学验证（不依赖 Minecraft / MTR 类）。
 *
 * <p>覆盖：端点锚定、端点方向角、弧长下界、端点零曲率、两种线型的差异、
 * 直线特例与不可表示几何的回退。</p>
 */
class EasedRailPathTest {

	private static final double NODE_EPSILON = 1E-6;
	private static final double HEADING_EPSILON = 1E-6;

	/** 典型弯道：东行 90° 转南，弦长 10。 */
	@Test
	void testCurveEndpointsAnchoredToNodes() {
		for (final boolean halfWaveSine : new boolean[]{false, true}) {
			final EasedRailPath path = EasedRailPath.build(0, 0, 0, 10, 10, Math.PI / 2, halfWaveSine);
			assertNotNull(path, "solver should converge for a gentle 90 degree bend");

			assertEquals(0.5, path.x[0], NODE_EPSILON);
			assertEquals(0.5, path.z[0], NODE_EPSILON);
			assertEquals(10.5, path.x[path.x.length - 1], NODE_EPSILON);
			assertEquals(10.5, path.z[path.z.length - 1], NODE_EPSILON);

			assertEquals(0, path.heading[0], HEADING_EPSILON);
			assertEquals(Math.PI / 2, path.heading[path.heading.length - 1], HEADING_EPSILON);
		}
	}

	/** 弧长不小于弦长；表格步长与总长自洽。 */
	@Test
	void testLengthBounds() {
		final EasedRailPath path = EasedRailPath.build(0, 0, 0, 10, 10, Math.PI / 2, false);
		assertNotNull(path);
		final double chord = Math.hypot(10, 10);
		assertTrue(path.length >= chord - NODE_EPSILON, "arc length must be at least the chord");
		assertEquals(path.length, path.step * (path.x.length - 1), 1E-9);
	}

	/** 端点曲率为零：方向角表在两端的局部变化率应接近零。 */
	@Test
	void testZeroCurvatureAtEnds() {
		final EasedRailPath path = EasedRailPath.build(0, 0, 0, 20, 10, Math.PI / 4, true);
		assertNotNull(path);
		final int n = path.x.length - 1;
		// 首段与末段的单位弧长方向角变化（曲率）应远小于中段。
		final double startCurvature = Math.abs(path.heading[1] - path.heading[0]) / path.step;
		final double endCurvature = Math.abs(path.heading[n] - path.heading[n - 1]) / path.step;
		final double midCurvature = Math.abs(path.heading[n / 2 + 1] - path.heading[n / 2 - 1]) / (2 * path.step);
		assertTrue(startCurvature < midCurvature / 4, "curvature must start near zero");
		assertTrue(endCurvature < midCurvature / 4, "curvature must end near zero");
		assertTrue(midCurvature > 1E-6, "mid-path curvature should be non-zero for a real bend");
	}

	/** S 形弯（反向角）应能求解，且中段横向偏移非零。 */
	@Test
	void testSBend() {
		// 起点朝东、终点（存储角）朝西的行进方向为东 —— S 弯由节点位置差决定。
		final EasedRailPath cubic = EasedRailPath.build(0, 0, 0, 12, 6, 0, false);
		assertNotNull(cubic, "S-bend should be solvable");
		assertEquals(0.5, cubic.x[0], NODE_EPSILON);
		assertEquals(0.5, cubic.z[0], NODE_EPSILON);
		assertEquals(12.5, cubic.x[cubic.x.length - 1], NODE_EPSILON);
		assertEquals(6.5, cubic.z[cubic.z.length - 1], NODE_EPSILON);

		// 中点应朝节点连线偏向的一侧偏移（S 弯），而不是零。
		boolean lateralDeviationFound = false;
		final double midX = 0.5 + 12 / 2D;
		final double midZ = 0.5 + 6 / 2D;
		for (int i = 1; i < cubic.x.length - 1; i++) {
			if (Math.abs(cubic.z[i] - (midZ + (cubic.x[i] - midX) * 6 / 12D)) > 0.1) {
				lateralDeviationFound = true;
				break;
			}
		}
		assertTrue(lateralDeviationFound, "S-bend must deviate from the straight chord mid-path");
	}

	/** 三次抛物线与半波正弦在同一几何下应给出不同的中段轨迹。 */
	@Test
	void testProfilesDiffer() {
		final EasedRailPath cubic = EasedRailPath.build(0, 0, 0, 10, 10, Math.PI / 2, false);
		final EasedRailPath sine = EasedRailPath.build(0, 0, 0, 10, 10, Math.PI / 2, true);
		assertNotNull(cubic);
		assertNotNull(sine);

		boolean differ = false;
		for (int i = 1; i < Math.min(cubic.x.length, sine.x.length) - 1; i++) {
			if (Math.abs(cubic.z[i] - sine.z[i]) > 1E-3 || Math.abs(cubic.x[i] - sine.x[i]) > 1E-3) {
				differ = true;
				break;
			}
		}
		assertTrue(differ, "cubic parabola and half-wave sine must produce different mid-path geometry");
	}

	/** 对齐的同向节点对应直线。 */
	@Test
	void testStraight() {
		final EasedRailPath path = EasedRailPath.build(3, -2, 0, 13, -2, 0, false);
		assertNotNull(path);
		assertEquals(10, path.length, NODE_EPSILON);
		for (int i = 0; i < path.x.length; i++) {
			assertEquals(3.5 + 10D * i / (path.x.length - 1), path.x[i], NODE_EPSILON);
			assertEquals(-1.5, path.z[i], NODE_EPSILON);
			assertEquals(0, path.heading[i], HEADING_EPSILON);
		}
		assertEquals(0, path.minRadius1);
		assertEquals(0, path.minRadius2);
	}

	/** 同一位置的节点（零弦长）不可表示。 */
	@Test
	void testDegenerateNodesRejected() {
		assertNull(EasedRailPath.build(5, 5, 0, 5, 5, Math.PI, false));
	}

	/** 超过 135° 的极端转角回退经典求解器。 */
	@Test
	void testExtremeHeadingChangeRejected() {
		assertNull(EasedRailPath.build(0, 0, 0, 1, 1, Math.PI, false));
	}

	/** 终点位于起点行进方向的反侧（折返）不可表示。 */
	@Test
	void testNodeBehindStartRejected() {
		assertNull(EasedRailPath.build(0, 0, 0, -5, 0, Math.PI, false));
	}

	/** 急转弯的最小半径应为有限正值。 */
	@Test
	void testMinRadius() {
		final EasedRailPath path = EasedRailPath.build(0, 0, 0, 6, 6, Math.PI / 2, true);
		assertNotNull(path);
		assertTrue(path.minRadius1 > 0, "first half must be curved");
		assertTrue(path.minRadius2 > 0, "second half must be curved");
		assertTrue(Double.isFinite(path.minRadius1));
		assertTrue(Double.isFinite(path.minRadius2));
	}
}
