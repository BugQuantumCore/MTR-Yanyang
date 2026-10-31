package com.bugquantumcore.yanyang.easement;

import java.util.Map;

/**
 * 经典「直线 + 圆弧」求解器几何的只读快照，以及从中复原两节点并求解缓和路径的逻辑。
 *
 * <p>MTR 3.x 的 {@code Rail} 在构造函数中把轨道几何烘焙为至多两段（直线或圆弧）的
 * 参数（h/k/r/tStart/tEnd 等，公式见 MTR 源码注释：直线 {@code x = h*T, z = k*T + h*r}，
 * 圆弧 {@code x = h + r*cos(T), z = k + r*sin(T)}）。本类把 {@code RailMixin} 需要的
 * 复原步骤集中到一处，且只用 double 表达 —— 不依赖任何 Minecraft / MTR 类，可以
 * 直接单元测试：</p>
 *
 * <ol>
 * <li>按经典公式取弧长 0 与总长处的中心线位置，向下取整得到两节点方块坐标（经典
 * 求解器的端点严格落在方块中心上，浮点误差为 1E-12 量级）；</li>
 * <li>按 {@code Rail.getRailAngle} 的原逻辑，在两端各采样一个微小弧长得到方向角，
 * 并量化到 22.5° 网格（与 {@code RailAngle.fromAngle} 等价），起点为行进方向、
 * 终点为指向起点的反向朝向 —— 与 MTR-Yanyang {@code tsc-1.21.x} 中
 * {@code EasedRailPath.build} 的入参约定一致；</li>
 * <li>交由 {@link EasedRailPath#build} 求解缓和路径。</li>
 * </ol>
 */
public final class ClassicRailGeometry {

	private final double h1;
	private final double k1;
	private final double r1;
	private final double tStart1;
	private final double tEnd1;
	private final double h2;
	private final double k2;
	private final double r2;
	private final double tStart2;
	private final double tEnd2;
	private final boolean reverseT1;
	private final boolean reverseT2;
	private final boolean isStraight1;
	private final boolean isStraight2;

	/**
	 * 与 {@code Rail.getRailAngle} 相同的采样步长（{@code ACCEPT_THRESHOLD}）。
	 */
	private static final double ANGLE_EPSILON = 1E-4;

	/**
	 * 方向角量化步长（度），与 {@code RailAngle} 的 16 方向网格一致。
	 */
	private static final double ANGLE_GRID_DEGREES = 22.5;

	public ClassicRailGeometry(double h1, double k1, double r1, double tStart1, double tEnd1, double h2, double k2, double r2, double tStart2, double tEnd2, boolean reverseT1, boolean reverseT2, boolean isStraight1, boolean isStraight2) {
		this.h1 = h1;
		this.k1 = k1;
		this.r1 = r1;
		this.tStart1 = tStart1;
		this.tEnd1 = tEnd1;
		this.h2 = h2;
		this.k2 = k2;
		this.r2 = r2;
		this.tStart2 = tStart2;
		this.tEnd2 = tEnd2;
		this.reverseT1 = reverseT1;
		this.reverseT2 = reverseT2;
		this.isStraight1 = isStraight1;
		this.isStraight2 = isStraight2;
	}

	/**
	 * @return 经典分段的总长度（两段弧长绝对值之和），与 {@code Rail.getLength} 一致
	 */
	public double length() {
		return Math.abs(tEnd1 - tStart1) + Math.abs(tEnd2 - tStart2);
	}

	/**
	 * @return 经典求解器中心线上指定弧长处的 (x, z)，无半径偏移。
	 * 与 {@code Rail.getPosition} 的 XZ 部分一致。
	 */
	public double[] positionXZ(double rawValue) {
		final double count1 = Math.abs(tEnd1 - tStart1);
		final double count2 = Math.abs(tEnd2 - tStart2);
		final double value = Math.max(0, Math.min(rawValue, count1 + count2));

		if (value <= count1) {
			return segmentXZ(h1, k1, r1, (reverseT1 ? -1 : 1) * value + tStart1, isStraight1);
		} else {
			return segmentXZ(h2, k2, r2, (reverseT2 ? -1 : 1) * (value - count1) + tStart2, isStraight2);
		}
	}

	/**
	 * 按轨道附加数据中标记的线型求解缓和路径。
	 *
	 * @return 缓和路径；未设置线型、几何退化或不可表示时返回 {@code null}
	 */
	public EasedRailPath resolveEased(Map<String, String> railData) {
		final EasementShape shape = EasementShape.fromRailData(railData);
		if (shape == null) {
			return null;
		}

		final double classicLength = length();
		if (classicLength <= ANGLE_EPSILON) {
			return null;
		}

		// 节点坐标：经典端点严格位于方块中心（±1E-12 级浮点误差），向下取整安全。
		final double[] start = positionXZ(0);
		final double[] end = positionXZ(classicLength);
		final long x1 = (long) Math.floor(start[0]);
		final long z1 = (long) Math.floor(start[1]);
		final long x2 = (long) Math.floor(end[0]);
		final long z2 = (long) Math.floor(end[1]);

		// 方向角：按 getRailAngle 的采样逻辑推导并量化到 22.5° 网格。
		// 终点处得到的是指向起点的反向朝向（存储角），行进方向为其相反角。
		final double[] startPlus = positionXZ(ANGLE_EPSILON);
		final double[] endMinus = positionXZ(classicLength - ANGLE_EPSILON);
		final double heading1 = quantizedHeading(startPlus[0] - start[0], startPlus[1] - start[1]);
		final double heading2Stored = quantizedHeading(endMinus[0] - end[0], endMinus[1] - end[1]);

		return EasedRailPath.build(x1, z1, heading1, x2, z2, opposite(heading2Stored), shape == EasementShape.HALF_WAVE_SINE);
	}

	private static double[] segmentXZ(double h, double k, double r, double t, boolean isStraight) {
		if (isStraight) {
			final double sideOffset = Math.abs(h) >= 0.5 && Math.abs(k) >= 0.5 ? 0 : r;
			return new double[]{h * t + k * sideOffset + 0.5, k * t + h * r + 0.5};
		} else {
			return new double[]{h + r * Math.cos(t / r) + 0.5, k + r * Math.sin(t / r) + 0.5};
		}
	}

	/**
	 * @return 方向向量对应的罗盘角（弧度，自东顺时针），量化到 22.5° 网格。
	 * 与 {@code RailAngle.fromAngle(Math.toDegrees(atan2(dz, dx)))} 等价。
	 */
	private static double quantizedHeading(double dx, double dz) {
		final double degrees = Math.toDegrees(Math.atan2(dz, dx));
		final double grid = ANGLE_GRID_DEGREES;
		final double quantized = Math.round((normalizeDegrees(degrees) + 360) / grid) % (360 / grid) * grid;
		return Math.toRadians(normalizeDegrees(quantized));
	}

	/**
	 * @return 相反的行进方向角（差 180°，仍在 22.5° 网格上）。
	 * 与 {@code RailAngle.getOpposite().angleRadians} 等价。
	 */
	private static double opposite(double headingRadians) {
		return normalizeRadians(headingRadians + Math.PI);
	}

	private static double normalizeDegrees(double degrees) {
		double result = degrees;
		while (result < -180) {
			result += 360;
		}
		while (result >= 180) {
			result -= 360;
		}
		return result;
	}

	private static double normalizeRadians(double radians) {
		double result = radians;
		while (result < -Math.PI) {
			result += 2 * Math.PI;
		}
		while (result >= Math.PI) {
			result -= 2 * Math.PI;
		}
		return result;
	}
}
