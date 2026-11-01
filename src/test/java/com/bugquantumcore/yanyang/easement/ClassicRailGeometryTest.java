package com.bugquantumcore.yanyang.easement;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ClassicRailGeometry}（经典几何 → 节点/方向角复原 → 缓和路径求解）的端到端
 * 数学验证，等价于 {@code RailMixin} 在游戏中对反序列化轨道执行的完整流程。
 *
 * <p>用合成的经典分段参数（与 MTR 3.x {@code Rail} 构造函数产出的字段同构）构造
 * 直线与四分之一圆弧两种几何，验证：节点方块坐标复原、22.5° 网格方向角复原、
 * 缓和路径端点锚定与端点方向角；并验证线型键解析（印记 / 轨道附加数据两种载体）。</p>
 */
class ClassicRailGeometryTest {

        private static final double NODE_EPSILON = 1E-6;
        /**
         * 方向角容差：从弧长 1E-4 的弦采样推导切向存在约 eps/(2r) 的固有偏差
         * （最小半径 2 时至多 2.5E-5），1E-4 足以覆盖且远小于 22.5° 网格步长。
         */
        private static final double HEADING_EPSILON = 1E-4;

        /**
         * 直线轨道：从 (0, 0) 朝东到 (10, 0)。
         * 经典字段与 {@code Rail} 的「1. a. 对齐直线」分支同构（h=1, k=0）。
         */
        @Test
        void testStraightRailRecovery() {
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                1, 0, 0, 0, 10,   // h1=1, k1=0, r1=0, tStart1=0, tEnd1=10（x = t + 0.5）
                                0, 0, 0, 0, 0,   // 第二段退化
                                false, false, true, true
                );

                assertEquals(10, geometry.length(), NODE_EPSILON);
                final double[] start = geometry.positionXZ(0);
                assertEquals(0.5, start[0], NODE_EPSILON);
                assertEquals(0.5, start[1], NODE_EPSILON);
                final double[] end = geometry.positionXZ(10);
                assertEquals(10.5, end[0], NODE_EPSILON);
                assertEquals(0.5, end[1], NODE_EPSILON);

