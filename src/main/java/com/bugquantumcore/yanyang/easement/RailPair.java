package com.bugquantumcore.yanyang.easement;

import net.minecraft.core.BlockPos;

/**
 * 一条轨道连接的两端节点（方块坐标 + Y）经规范排序后的键。
 *
 * <p>正反向两条 {@code Rail} 实例（A→B 与 B→A）会得到相同的键，使线型数据能按
 * 「连接」而非「轨道实例」寻址。打包格式与 {@link BlockPos#asLong()} 一致，
 * 因此工具侧（持有真实 {@code BlockPos}）与几何复原侧（从经典分段字段反推节点坐标）
 * 产生完全相同的键。</p>
 */
public record RailPair(long a, long b) {

        /**
         * @param packedA 节点 A 的 {@link BlockPos#asLong()} 打包值
         * @param packedB 节点 B 的 {@link BlockPos#asLong()} 打包值
         * @return 规范排序（小值在前）后的连接键
         */
        public static RailPair of(long packedA, long packedB) {
                return packedA <= packedB ? new RailPair(packedA, packedB) : new RailPair(packedB, packedA);
        }

        /**
         * @see #of(long, long)
         */
        public static RailPair of(BlockPos posA, BlockPos posB) {
                return of(posA.asLong(), posB.asLong());
        }

        /**
         * 按节点方块坐标（可为负）与 Y 打包为与 {@link BlockPos#asLong()} 一致的 long。
         */
        public static long pack(long x, long y, long z) {
                return (x & 0x3FFFFFF) << 38 | (z & 0x3FFFFFF) << 12 | y & 0xFFF;
        }
}
