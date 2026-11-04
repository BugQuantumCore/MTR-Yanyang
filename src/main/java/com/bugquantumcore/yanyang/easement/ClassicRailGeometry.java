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
 * <li>按 {@code Rail.getRailAngle} 的原逻辑，在两端各采样一个微小弧长得到方向角
 * ——但<b>不量化到 22.5° 网格</b>：起点为行进方向、终点为指向起点的反向朝向，
 * 交由 {@link EasedRailPath#build} 的入参约定使用。方速（FangSu）万向节点会以
 * 任意角度构造轨道（幻影 {@code RailAngle}），量化会把直线轨道变成错误的弯曲
 * 缓和路径；而 MTR 自己的 {@code getRailAngle} 在读 {@code facingStart/facingEnd}
 * 时独立经由 {@code RailAngle.fromAngle} 量化，因此不量化不影响方向角引用一致性；</li>
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
         * 按已解析的线型求解缓和路径。
         *
         * @param shape 线型；{@code null} 表示经典线型
         * @return 缓和路径；未设置线型、几何退化或不可表示时返回 {@code null}
         */
        public EasedRailPath resolveEased(EasementShape shape) {
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

                // 方向角：按 getRailAngle 的采样逻辑推导，保留真实角度不量化。
                // 终点处得到的是指向起点的反向朝向（存储角），行进方向为其相反角。
                final double[] startPlus = positionXZ(ANGLE_EPSILON);
                final double[] endMinus = positionXZ(classicLength - ANGLE_EPSILON);
                final double heading1 = heading(startPlus[0] - start[0], startPlus[1] - start[1]);
                final double heading2Stored = heading(endMinus[0] - end[0], endMinus[1] - end[1]);

                return EasedRailPath.build(x1, z1, heading1, x2, z2, opposite(heading2Stored), shape == EasementShape.HALF_WAVE_SINE);
        }

        /**
         * v1.0.4：以「真实节点对」锚定端点求解缓和路径。
         *
         * <p>被 ANTE/NTE 轨道编辑器重写过的 {@code Rail}，其经典几何字段（h/k/r/t 等）
         * 可能不再满足 MTR 构造器的端点锚定性质 —— 从几何推导的端点会偏离真实节点
         * 若干格（例如一条南北向长轨被推导到错误的 x 上，导致镜像查询 miss、
         * 线型不生效）。</p>
         *
         * <ul>
         * <li>若几何推导端点与真实节点一致（绝大多数原生 MTR 轨道），行为与
         * {@link #resolveEased(EasementShape)} 完全相同（采样表逐位一致）；</li>
         * <li>否则把端点按「就近配对」锚定到服务器广播携带的真实节点；方向角仍取
         * 几何切向（编辑器写入的几何朝向通常与节点连线方向一致）；竖向剖面由
         * {@code Rail} 自身的 {@code getPositionY} 决定，与本处无关。</li>
         * </ul>
         *
         * @param shape 线型；{@code null} 表示经典线型
         * @param nodeA 真实节点 A 的 {@code BlockPos.asLong()} 打包值（服务器广播携带）
         * @param nodeB 真实节点 B 的 {@code BlockPos.asLong()} 打包值
         * @return 缓和路径；未设置线型或几何退化时返回 {@code null}
         */
        public EasedRailPath resolveEasedAnchored(EasementShape shape, long nodeA, long nodeB) {
                if (shape == null) {
                        return null;
                }

                final double classicLength = length();
                if (classicLength <= ANGLE_EPSILON) {
                        return null;
                }

                final double[] start = positionXZ(0);
                final double[] end = positionXZ(classicLength);
                final long geoX1 = (long) Math.floor(start[0]);
                final long geoZ1 = (long) Math.floor(start[1]);
                final long geoX2 = (long) Math.floor(end[0]);
                final long geoZ2 = (long) Math.floor(end[1]);

                // 真实节点解包（与 RailPair.pack / BlockPos.asLong 的布局一致）。
                final long ax = unpackX(nodeA);
                final long az = unpackZ(nodeA);
                final long bx = unpackX(nodeB);
                final long bz = unpackZ(nodeB);

                final long x1;
                final long z1;
                final long x2;
                final long z2;
                if (geoX1 == ax && geoZ1 == az && geoX2 == bx && geoZ2 == bz) {
                        // 几何推导端点 == 真实节点：与 resolveEased 完全一致（无回归）。
                        x1 = geoX1;
                        z1 = geoZ1;
                        x2 = geoX2;
                        z2 = geoZ2;
                } else {
                        // 几何被编辑器污染：按距离就近配对起终点，强制锚定到真实节点。
                        final double forwardCost = dist2(start[0], start[1], ax, az) + dist2(end[0], end[1], bx, bz);
                        final double backwardCost = dist2(start[0], start[1], bx, bz) + dist2(end[0], end[1], ax, az);
                        if (forwardCost <= backwardCost) {
                                x1 = ax;
                                z1 = az;
                                x2 = bx;
                                z2 = bz;
                        } else {
                                x1 = bx;
                                z1 = bz;
                                x2 = ax;
                                z2 = az;
                        }
                }

                final double[] startPlus = positionXZ(ANGLE_EPSILON);
                final double[] endMinus = positionXZ(classicLength - ANGLE_EPSILON);
                final double heading1 = heading(startPlus[0] - start[0], startPlus[1] - start[1]);
                final double heading2Stored = heading(endMinus[0] - end[0], endMinus[1] - end[1]);

                return EasedRailPath.build(x1, z1, heading1, x2, z2, opposite(heading2Stored), shape == EasementShape.HALF_WAVE_SINE);
        }

        /** 平方距离（仅用于端点-节点就近配对的比较，无需开方）。 */
        private static double dist2(double px, double pz, long qx, long qz) {
                final double dx = px - qx;
                final double dz = pz - qz;
                return dx * dx + dz * dz;
        }

        /** {@code BlockPos.asLong()} 布局的 X 解包（26 位符号扩展）。 */
        static long unpackX(long packed) {
                return signExtend26(packed >> 38 & 0x3FFFFFF);
        }

        /** {@code BlockPos.asLong()} 布局的 Z 解包（26 位符号扩展）。 */
        static long unpackZ(long packed) {
                return signExtend26(packed >> 12 & 0x3FFFFFF);
        }

        private static long signExtend26(long value) {
                return (value & 0x2000000) != 0 ? value - 0x4000000 : value;
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
         * @return 方向向量对应的罗盘角（弧度，自东顺时针），保留真实角度不量化。
         * 与 {@code atan2(dz, dx)} 一致；量化发生在 MTR 自己的
         * {@code RailAngle.fromAngle}（读 {@code facingStart/facingEnd} 时），与本处无关。
         */
        private static double heading(double dx, double dz) {
                return Math.atan2(dz, dx);
        }

        /**
         * @return 相反的行进方向角（差 180°）。
         * 与 {@code RailAngle.getOpposite().angleRadians} 在真实角度上的语义一致。
         */
        private static double opposite(double headingRadians) {
                return normalizeRadians(headingRadians + Math.PI);
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
