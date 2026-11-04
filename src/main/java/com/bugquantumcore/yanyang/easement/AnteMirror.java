package com.bugquantumcore.yanyang.easement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 与 ANTE（MTRSteamLoco / NTE / ANTE 系列，含 MSKmetromine 的 MTR-ANTE 社区分支）的
 * 运行时兼容层，全部经反射实现，ANTE 未安装、接口缺失或调用失败时静默降级为无操作。
 *
 * <p>三处接入：</p>
 * <ul>
 * <li><b>customConfigs 镜像</b> —— 把线型数据写入轨道的 ANTE
 * {@code customConfigs}，使其 JS 脚本与其轨道配置编辑器可读。等价于 YMTR 3.6.2.2+
 * 自带的 {@code mtr.client.AnteRailCompat}，但以自有反射实现（该类在更早版本上不存在）。
 * 镜像目标（与 {@code AnteRailCompat} 相同）：
 * <pre>
 * [fabric.]cn.zbx1425.mtrsteamloco.data.RailExtraSupplier   // v1.0.4：Forgix 前缀优先
 *   ├── getCustomConfigs()        // 读取轨道的 ANTE 配置映射
 *   └── setCustomConfigs(Map)     // 写回
 * </pre></li>
 * <li><b>customConfigs 回读</b> —— MTR-ANTE 社区分支的「轨道配置」编辑器直接修改
 * {@code customConfigs}（键值随 {@code PacketUpdateRail} 与轨道网络包往返同步），并且
 * 该分支用新轨道实例替换被编辑的轨道（legacy 印记随旧实例丢失）。因此线型解析时也从
 * {@code customConfigs} 的 {@code rail_shape} 键回读，使「在 ANTE 编辑器里设置
 * rail_shape」与「用本补丁的线型工具」两条路径完全等价，且编辑器保存后线型不丢。</li>
 * <li><b>烘焙轨道重建</b> —— ANTE 把轨道渲染为按 {@code Rail} 实例缓存的烘焙网格
 * （{@code BakedRail}，按 {@code rail.getPosition}/{@code getLength} 采样模型摆放）。
 * 缓存仅在轨道实例增减时重建，因此本补丁在线型变化（镜像更新）后需要主动调用
 * {@code MainClient.railRenderDispatcher.clearRail()} 让其下一帧用新几何重建，
 * 否则轨道外观不随线型更新。</li>
 * </ul>
 *
 * <p><b>诊断</b>：接入点的解析结果只在首次（或失败后的每次状态翻转）以 INFO/WARN 记录
 * ——ANTE 集成是否生效直接影响「线型改变是否可见」，问题排查需要这些日志。</p>
 */
public final class AnteMirror {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * v1.0.4：Aphrodite MTR-ANTE / NTE 社区分发版使用 Forgix 合并的多 loader jar，
         * Fabric 侧运行时类名带 {@code fabric.} 前缀（mixins.json 的 package 即为
         * {@code fabric.cn.zbx1425.mtrsteamloco.mixin}）。反射必须同时尝试两种类名，
         * 否则 {@code Class.forName} 静默失败，customConfigs 镜像 / rail_shape 回读 /
         * clearRail 重烘全部变成 no-op —— 这正是 1.0.3 线型不生效的第二个根因。
         */
        private static final String[] SUPPLIER_CLASS_CANDIDATES = {
                        "fabric.cn.zbx1425.mtrsteamloco.data.RailExtraSupplier",
                        "cn.zbx1425.mtrsteamloco.data.RailExtraSupplier",
        };

        private static final String[] MAIN_CLIENT_CLASS_CANDIDATES = {
                        "fabric.cn.zbx1425.mtrsteamloco.MainClient",
                        "cn.zbx1425.mtrsteamloco.MainClient",
        };

        /**
         * ANTE 烘焙轨道清理（{@code MainClient.railRenderDispatcher.clearRail()}）的反射句柄；
         * {@code null} = ANTE 未安装或结构未知。必须在客户端主线程（渲染线程）调用。
         */
        private static volatile Object railRenderDispatcher;
        private static volatile Method clearBakedRails;
        private static volatile boolean clearBakedRailsResolved;
        /** 烘焙清理连续失败计数：仅前几次记 WARN，避免刷屏。 */
        private static int clearBakedRailsFailures;

        private static volatile boolean resolved;
        private static Class<?> supplierClass;
        private static Method getCustomConfigs;
        private static Method setCustomConfigs;
        /** customConfigs 是否可用（决定回读路径是否有意义）。 */
        private static volatile boolean supplierLogged;

        private AnteMirror() {
        }

