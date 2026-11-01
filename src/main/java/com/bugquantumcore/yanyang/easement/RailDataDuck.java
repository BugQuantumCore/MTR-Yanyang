package com.bugquantumcore.yanyang.easement;

import java.util.Map;

/**
 * 由 {@code RailDataCarrierMixin}（仅在具备 {@code railData} 机制的 YMTR 3.6.2.2+ 上应用）
 * 实现的轨道附加数据访问接口（对 {@code mtr.data.Rail} 的鸭子类型）。
 *
 * <p>核心 {@code RailMixin} 通过 {@code instanceof RailDataDuck} 判断当前轨道是否携带
 * 附加数据机制：该 Mixin 未应用（legacy YMTR）时 {@code instanceof} 恒为 {@code false}，
 * 线型解析自动落到 legacy 印记路径，无需任何版本分支代码。</p>
 */
public interface RailDataDuck {

        /**
         * @return 该轨道的附加数据（{@code railData}）映射；可能为 {@code null}
         */
        Map<String, String> yanyang$getRailData();
}
