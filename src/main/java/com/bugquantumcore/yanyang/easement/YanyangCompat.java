package com.bugquantumcore.yanyang.easement;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Optional;

/**
 * YMTR（MTR 3.x）版本能力探测。
 *
 * <p>本补丁支持的 YMTR 版本范围为 <b>3.4.4 ~ latest（1.20.1 线）</b>。这个范围内的 YMTR
 * 存在两种轨道附加数据架构，补丁需要在运行时选择对应的存储与同步路径：</p>
 *
 * <table border="1">
 * <tr><th></th><th>3.4.4 ~ 3.6.1.2（legacy）</th><th>3.6.2.2+（capable）</th></tr>
 * <tr><td>轨道附加数据 {@code railData}</td><td>不存在</td><td>存在</td></tr>
 * <tr><td>线型存储</td><td>轨道实例印记 + 存档 messagepack 搭载</td><td>{@code railData} 键值对</td></tr>
 * <tr><td>客户端同步</td><td>本补丁自定义 S2C 包</td><td>YMTR 原生 {@code createRailS2C}</td></tr>
 * <tr><td>轨道数据编辑器可见</td><td>否（该功能不存在）</td><td>是（键 {@code rail_shape}）</td></tr>
 * </table>
 *
 * <p>判定依据为 Fabric 模组容器中 {@code mtr} 的版本号（形如 {@code 1.20.1-3.6.3}，
 * 取连字符后的 YMTR 版本号与 {@code 3.6.2.2} 做数值比较）。<b>刻意不使用反射探测
 * {@code Rail} 类</b>——Mixin 插件在类转换阶段就会调用本类，此时强制加载
 * {@code mtr.data.Rail} 会导致该类在 Mixin 应用完成前被定义，注入全部失效。</p>
 *
 * <p>版本号无法解析时按 legacy 处理：legacy 路径只依赖两个版本都稳定的几何字段，
 * 是更保守的回退方向。</p>
 */
public final class YanyangCompat {

        /**
         * 引入 {@code Rail.railData} 的最低 YMTR 版本（含）。
         */
        private static final int[] RAILDATA_MIN_VERSION = {3, 6, 2, 2};

        /**
         * {@code true} 表示当前 YMTR 具备轨道附加数据（{@code railData}）机制。
         */
        public static final boolean RAILDATA_CAPABLE = detect();

        private YanyangCompat() {
        }

        private static boolean detect() {
                final Optional<String> version = FabricLoader.getInstance()
                                .getModContainer("mtr")
                                .map(container -> container.getMetadata().getVersion().getFriendlyString());
                if (version.isEmpty()) {
                        return false;
                }
                final int[] parts = parseYmtrVersion(version.get());
                if (parts == null) {
                        return false;
                }
                return compare(parts, RAILDATA_MIN_VERSION) >= 0;
        }

        /**
         * 从形如 {@code 1.20.1-3.6.3}、{@code 3.6.3}、{@code 1.19.2-3.6.2} 的版本字符串中
         * 解析出 YMTR 版本号的数值分段（{@code [3, 6, 3]} 等）。
         *
         * @return 数值分段；无法解析时返回 {@code null}
         */
        private static int[] parseYmtrVersion(String raw) {
                if (raw == null || raw.isEmpty()) {
                        return null;
                }
                // 取最后一个连字符后的部分（剥掉 Minecraft 版本前缀）。
                String tail = raw;
                final int dash = raw.lastIndexOf('-');
                if (dash >= 0) {
                        tail = raw.substring(dash + 1);
                }
                // 只保留数字与点。
                if (!tail.matches("[0-9]+(\\.[0-9]+)*")) {
                        return null;
                }
                final String[] tokens = tail.split("\\.");
                final int[] parts = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                        try {
                                parts[i] = Integer.parseInt(tokens[i]);
                        } catch (NumberFormatException e) {
                                return null;
                        }
                }
                return parts;
        }

        /**
         * 数值分段字典序比较（缺省分段按 0 计）。
         */
        private static int compare(int[] a, int[] b) {
                final int length = Math.max(a.length, b.length);
                for (int i = 0; i < length; i++) {
                        final int x = i < a.length ? a[i] : 0;
                        final int y = i < b.length ? b[i] : 0;
                        if (x != y) {
                                return Integer.compare(x, y);
                        }
                }
                return 0;
        }
}
