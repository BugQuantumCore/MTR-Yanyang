package com.bugquantumcore.yanyang.easement;

/**
 * 水平缓和曲线（过渡曲线）几何 —— 整条轨道层面的、现实铁路中三次抛物线与半波正弦
 * 缓和曲线的方块尺度对应实现。
 *
 * <p>本类是 MTR-Yanyang 仓库 {@code tsc-1.21.x} 分支
 * {@code org.mtr.core.data.EasedRailPath}（Transport Simulation Core，MIT 许可，
 * 适用于 MTR 4.1.x / Minecraft 1.21.x）向 MTR 3.x 架构的忠实移植：算法、常量与求解
 * 流程与原实现完全一致，仅将输入类型从 TSC 的 {@code Position}/{@code Angle} 改为
 * 纯 double，使本类不依赖任何 Minecraft / MTR 类，便于独立测试与复用。</p>
 *
 * <p>与经典的「直线—圆弧」突变拼接不同，整条轨道成为一条曲率连续的路径：曲率在
 * 两端为零（与相邻轨道端点衔接处没有曲率阶跃），中间按所选线型分布：</p>
 * <ul>
 * <li><b>三次抛物线（cubic parabola）</b> —— 分段<b>线性</b>曲率，路径的每个分量
 * 都是三次抛物线缓和曲线（线性曲率爬升 = 二次方向角爬升 = 三次横向偏移）。曲率先
 * 爬升、维持一段圆弧平台、再回落，即现实的「直—缓—圆—缓—直」序列；当弯道足够
 * 温和时圆弧平台收缩为一点，成为纯双缓和曲线。</li>
 * <li><b>半波正弦（half-wave sine）</b> —— 以单个半波正弦作为曲率图，是经典线型中
 * 最平滑的一种：曲率处处无角点，连峰值处也是光滑的。</li>
 * </ul>
 *
 * <p>方向角剖面被参数化为 {@code headingChange * profileA(tau) + flex * profileB(tau)}：
 * {@code profileA} 承载必须达到的总转角（起点为 0、终点为 1、两端斜率为 0，因此端点
 * 曲率为零）；{@code profileB} 是不改总转角的「柔性凸包」（两端为 0 且斜率为 0，峰值为
 * 1），用于补足两个节点之间所需的额外横向到达距离。剩余两个未知量 —— 总弧长与柔性幅
 * 值 —— 通过带阻尼的 2×2 牛顿迭代对两个端点坐标定解。</p>
 *
 * <p>求解出的路径被一次性积分为等距采样表（世界坐标 + 每采样点方向角）。渲染、车辆
 * 放置、包围盒与半径查询全部对同一张表插值，因此列车天然沿显示的轨道行驶。所有重活
 * 都在构造时完成；单次查询为常数时间插值。两族曲线无法表达的几何（极端转角、轨道
 * 折返到起点节点之后、求解失败）返回 {@code null}，调用方回退到经典分段求解器，因此
 * 缓和曲线永远是严格的可选增强。</p>
 */
public final class EasedRailPath {

	/**
	 * 缓和路径的总弧长，单位为方块。始终不小于两节点间的弦长。
	 */
	public final double length;
	/**
	 * 采样表相邻样本的等距间隔，单位为方块。
	 */
	public final double step;
	/**
	 * 各采样点的世界 X 坐标；样本 {@code i} 位于距第一节点 {@code i * step} 处（方块中心）。
	 */
	public final double[] x;
	/**
	 * 各采样点的世界 Z 坐标；样本 {@code i} 位于距第一节点 {@code i * step} 处（方块中心）。
	 */
	public final double[] z;
	/**
	 * 各采样点的世界方向角，弧度制罗盘角，自东（+X 指向 +Z）顺时针为正，
	 * 与 MTR 的 {@code RailAngle.angleRadians} 约定一致。
	 */
	public final double[] heading;
	/**
	 * 路径前半段的最小水平半径；该半段为直线时为 0，与经典分段求解器的约定一致。
	 */
	public final double minRadius1;
	/**
	 * 路径后半段的最小水平半径；该半段为直线时为 0。
	 */
	public final double minRadius2;

	private static final double MAX_HEADING_CHANGE = Math.toRadians(135);
	private static final double MIN_ALONG = 0.5;
	private static final double STRAIGHT_EPSILON = 1E-9;
	private static final double SOLVE_TOLERANCE = 1E-4;
	private static final int MAX_SOLVE_ITERATIONS = 16;
	private static final int MIN_SOLVE_STEPS = 32;
	private static final int MAX_SOLVE_STEPS = 128;
	private static final double SOLVE_STEP_TARGET = 0.5;
	private static final double FLEX_LIMIT = Math.PI;
	private static final double TABLE_STEP = 0.25;
	private static final int MAX_TABLE_SAMPLES = 1024;
	private static final int MIN_TABLE_SAMPLES = 4;

