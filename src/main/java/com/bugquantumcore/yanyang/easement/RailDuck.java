package com.bugquantumcore.yanyang.easement;

/**
 * 由 {@code RailMixin} 实现的轨道线型印记访问接口（对 {@code mtr.data.Rail} 的鸭子类型）。
 *
 * <p>「印记」是 legacy YMTR（3.4.4 ~ 3.6.1.2，无 {@code railData} 机制）下线型的
 * 服务端载体：一个可空的线型键字符串，存放在轨道实例的 Mixin 附加字段上，由</p>
 * <ul>
 * <li>线型工具（{@code EasementShapeApplier}）在服务端写入；</li>
 * <li>{@code RailPersistenceMixin} 在存档反序列化（{@code Rail(Map)} 构造器）时读回
 * （存档侧由 {@code toMessagePack} 搭载写入，见 {@link ShapeStore#LEGACY_KEY}）。</li>
 * </ul>
 * <p>其他 Mixin（如持久化 Mixin）通过本接口读写印记，避免跨 Mixin {@code @Shadow}
 * 附加字段（不支持）。</p>
 */
public interface RailDuck {

        /**
         * @return 该轨道实例当前的线型印记；{@code null} 表示经典线型
         */
        String yanyang$getShapeStamp();

        /**
         * 写入线型印记并立即失效缓存的缓和路径（工具切换线型时使用）。
         *
         * @param shapeKey 线型键（{@code cubic_parabola} / {@code half_wave_sine}）；
         *                 {@code null} 表示经典线型
         */
        void yanyang$setShapeStamp(String shapeKey);

        /**
         * v1.0.4：把「从经典几何推导连接键」的逻辑暴露给服务器侧广播代码使用。
         *
         * <p>服务器与客户端用完全相同的推导（{@link ClassicRailGeometry} + floor +
         * {@link RailPair#pack}），因此即便 ANTE 编辑过的轨道几何无法复原出真实
         * 节点坐标（MTR 公式推导出错位的端点），两端推导结果仍然一致 ——
         * 镜像按「几何键」即可命中。</p>
         *
         * @return 按本轨道经典几何字段推导出的连接键
         */
        RailPair yanyang$pairKey();
}
