package com.bugquantumcore.yanyang.easement;

import java.util.Locale;
import java.util.Map;

/**
 * 本补丁提供的两种水平缓和曲线线型，对应 MTR-Yanyang {@code tsc-1.21.x} 分支中
 * {@code Rail.Shape} 枚举新增的 {@code CUBIC_PARABOLA} 与 {@code HALF_WAVE_SINE}。
 *
 * <p>MTR 3.x 的 {@code Rail} 没有独立的线型字段，本补丁把所选线型作为键值对存进
 * YMTR 的轨道附加数据（{@code railData}，随存档持久化、随网络同步、可在游戏内的
 * 「轨道数据编辑器」中查看），因此<b>不改动 MTR 的任何序列化格式</b>：</p>
 * <pre>
 * 键：rail_shape
 * 值：cubic_parabola | half_wave_sine     （删除该键或填写其他值 = 经典线型）
 * </pre>
 */
public enum EasementShape {

        /**
         * 三次抛物线缓和曲线：分段线性曲率（直—缓—圆—缓—直）。
         */
        CUBIC_PARABOLA("cubic_parabola"),

        /**
         * 半波正弦缓和曲线：以半波正弦为曲率图，曲率处处光滑。
         */
        HALF_WAVE_SINE("half_wave_sine");

        /**
         * 轨道附加数据中标记所选线型的键名。
         */
        public static final String RAIL_DATA_KEY = "rail_shape";

        /**
         * YMTR 轨道数据编辑器为每个键额外保存的类型标记键（{@code __type__<键名>}）。
         * 本补丁写入线型时一并维护该标记，使编辑器的类型列正确显示 string。
         */
        public static final String RAIL_DATA_TYPE_PREFIX = "__type__";

        private final String key;

        EasementShape(String key) {
                this.key = key;
        }

        /**
         * @return 写入 {@code railData} 的标准值（小写下划线形式）
         */
        public String key() {
                return key;
        }

        /**
         * @return 该线型的语言文件键
         */
        public String translationKey() {
                return "easement.yanyang_easement." + key;
        }

        /**
         * 线型循环：经典（{@code null}）→ 三次抛物线 → 半波正弦 → 经典……
         *
         * @param current   当前线型，{@code null} 表示经典线型
         * @param backwards {@code true} 时反向循环
         * @return 下一个线型，{@code null} 表示回到经典线型
         */
        public static EasementShape next(EasementShape current, boolean backwards) {
                if (backwards) {
                        return current == null ? HALF_WAVE_SINE : current == CUBIC_PARABOLA ? null : CUBIC_PARABOLA;
                }
                return current == null ? CUBIC_PARABOLA : current == CUBIC_PARABOLA ? HALF_WAVE_SINE : null;
        }

        /**
         * 从轨道附加数据解析线型。
         *
         * @return 选中的线型；未设置或无法识别时返回 {@code null}（经典线型）
         */
        public static EasementShape fromRailData(Map<String, String> railData) {
                if (railData == null || railData.isEmpty()) {
                        return null;
                }
                final String value = railData.get(RAIL_DATA_KEY);
                if (value == null) {
                        return null;
                }
                final String normalized = value.trim().toLowerCase(Locale.ROOT);
                for (final EasementShape shape : values()) {
                        if (shape.key.equals(normalized)) {
                                return shape;
                        }
                }
                return null;
        }
}