	private EasedRailPath(double length, double step, double[] x, double[] z, double[] heading, double minRadius1, double minRadius2) {
		this.length = length;
		this.step = step;
		this.x = x;
		this.z = z;
		this.heading = heading;
		this.minRadius1 = minRadius1;
		this.minRadius2 = minRadius2;
	}

	/**
	 * 求解并积分两节点之间的缓和路径。
	 *
	 * @param x1                    第一节点的方块 X 坐标
	 * @param z1                    第一节点的方块 Z 坐标
	 * @param heading1Radians       第一节点的行进方向角（朝向轨道内部）
	 * @param x2                    第二节点的方块 X 坐标
	 * @param z2                    第二节点的方块 Z 坐标
	 * @param heading2TravelRadians 第二节点的行进方向角（行进方向，即离开轨道、继续向前的
	 *                              方向）；与 MTR 3.x {@code Rail} 存储的 {@code facingEnd}
	 *                              （自节点 2 指向节点 1 的反向朝向）互为相反方向
	 * @param halfWaveSine          {@code true} 为半波正弦线型，{@code false} 为三次抛物线线型
	 * @return 采样后的路径；当几何超出两族曲线的表达能力时返回 {@code null}
	 *         （调用方随后回退到经典分段求解器）
	 */
	public static EasedRailPath build(double x1, double z1, double heading1Radians, double x2, double z2, double heading2TravelRadians, boolean halfWaveSine) {
		final double originX = x1 + 0.5;
		final double originZ = z1 + 0.5;
		final double cos1 = Math.cos(heading1Radians);
		final double sin1 = Math.sin(heading1Radians);
		final double differenceX = x2 - x1;
		final double differenceZ = z2 - z1;
		final double headingChange = headingChange(heading1Radians, heading2TravelRadians);

		// 局部坐标系：+t 沿第一节点方向角，+n 指向其右侧（罗盘角 +90°）。
		final double along = differenceX * cos1 + differenceZ * sin1;
		final double lateral = differenceZ * cos1 - differenceX * sin1;
		final double chord = Math.hypot(along, lateral);

		if (chord < STRAIGHT_EPSILON || along < MIN_ALONG || Math.abs(headingChange) > MAX_HEADING_CHANGE) {
			return null;
		}
		if (Math.abs(headingChange) < STRAIGHT_EPSILON && Math.abs(lateral) < STRAIGHT_EPSILON) {
			return straightPath(originX, originZ, x2 + 0.5, z2 + 0.5, chord, heading1Radians);
		}

		final double[] solution = solve(halfWaveSine, headingChange, along, lateral, chord);
		if (solution == null) {
			return null;
		}

		return integrate(halfWaveSine, headingChange, solution[0], solution[1], originX, originZ, cos1, sin1, heading1Radians, x2 + 0.5, z2 + 0.5, heading2TravelRadians);
	}

	/**
	 * @return 两节点方向角之间的总转角，包装到 (-180°, 180°] 的罗盘弧度区间
	 */
	private static double headingChange(double heading1Radians, double heading2Radians) {
		double headingChange = heading2Radians - heading1Radians;
		while (headingChange > Math.PI) {
			headingChange -= 2 * Math.PI;
		}
		while (headingChange <= -Math.PI) {
			headingChange += 2 * Math.PI;
		}
		return headingChange;
	}

	/**
	 * 对弧长与柔性幅值的带阻尼 2×2 牛顿求解。总转角由构造精确保证；剩余两个未知量由
	 * 两个端点坐标定解。失败前会从两个不同的初值各尝试一次。
	 */
	private static double[] solve(boolean halfWaveSine, double headingChange, double along, double lateral, double chord) {
		final double minArcLength = chord + 1E-6;
		final double maxArcLength = chord * 2 + 24;

		double arcLength = chord * 1.05 + 0.1;
		final double[] straightEnd = integrateEnd(halfWaveSine, headingChange, arcLength, 0);
		final double flex = clampFlex((lateral - straightEnd[1]) / Math.max(arcLength / 2, 1E-6));
		final double[] solution = solveFrom(halfWaveSine, headingChange, along, lateral, minArcLength, maxArcLength, arcLength, flex);
		if (solution != null) {
			return solution;
		}

		// 第二次尝试：直接估计 S 形弯所需的中途方向角。
		final double arcLengthRetry = chord * 1.25 + 0.5;
		return solveFrom(halfWaveSine, headingChange, along, lateral, minArcLength, maxArcLength, arcLengthRetry, clampFlex(2 * Math.atan2(lateral, along)));
	}