                final EasedRailPath path = geometry.resolveEased(EasementShape.CUBIC_PARABOLA);
                assertNotNull(path);
                // 对齐直线 + 零转角 → 直线采样表
                assertEquals(10, path.length, NODE_EPSILON);
                assertEquals(0.5, path.x[0], NODE_EPSILON);
                assertEquals(0.5, path.z[0], NODE_EPSILON);
                assertEquals(10.5, path.x[path.x.length - 1], NODE_EPSILON);
                assertEquals(0.5, path.z[path.z.length - 1], NODE_EPSILON);
        }

        /**
         * 四分之一圆弧：从 (0, 0) 朝东到 (10, 10) 朝南（存储反向角 = 北）。
         * 经典字段与 {@code Rail} 的圆弧分支同构：圆心 (0.5, 10.5)，半径 10，
         * x = 0.5 + 10·cos(t/10)，z = 10.5 + 10·sin(t/10)，
         * 从 t = -5π（角 -90°，点 (0.5, 0.5)，切向朝东）到 t = 0（角 0°，点 (10.5, 10.5)，切向朝南）。
         */
        @Test
        void testQuarterArcRecovery() {
                final double radius = 10;
                final double tStart = -Math.PI / 2 * radius;
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                0, 10, radius, tStart, 0,       // 圆心 (0, 10)（h/k 不含 +0.5 方块中心偏移）
                                0, 0, 0, 0, 0,                 // 第二段退化
                                false, false, false, true      // 第一段为圆弧
                );

                final double quarterArc = Math.PI / 2 * radius;
                assertEquals(quarterArc, geometry.length(), 1E-9);

                final EasedRailPath cubic = geometry.resolveEased(EasementShape.CUBIC_PARABOLA);
                assertNotNull(cubic);
                // 复原出的节点与端点方向角
                assertEquals(0.5, cubic.x[0], NODE_EPSILON);
                assertEquals(0.5, cubic.z[0], NODE_EPSILON);
                assertEquals(10.5, cubic.x[cubic.x.length - 1], NODE_EPSILON);
                assertEquals(10.5, cubic.z[cubic.z.length - 1], NODE_EPSILON);
                // 起点 0°（东）；终点行进方向 90°（南）—— 反向存储角（北）已被正确翻转
                assertEquals(0, cubic.heading[0], HEADING_EPSILON);
                assertEquals(Math.PI / 2, cubic.heading[cubic.heading.length - 1], HEADING_EPSILON);

                // 同一几何在半波正弦下同样可解，且端点一致
                final EasedRailPath sine = geometry.resolveEased(EasementShape.HALF_WAVE_SINE);
                assertNotNull(sine);
                assertEquals(0.5, sine.x[0], NODE_EPSILON);
                assertEquals(10.5, sine.x[sine.x.length - 1], NODE_EPSILON);
        }

        /**
         * 线型解析：未设置 / 值无法识别 → 经典线型（零侵入）；
         * 印记键（legacy 载体）与轨道附加数据（capable 载体）解析结果一致。
         */
        @Test
        void testShapeKeyParsing() {
                final Map<String, String> railData = new HashMap<>();
                assertNull(EasementShape.fromKey(null));
                assertNull(EasementShape.fromKey(""));
                assertNull(EasementShape.fromKey("classic"));
                assertNull(EasementShape.fromKey("比例抛物线"));
                assertNull(EasementShape.fromRailData(null));
                assertNull(EasementShape.fromRailData(railData));
                railData.put(EasementShape.RAIL_DATA_KEY, "cubic_parabola");
                assertEquals(EasementShape.CUBIC_PARABOLA, EasementShape.fromRailData(railData));
                assertEquals(EasementShape.HALF_WAVE_SINE, EasementShape.fromKey(" half_wave_sine "));
                assertEquals(EasementShape.HALF_WAVE_SINE, EasementShape.fromKey("Half_Wave_Sine"));
        }

        /**
         * 未设置线型 / 值无法识别 → 返回 null（经典线型，零侵入）。
         */
        @Test
        void testNoShapeReturnsNull() {
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                1, 0, 0, 0, 10, 0, 0, 0, 0, 0, false, false, true, true
                );
                assertNull(geometry.resolveEased(null));
                assertNull(geometry.resolveEased(EasementShape.fromKey("classic")));
                assertNull(geometry.resolveEased(EasementShape.fromKey("比例抛物线")));
        }

        /**
         * 退化几何（长度为 0 的「被禁」轨道）→ 返回 null，不抛异常。
         */
        @Test
        void testDegenerateGeometryReturnsNull() {
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, true, true
                );
                assertNull(geometry.resolveEased(EasementShape.CUBIC_PARABOLA));
        }

        /**
         * 45° 斜向直线（h = k = √2/2）—— 验证斜向直线分支的方块中心复原。
         * 从 (3, 1) 朝东南前进 9 块（弧长 9√2），终点应为 (12, 10)。
         */
        @Test
        void testDiagonalStraightRecovery() {
                final double c = Math.cos(Math.PI / 4);
                final double s = Math.sin(Math.PI / 4);
                // h=k≈0.707 ≥ 0.5：x = h·t + 0.5，z = k·t + h·r + 0.5
                final int xStart = 3;
                final int zStart = 1;
                final double r = (c * zStart - s * xStart) / (c * c);
                final double tStart = xStart / c;
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                c, s, r, tStart, tStart + 9 * Math.sqrt(2),   // 弧长 9√2 → Δx = Δz = 9
                                0, 0, 0, 0, 0,
                                false, false, true, true
                );

                assertEquals(9 * Math.sqrt(2), geometry.length(), 1E-9);
                final EasedRailPath path = geometry.resolveEased(EasementShape.HALF_WAVE_SINE);
                assertNotNull(path);
                assertEquals(xStart + 0.5, path.x[0], NODE_EPSILON);
                assertEquals(zStart + 0.5, path.z[0], NODE_EPSILON);
                assertEquals(12.5, path.x[path.x.length - 1], NODE_EPSILON);
                assertEquals(10.5, path.z[path.z.length - 1], NODE_EPSILON);
                // 方向角量化到 45°（东南）
                assertEquals(Math.PI / 4, path.heading[0], HEADING_EPSILON);
                assertEquals(Math.PI / 4, path.heading[path.heading.length - 1], HEADING_EPSILON);
        }

        /**
         * <b>方速（FangSu）万向节点兼容</b>：任意角度（非 22.5° 网格）直线轨道。
         *
         * <p>方速的万向节点以幻影 {@code RailAngle} 构造任意角度的直线（如 18.4°），
         * {@code Rail} 几何仍是标准直线分段。缓和求解必须保留真实方向角：
         * 量化到 22.5° 网格会把直线变成错误的弯曲「缓和」路径。</p>
         *
         * <p>构造：从 (0, 0) 沿 atan2(1, 3) ≈ 18.43° 到 (3, 1)，字段与 {@code Rail}
         * 的「1. b. 非对角直线」分支同构（|k| < 0.5：h = cosθ, k = sinθ,
         * div = cos2θ，t = (h·x - k·z)/div）。</p>
         */
        @Test
        void testArbitraryAngleStraightRail() {
                final double theta = Math.atan2(1, 3); // ≈ 18.43°，非网格且 |k| < 0.5
                final double h = Math.cos(theta);
                final double k = Math.sin(theta);
                final double div = Math.cos(2 * theta);
                final double r = 0; // 起点 (0, 0)：(h·z - k·x) = 0
                final double tStart = 0;
                final double tEnd = (h * 3 - k * 1) / div;
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                h, k, r, tStart, tEnd,
                                0, 0, 0, 0, 0,
                                false, false, true, true
                );

                final double length = Math.hypot(3, 1);
                assertEquals(length, geometry.length(), 1E-9);
                // 端点严格落在方块中心
                final double[] start = geometry.positionXZ(0);
                assertEquals(0.5, start[0], NODE_EPSILON);
                assertEquals(0.5, start[1], NODE_EPSILON);
                final double[] end = geometry.positionXZ(length);
                assertEquals(3.5, end[0], NODE_EPSILON);
                assertEquals(1.5, end[1], NODE_EPSILON);

                final EasedRailPath path = geometry.resolveEased(EasementShape.CUBIC_PARABOLA);
                assertNotNull(path);
                // 零转角 + 零横向偏移 → 直线采样表，长度等于弦长
                assertEquals(length, path.length, 1E-6);
                assertEquals(0.5, path.x[0], NODE_EPSILON);
                assertEquals(0.5, path.z[0], NODE_EPSILON);
                assertEquals(3.5, path.x[path.x.length - 1], NODE_EPSILON);
                assertEquals(1.5, path.z[path.z.length - 1], NODE_EPSILON);
                // 真实方向角（≈18.43°），不得量化到 0° / 22.5°
                assertEquals(theta, path.heading[0], HEADING_EPSILON);
                assertEquals(theta, path.heading[path.heading.length - 1], HEADING_EPSILON);
                // 中点严格在连线上：(z - 0.5) / (x - 0.5) = 1 / 3
                final int mid = path.x.length / 2;
                assertEquals(1D / 3, (path.z[mid] - 0.5) / (path.x[mid] - 0.5), 1E-6);
        }

        /**
         * <b>方速（FangSu）万向节点兼容</b>：一端绑定任意角度切向的圆弧轨道。
         *
         * <p>构造与 {@code NodeConnector.maxRadiusTangentAngle} 同构的「最大半径圆弧」：
         * 过 (0, 0)（切向 20°，非网格）与 (10, 6) 的唯一切圆。经典字段按圆心与
         * 参数区间端点复原，验证缓和求解的端点锚定与<b>真实</b>端点方向角
         * （20° ≈ 0.349 弧度，量化到 22.5° 的旧实现无法通过）。</p>
         */
        @Test
        void testArbitraryAngleArcRail() {
                final double thetaDegrees = 20;
                final double theta = Math.toRadians(thetaDegrees);
                final double s = Math.sin(theta);
                final double c = Math.cos(theta);
                // 圆心在行进方向左侧法线上（几何按 MTR 的 (h,k) 原点系构造，positionXZ
                // 另加 +0.5 方块中心偏移）：C = P1 + R·(-sinθ, cosθ)，由 |C - P2| = R 定解
                // 展开得 R·(20·sinθ - 12·cosθ) + (10² + 6²) = 0（P2 - P1 = (10, 6)，
                // P1 = (0, 0)、P2 = (10, 6) 为节点原始方块坐标）
                final double radius = -136 / (20 * s - 12 * c);
                final double cx = -radius * s;
                final double cz = radius * c;
                // 参数角（自东顺时针为正的罗盘系下即为 atan2(dz, dx)）
                final double phiStart = Math.atan2(-cz, -cx);
                final double phiEnd = Math.atan2(6 - cz, 10 - cx);
                final ClassicRailGeometry geometry = new ClassicRailGeometry(
                                cx, cz, radius, phiStart * radius, phiEnd * radius,
                                0, 0, 0, 0, 0,
                                false, false, false, true
                );

                // 端点落在节点方块中心
                final double length = geometry.length();
                final double[] start = geometry.positionXZ(0);
                assertEquals(0.5, start[0], NODE_EPSILON);
                assertEquals(0.5, start[1], NODE_EPSILON);
                final double[] end = geometry.positionXZ(length);
                assertEquals(10.5, end[0], NODE_EPSILON);
                assertEquals(6.5, end[1], NODE_EPSILON);

                final EasedRailPath path = geometry.resolveEased(EasementShape.CUBIC_PARABOLA);
                assertNotNull(path);
                assertEquals(0.5, path.x[0], NODE_EPSILON);
                assertEquals(0.5, path.z[0], NODE_EPSILON);
                assertEquals(10.5, path.x[path.x.length - 1], NODE_EPSILON);
                assertEquals(6.5, path.z[path.z.length - 1], NODE_EPSILON);
                // 起点真实切向 = 20°（圆弧上参数角处的切向 = 参数角 + 90°）；
                // 弦采样固有的 eps/(2r) 偏差由 HEADING_EPSILON 覆盖
                assertEquals(Math.toRadians(thetaDegrees), path.heading[0], HEADING_EPSILON);
                // 终点行进方向 = 终点参数角 + 90°（自东顺时针）
                final double expectedEndHeading = phiEnd + Math.PI / 2;
                final double wrapped = Math.atan2(Math.sin(expectedEndHeading), Math.cos(expectedEndHeading));
                assertEquals(wrapped, path.heading[path.heading.length - 1], HEADING_EPSILON);
        }
}
