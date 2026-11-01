package com.bugquantumcore.yanyang.easement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 与 ANTE（MTRSteamLoco / NTE / ANTE 系列）的运行时兼容层，全部经反射实现，
 * ANTE 未安装、接口缺失或调用失败时静默降级为无操作。
 *
 * <p>两处接入：</p>
 * <ul>
 * <li><b>customConfigs 镜像</b> —— 把线型数据写入轨道的 ANTE
 * {@code customConfigs}，使其 JS 脚本可读。等价于 YMTR 3.6.2.2+ 自带的
 * {@code mtr.client.AnteRailCompat}，但以自有反射实现（该类在更早版本上不存在）。
 * 镜像目标（与 {@code AnteRailCompat} 相同）：
 * <pre>
 * cn.zbx1425.mtrsteamloco.data.RailExtraSupplier
 *   ├── getCustomConfigs()        // 读取轨道的 ANTE 配置映射
 *   └── setCustomConfigs(Map)     // 写回
 * </pre></li>
 * <li><b>烘焙轨道重建</b> —— ANTE 把轨道渲染为按 {@code Rail} 实例缓存的烘焙网格
 * （{@code BakedRail}，按 {@code rail.getPosition}/{@code getLength} 采样模型摆放）。
 * 缓存仅在轨道实例增减时重建，因此本补丁在线型变化（镜像更新）后需要主动调用
 * {@code MainClient.railRenderDispatcher.clearRail()} 让其下一帧用新几何重建，
 * 否则轨道外观不随线型更新（已安装 ANTE 时「改变未生效」的根因）。</li>
 * </ul>
 */
public final class AnteMirror {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        private static final String SUPPLIER_CLASS = "cn.zbx1425.mtrsteamloco.data.RailExtraSupplier";

        private static final String MAIN_CLIENT_CLASS = "cn.zbx1425.mtrsteamloco.MainClient";

        private static volatile boolean resolved;
        private static Class<?> supplierClass;
        private static Method getCustomConfigs;
        private static Method setCustomConfigs;

        /**
         * ANTE 烘焙轨道清理（{@code MainClient.railRenderDispatcher.clearRail()}）的反射句柄；
         * {@code null} = ANTE 未安装或结构未知。必须在客户端主线程（渲染线程）调用。
         */
        private static volatile Object railRenderDispatcher;
        private static volatile Method clearBakedRails;
        private static volatile boolean clearBakedRailsResolved;

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

        /**
         * 清空 ANTE 的烘焙轨道缓存，使其在下一帧按当前（可能是缓和后的）轨道几何重建。
         * 必须在客户端主线程调用；ANTE 未安装时为无操作。失败时静默降级并只记一次日志
         * （视觉重建失败不影响几何本身，列车仍沿正确路径运行）。
         */
        public static void clearBakedRails() {
                if (!resolveClearBakedRails()) {
                        return;
                }
                try {
                        clearBakedRails.invoke(railRenderDispatcher);
                } catch (Throwable t) {
                        LOGGER.debug("Failed to ask ANTE to rebuild baked rails", t);
                }
        }

        private static boolean resolveClearBakedRails() {
                if (clearBakedRailsResolved) {
                        return clearBakedRails != null;
                }
                synchronized (AnteMirror.class) {
                        if (clearBakedRailsResolved) {
                                return clearBakedRails != null;
                        }
                        clearBakedRailsResolved = true;
                        try {
                                final Class<?> mainClient = Class.forName(MAIN_CLIENT_CLASS);
                                final Object dispatcher = mainClient.getField("railRenderDispatcher").get(null);
                                clearBakedRails = dispatcher.getClass().getMethod("clearRail");
                        } catch (ReflectiveOperationException e) {
                                clearBakedRails = null; // ANTE 未安装或结构变化（常态）
                        }
                        return clearBakedRails != null;
                }
        }
}