	private static double clampFlex(double flex) {
		return Math.max(-FLEX_LIMIT, Math.min(FLEX_LIMIT, flex));
	}

	/**
	 * 从给定初值出发的一次带阻尼牛顿迭代。返回收敛后的（弧长，柔性幅值）二元组；
	 * 未收敛则返回 {@code null}。
	 */
	private static double[] solveFrom(boolean halfWaveSine, double headingChange, double along, double lateral, double minArcLength, double maxArcLength, double arcLength, double flex) {
		for (int iteration = 0; iteration < MAX_SOLVE_ITERATIONS; iteration++) {
			final double[] end = integrateEnd(halfWaveSine, headingChange, arcLength, flex);
			final double errorAlong = end[0] - along;
			final double errorLateral = end[1] - lateral;
			if (Math.abs(errorAlong) + Math.abs(errorLateral) < SOLVE_TOLERANCE) {
				return new double[]{arcLength, flex};
			}

			final double stepLength = Math.max(1E-3, arcLength * 1E-5);
			final double stepFlex = 1E-4;
			final double[] endLength = integrateEnd(halfWaveSine, headingChange, arcLength + stepLength, flex);
			final double[] endFlex = integrateEnd(halfWaveSine, headingChange, arcLength, flex + stepFlex);

			final double alongPerLength = (endLength[0] - end[0]) / stepLength;
			final double lateralPerLength = (endLength[1] - end[1]) / stepLength;
			final double alongPerFlex = (endFlex[0] - end[0]) / stepFlex;
			final double lateralPerFlex = (endFlex[1] - end[1]) / stepFlex;

			final double determinant = alongPerLength * lateralPerFlex - alongPerFlex * lateralPerLength;
			if (!Double.isFinite(determinant) || Math.abs(determinant) < 1E-12) {
				return null;
			}

			arcLength = Math.max(minArcLength, Math.min(maxArcLength, arcLength + (errorLateral * alongPerFlex - errorAlong * lateralPerFlex) / determinant));
			flex = clampFlex(flex + (errorAlong * lateralPerLength - errorLateral * alongPerLength) / determinant);
		}

		return null;
	}

	/**
	 * 以求解分辨率对方向角剖面积分一次，返回行走 {@code arcLength} 方块后到达的局部
	 * 端点坐标（along, lateral）。
	 */
	private static double[] integrateEnd(boolean halfWaveSine, double headingChange, double arcLength, double flex) {
		final int steps = (int) Math.max(MIN_SOLVE_STEPS, Math.min(MAX_SOLVE_STEPS, Math.ceil(arcLength / SOLVE_STEP_TARGET)));
		final double step = arcLength / steps;
		double along = 0;
		double lateral = 0;

		for (int index = 0; index < steps; index++) {
			final double tau = (index + 0.5) / steps;
			final double theta = headingChange * profileA(tau, halfWaveSine) + flex * profileB(tau, halfWaveSine);
			along += step * Math.cos(theta);
			lateral += step * Math.sin(theta);
		}

		return new double[]{along, lateral};
	}