        /**
         * 把 {@code data} 中的键值合并进轨道的 ANTE {@code customConfigs}（保留 ANTE 已有的
         * 其他键），随后 {@code setCustomConfigs} 写回。<b>值为 {@code null} 的条目表示删除
         * 该键</b> —— 清除线型时必须从 customConfigs 一并移除，否则回读路径会把旧线型
         * 「复活」。
         *
         * @param rail 目标轨道
         * @param data 要镜像的键值对（值为 {@code null} = 删除该键）
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
                                for (final Map.Entry<String, String> entry : data.entrySet()) {
                                        if (entry.getValue() == null) {
                                                configs.remove(entry.getKey());
                                        } else {
                                                configs.put(entry.getKey(), entry.getValue());
                                        }
                                }
                                setCustomConfigs.invoke(supplier, configs);
                        }
                } catch (Throwable t) {
                        LOGGER.debug("Failed to mirror rail shape into ANTE customConfigs", t);
                }
        }

        /**
         * 从轨道的 ANTE {@code customConfigs} 回读线型键（键 {@code rail_shape}）。
         * ANTE 未安装、轨道未实现该接口或读取失败时返回 {@code null}。
         *
         * @param rail 目标轨道
         * @return 线型键（如 {@code cubic_parabola}）；无记录时 {@code null}
         */
        public static String readRailShapeKey(Object rail) {
                if (!resolve()) {
                        return null;
                }
                try {
                        final Object supplier = supplierClass.cast(rail);
                        final Map<?, ?> configs = (Map<?, ?>) getCustomConfigs.invoke(supplier);
                        if (configs == null) {
                                return null;
                        }
                        final Object value = configs.get(EasementShape.RAIL_DATA_KEY);
                        return value instanceof String key ? key : null;
                } catch (Throwable t) {
                        LOGGER.debug("Failed to read rail shape from ANTE customConfigs", t);
                        return null;
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
                        // v1.0.4：按 Forgix 前缀优先、无前缀兑底的顺序尝试候选类名。
                        for (final String candidate : SUPPLIER_CLASS_CANDIDATES) {
                                try {
                                        final Class<?> cls = Class.forName(candidate);
                                        getCustomConfigs = cls.getMethod("getCustomConfigs");
                                        setCustomConfigs = cls.getMethod("setCustomConfigs", Map.class);
                                        supplierClass = cls;
                                        break;
                                } catch (Throwable t) {
                                        // v1.0.4：捕获 Throwable —— 类存在但链接失败（LinkageError）也不能让施印/同步流程崩溃
                                }
                        }
                        if (!supplierLogged) {
                                supplierLogged = true;
                                if (supplierClass != null) {
                                        LOGGER.info("ANTE integration: RailExtraSupplier resolved as {} (customConfigs mirror + rail_shape read-back active)", supplierClass.getName());
                                } else {
                                        // v1.0.4：不再静默。无 ANTE 时这是正常情况（INFO 级提示），
                                        // 有 ANTE 却解析失败时用户需要知道镜像通路断了。
                                        LOGGER.info("ANTE integration: RailExtraSupplier not found (tried {}); customConfigs mirror inactive. If an ANTE/NTE build is installed, please report this.", String.join(", ", SUPPLIER_CLASS_CANDIDATES));
                                }
                        }
                        return supplierClass != null;
                }
        }

        /**
         * 清空 ANTE 的烘焙轨道缓存，使其在下一帧按当前（可能是缓和后的）轨道几何重建。
         * 必须在客户端主线程调用；ANTE 未安装时为无操作。失败时记 WARN（前几次），
         * 视觉重建失败不影响几何本身，列车仍沿正确路径运行。
         */
        public static void clearBakedRails() {
                if (!resolveClearBakedRails()) {
                        return;
                }
                try {
                        clearBakedRails.invoke(railRenderDispatcher);
                } catch (Throwable t) {
                        if (clearBakedRailsFailures < 3) {
                                LOGGER.warn("Failed to ask ANTE to rebuild baked rails (visual update may lag until a rail instance changes)", t);
                        }
                        clearBakedRailsFailures++;
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
                        // v1.0.4：同样按 Forgix 前缀优先的双候选解析。
                        for (final String candidate : MAIN_CLIENT_CLASS_CANDIDATES) {
                                try {
                                        final Class<?> mainClient = Class.forName(candidate);
                                        final Object dispatcher = mainClient.getField("railRenderDispatcher").get(null);
                                        clearBakedRails = dispatcher.getClass().getMethod("clearRail");
                                        railRenderDispatcher = dispatcher;
                                        LOGGER.info("ANTE integration: {}.railRenderDispatcher.clearRail resolved (baked rail rebuild available)", mainClient.getName());
                                        break;
                                } catch (Throwable t) {
                                        // v1.0.4：捕获 Throwable —— 链接失败也不能让同步流程崩溃
                                }
                        }
                        if (clearBakedRails == null) {
                                LOGGER.info("ANTE integration: MainClient/railRenderDispatcher not found (tried {}); baked rail rebuild inactive. If an ANTE/NTE build is installed, please report this.", String.join(", ", MAIN_CLIENT_CLASS_CANDIDATES));
                        }
                        return clearBakedRails != null;
                }
        }
}
