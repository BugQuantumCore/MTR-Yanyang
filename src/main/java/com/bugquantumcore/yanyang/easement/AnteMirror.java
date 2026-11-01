package com.bugquantumcore.yanyang.easement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 把线型数据镜像到 ANTE（MTRSteamLoco）的 {@code customConfigs}，使其 JS 脚本可读。
 *
 * <p>等价于 YMTR 3.6.2.2+ 自带的 {@code mtr.client.AnteRailCompat}，但以自有反射实现，
 * 因该类在 YMTR 3.4.4 ~ 3.6.1.2 上不存在。镜像目标（与 {@code AnteRailCompat} 相同）：</p>
 * <pre>
 * cn.zbx1425.mtrsteamloco.data.RailExtraSupplier
 *   ├── getCustomConfigs()        // 读取轨道的 ANTE 配置映射
 *   └── setCustomConfigs(Map)     // 写回
 * </pre>
 * <p>ANTE 未安装、接口缺失或调用失败时静默降级为无操作（镜像只是附加增强）。</p>
 */
public final class AnteMirror {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        private static final String SUPPLIER_CLASS = "cn.zbx1425.mtrsteamloco.data.RailExtraSupplier";

        private static volatile boolean resolved;
        private static Class<?> supplierClass;
        private static Method getCustomConfigs;
        private static Method setCustomConfigs;

        private AnteMirror() {
        }

        /**
         * 将 {@code data} 中的键值合并进轨道的 ANTE {@code customConfigs}（保留 ANTE 已有的
         * 其他键），随后的 {@code setCustomConfigs} 写回。
         *
         * @param rail 目标轨道
         * @param data 要镜像的键值对
         */
        @SuppressWarnings("unchecked")
        public static void setRailCustomConfigsMirror(Object rail, Map<String, String> data) {
                if (!resolve()) {
                        return;
                }
                try {
                        final Object supplier = supplierClass.cast(rail);
                        final Map<String, String> configs = (Map<String, String>) getCustomConfigs.invoke(supplier);
                        if (configs != null) {
                                configs.putAll(data);
                                setCustomConfigs.invoke(supplier, configs);
                        }
                } catch (Throwable t) {
                        LOGGER.debug("Failed to mirror rail shape into ANTE customConfigs", t);
                }
        }

        private static boolean resolve() {
                if (resolved) {
                        return supplierClass != null;
                }
                synchronized (AnteMirror.class) {
                        if (resolved) {
                                return supplierClass != null;
                        }
                        resolved = true;
                        try {
                                supplierClass = Class.forName(SUPPLIER_CLASS);
                                getCustomConfigs = supplierClass.getMethod("getCustomConfigs");
                                setCustomConfigs = supplierClass.getMethod("setCustomConfigs", Map.class);
                        } catch (ReflectiveOperationException e) {
                                supplierClass = null; // ANTE 未安装（常态），不做日志
                        }
                        return supplierClass != null;
                }
        }
}