	/**
	 * 构建最终的等距采样表：以渲染分辨率做中点积分，然后叠加一个平滑（两端零斜率）
	 * 修正来吸收求解残差，使路径精确落在两个节点上、且两端方向角精确等于请求值。
	 */
	private static EasedRailPath integrate(boolean halfWaveSine, double headingChange, double arcLength, double flex, double originX, double originZ, double cos1, double sin1, double heading1, double targetX, double targetZ, double heading2Travel) {
		final int sampleCount = (int) Math.max(MIN_TABLE_SAMPLES, Math.min(MAX_TABLE_SAMPLES, Math.ceil(arcLength / TABLE_STEP)));
		final double step = arcLength / sampleCount;
		final double[] x = new double[sampleCount + 1];
		final double[] z = new double[sampleCount + 1];
		final double[] heading = new double[sampleCount + 1];

		x[0] = originX;
		z[0] = originZ;
		heading[0] = heading1;

		double along = 0;
		double lateral = 0;
		double maxCurvature1 = 0;
		double maxCurvature2 = 0;

		for (int index = 1; index <= sampleCount; index++) {
			final double tauMid = (index - 0.5) / sampleCount;
			final double thetaMid = headingChange * profileA(tauMid, halfWaveSine) + flex * profileB(tauMid, halfWaveSine);
			along += step * Math.cos(thetaMid);
			lateral += step * Math.sin(thetaMid);

			final double curvature = (headingChange * slopeA(tauMid, halfWaveSine) + flex * slopeB(tauMid, halfWaveSine)) / arcLength;
			if (index <= sampleCount / 2) {
				maxCurvature1 = Math.max(maxCurvature1, Math.abs(curvature));
			} else {
				maxCurvature2 = Math.max(maxCurvature2, Math.abs(curvature));
			}

			final double tau = (double) index / sampleCount;
			final double theta = headingChange * profileA(tau, halfWaveSine) + flex * profileB(tau, halfWaveSine);
			x[index] = originX + along * cos1 - lateral * sin1;
			z[index] = originZ + along * sin1 + lateral * cos1;
			heading[index] = heading1 + theta;
		}

		// 将路径精确锚定在第二节点上。普通几何下残差为亚毫米级（极端急转弯情形至多
		// 几厘米），并被平滑地摊到整条路径上，因此不会引入折角。
		final double residualX = targetX - x[sampleCount];
		final double residualZ = targetZ - z[sampleCount];
		for (int index = 1; index <= sampleCount; index++) {
			final double tau = (double) index / sampleCount;
			final double weight = tau * tau * (3 - 2 * tau);
			x[index] += residualX * weight;
			z[index] += residualZ * weight;
		}

		heading[sampleCount] = heading2Travel;

		return new EasedRailPath(
				arcLength, step, x, z, heading,
				maxCurvature1 > 1E-9 ? 1 / maxCurvature1 : 0,
				maxCurvature2 > 1E-9 ? 1 / maxCurvature2 : 0
		);
	}

	/**
	 * @return 对齐节点对（零转角、零横向偏移）的直线采样表
	 */
	private static EasedRailPath straightPath(double originX, double originZ, double targetX, double targetZ, double length, double heading) {
		final int sampleCount = (int) Math.max(MIN_TABLE_SAMPLES, Math.min(MAX_TABLE_SAMPLES, Math.ceil(length / TABLE_STEP)));
		final double step = length / sampleCount;
		final double[] x = new double[sampleCount + 1];
		final double[] z = new double[sampleCount + 1];
		final double[] headingTable = new double[sampleCount + 1];

		for (int index = 0; index <= sampleCount; index++) {
			final double tau = (double) index / sampleCount;
			x[index] = originX + (targetX - originX) * tau;
			z[index] = originZ + (targetZ - originZ) * tau;
			headingTable[index] = heading;
		}

		return new EasedRailPath(length, step, x, z, headingTable, 0, 0);
	}

	/**
	 * 承载必须总转角的方向角爬升。起点为 0、终点为 1、两端斜率为 0，
	 * 因此曲率在两端为零。
	 */
	private static double profileA(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return (1 - Math.cos(Math.PI * tau)) / 2;
		}
		if (tau <= 0.25) {
			return 8 * tau * tau / 3;
		}
		if (tau <= 0.75) {
			return 4 * tau / 3 - 1D / 6;
		}
		return 1 - 8 * (1 - tau) * (1 - tau) / 3;
	}

	/**
	 * {@link #profileA} 的斜率，即基础曲率图的形状乘以弧长：半波正弦，或一个
	 * 对称梯形（其爬升段即三次抛物线缓和曲线的线性曲率爬升）。
	 */
	private static double slopeA(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return Math.PI * Math.sin(Math.PI * tau) / 2;
		}
		if (tau <= 0.25) {
			return 16 * tau / 3;
		}
		if (tau <= 0.75) {
			return 4D / 3;
		}
		return 16 * (1 - tau) / 3;
	}

	/**
	 * 不改总转角的柔性凸包：峰值为 1，两端为 0 且斜率为 0。
	 * 它在不改变总转角的情况下增加路径中段的横向到达距离。
	 */
	private static double profileB(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return (1 - Math.cos(2 * Math.PI * tau)) / 2;
		}
		if (tau <= 0.25) {
			return 8 * tau * tau;
		}
		if (tau <= 0.75) {
			final double offset = tau - 0.25;
			return 0.5 + 4 * offset - 8 * offset * offset;
		}
		return 8 * (1 - tau) * (1 - tau);
	}

	/**
	 * {@link #profileB} 的斜率，即柔性曲率图的形状乘以弧长。
	 * 三次抛物线族下仍为分段线性。
	 */
	private static double slopeB(double tau, boolean halfWaveSine) {
		if (halfWaveSine) {
			return Math.PI * Math.sin(2 * Math.PI * tau);
		}
		if (tau <= 0.25) {
			return 16 * tau;
		}
		if (tau <= 0.75) {
			return 4 - 16 * (tau - 0.25);
		}
		return -16 * (1 - tau);
	}
}
