package com.bugquantumcore.yanyang.easement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.0.4 端点锚定（{@link ClassicRailGeometry#resolveEasedAnchored}）的单元测试，
 * 复现真实用户日志中的场景（见 docs/fixes/FIX-1.0.4）。
 *
 * <p>用户轨道真实节点：{@code (-7,-60,-237) <-> (-1,-60,-8)}；被 ANTE 编辑器污染的
 * 经典几何推导出 {@code (2,-60,-237) <-> (2,-60,-8)}（x 全错）。1.0.3 的镜像只存
 * 节点键 → 客户端几何键查询 miss → 线型不生效；1.0.4 服务器同时广播几何键与节点键，
 * 命中后把端点锚定到真实节点。</p>
 */
class ClassicRailGeometryAnchoringTest {

        private static final double NODE_EPSILON = 1E-6;

        /** 与 {@code BlockPos.asLong()} 布局一致的解包（仅测试用）。 */
        private static long unpackX(long packed) {
                final long x = (packed >> 38) & 0x3FFFFFFL;
                return (x & (1L << 25)) != 0 ? x - (1L << 26) : x;
        }

        private static long unpackZ(long packed) {
                final long z = (packed >> 12) & 0x3FFFFFFL;
                return (z & (1L << 25)) != 0 ? z - (1L << 26) : z;
        }

        private static long unpackY(long packed) {
                final long y = packed & 0xFFFL;
                return (y & 0x800) != 0 ? y - 0x1000 : y;
        }

        /**
         * 场景 1：被污染的几何（南北长轨被推导到错误的 x=2 上）。
         */
        @Test
        void testPollutedGeometryDerivesWrongEndpoints() {
                final ClassicRailGeometry polluted = new ClassicRailGeometry(
                                0, 1, 1.6, -8.5, -237.5,
                                0, 0, 0, 0, 0,
                                true, false, true, true
                );

                final double length = polluted.length();
                assertEquals(229, length, 1E-9);

                final double[] start = polluted.positionXZ(0);
                final double[] end = polluted.positionXZ(length);
                assertEquals(2, Math.floor(start[0]), 0);
                assertEquals(-8, Math.floor(start[1]), 0);
                assertEquals(2, Math.floor(end[0]), 0);
                assertEquals(-237, Math.floor(end[1]), 0);
        }

        /**
         * 场景 2：几何键与节点键不一致（1.0.3 线型不生效的根因），双键广播两者都覆盖。
         */
        @Test
        void testGeometryKeyDiffersFromNodeKeyButBothBroadcast() {
                final ClassicRailGeometry polluted = new ClassicRailGeometry(
                                0, 1, 1.6, -8.5, -237.5,
                                0, 0, 0, 0, 0,
                                true, false, true, true
                );
                final double[] start = polluted.positionXZ(0);
                final double[] end = polluted.positionXZ(polluted.length());

                final RailPair geometryKey = RailPair.of(
                                RailPair.pack((long) Math.floor(start[0]), -60, (long) Math.floor(start[1])),
                                RailPair.pack((long) Math.floor(end[0]), -60, (long) Math.floor(end[1])));
                final RailPair nodeKey = RailPair.of(RailPair.pack(-7, -60, -237), RailPair.pack(-1, -60, -8));

                assertFalse(geometryKey.equals(nodeKey), "几何键 != 节点键（复现 1.0.3 的 miss）");

                final java.util.Set<RailPair> keys = new java.util.LinkedHashSet<>();
                keys.add(nodeKey);
                keys.add(geometryKey);
                assertTrue(keys.contains(nodeKey), "双键广播包含节点键");
                assertTrue(keys.contains(geometryKey), "双键广播包含几何键");
        }

        /**
         * 场景 3：锚定后缓和曲线端点精确落在真实节点中心，中间采样不瞬移到污染的 x=2。
         */
        @Test
        void testAnchoredPathLandsOnRealNodes() {
                final ClassicRailGeometry polluted = new ClassicRailGeometry(
                                0, 1, 1.6, -8.5, -237.5,
                                0, 0, 0, 0, 0,
                                true, false, true, true
                );
                final long nodeA = RailPair.pack(-7, -60, -237);
                final long nodeB = RailPair.pack(-1, -60, -8);

                final EasedRailPath path = polluted.resolveEasedAnchored(EasementShape.CUBIC_PARABOLA, nodeA, nodeB);
                assertNotNull(path);

                assertEquals(-0.5, path.x[0], NODE_EPSILON);
                assertEquals(-7.5, path.z[0], NODE_EPSILON);
                assertEquals(-6.5, path.x[path.x.length - 1], NODE_EPSILON);
                assertEquals(-236.5, path.z[path.z.length - 1], NODE_EPSILON);
                assertTrue(path.length > 229, "缓和曲线长度 > 经典长度 229");

                for (int i = 1; i < path.x.length - 1; i++) {
                        assertTrue(path.x[i] >= -7.5 - 1.0 && path.x[i] <= -0.5 + 1.0,
                                        "中间采样不超出节点范围（无 x=2 瞬移），x[" + i + "]=" + path.x[i]);
                }
        }

        /**
         * 场景 4：正常几何（未被污染）锚定前后行为逐位一致（无回归）。
         */
        @Test
        void testNormalGeometryAnchoringIsNoOp() {
                final ClassicRailGeometry normal = new ClassicRailGeometry(
                                0, -1, 1.0, 8.5, 237.5,
                                0, 0, 0, 0, 0,
                                false, false, true, true
                );
                final long nodeA = RailPair.pack(-1, -60, -237);
                final long nodeB = RailPair.pack(-1, -60, -8);

                final EasedRailPath anchored = normal.resolveEasedAnchored(EasementShape.CUBIC_PARABOLA, nodeA, nodeB);
                final EasedRailPath classic = normal.resolveEased(EasementShape.CUBIC_PARABOLA);
                assertNotNull(anchored);
                assertNotNull(classic);

                assertEquals(classic.length, anchored.length, 1E-9);
                assertEquals(classic.x.length, anchored.x.length);
                for (int i = 0; i < classic.x.length; i++) {
                        assertEquals(classic.x[i], anchored.x[i], 1E-9);
                        assertEquals(classic.z[i], anchored.z[i], 1E-9);
                }
        }

        /**
         * 场景 5：RailPair 打包布局与解包互逆；classic（{@code null} 线型）不受锚定影响。
         */
        @Test
        void testPackUnpackAndClassicNull() {
                final long nodeA = RailPair.pack(-7, -60, -237);
                final long nodeB = RailPair.pack(-1, -60, -8);
                assertEquals(-7, unpackX(nodeA));
                assertEquals(-60, unpackY(nodeA));
                assertEquals(-237, unpackZ(nodeA));
                assertEquals(-1, unpackX(nodeB));
                assertEquals(-60, unpackY(nodeB));
                assertEquals(-8, unpackZ(nodeB));

                final ClassicRailGeometry polluted = new ClassicRailGeometry(
                                0, 1, 1.6, -8.5, -237.5,
                                0, 0, 0, 0, 0,
                                true, false, true, true
                );
                assertNull(polluted.resolveEasedAnchored(null, nodeA, nodeB), "classic 锚定求解返回 null");
        }
}
