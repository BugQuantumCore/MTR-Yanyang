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
        private static final double HEADING_EPSILON = 1E-6;

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
}
