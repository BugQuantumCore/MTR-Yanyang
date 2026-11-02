package com.bugquantumcore.yanyang.easement.client;

import com.bugquantumcore.yanyang.easement.AnteMirror;
import com.bugquantumcore.yanyang.easement.RailPair;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口（legacy YMTR 3.4.4 ~ 3.6.1.2 的客户端同步）。
 *
 * <p>注册 {@code yanyang_easement:shape_sync} 的 S2C 接收器，把服务端发来的线型真值
 * 写入客户端镜像（{@link ShapeStore}），并注入「当前客户端维度」供给 —— 两者共同让
 * 客户端轨道（由 YMTR 网络包重建、不携带印记）能按「维度 × 连接键」解析线型。</p>
 *
 * <p><b>渲染缓存失效</b>：YMTR 原生渲染每帧调用 {@code Rail.render}，镜像更新后线型
 * 立即生效；但 ANTE（MTRSteamLoco）把轨道烘焙为按 {@code Rail} 实例缓存的网格，
 * 缓存只在轨道实例增减时重建 —— 线型变化不替换实例，烘焙网格会停留在经典线型。
 * 因此每客户端 tick 检查镜像脏标志（{@link ShapeStore#consumeClientDirty}，同 tick
 * 内多次更新合并为一次），变化时让 ANTE 重建烘焙轨道（{@link AnteMirror#clearBakedRails}，
 * ANTE 未安装时为无操作）。</p>
 *
 * <p><b>诊断</b>：收到的同步包、触发的烘焙重建与周期性的线型解析报告都记 INFO 日志
 * —— 线型是否生效的每一环（包到达 → 镜像写入 → 烘焙重建 → 几何解析）都可从日志核对。</p>
 */
@Environment(EnvType.CLIENT)
public class YanyangEasementModClient implements ClientModInitializer {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * 诊断报告间隔（客户端 tick，20 tps ≈ 20 秒）。
         */
        private static final int REPORT_INTERVAL_TICKS = 400;

        private int reportTicks;

        @Override
        public void onInitializeClient() {
                ShapeStore.setClientDimensionSupplier(() -> {
                        final Minecraft client = Minecraft.getInstance();
                        return client.level == null ? null : client.level.dimension().location();
                });

                ClientPlayNetworking.registerGlobalReceiver(ShapeStore.SHAPE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
                        // 接收器在网络线程执行：先读取全部载荷，再跳到客户端线程写镜像。
                        final ResourceLocation dimension = buf.readResourceLocation();
                        final long a = buf.readLong();
                        final long b = buf.readLong();
                        final String shapeKey = buf.readUtf();
                        LOGGER.info("Received shape sync: {} for {} in {}", shapeKey.isEmpty() ? "(classic)" : shapeKey, RailPair.of(a, b), dimension);
                        client.execute(() -> ShapeStore.clientPut(dimension, RailPair.of(a, b), shapeKey.isEmpty() ? null : shapeKey));
                });

                // 客户端 tick（渲染线程）：镜像有实际变化时让 ANTE 重建烘焙轨道；
                // 并周期性报告线型解析统计（无新活动时不输出）。
                ClientTickEvents.END_CLIENT_TICK.register(client -> {
                        if (ShapeStore.consumeClientDirty()) {
                                LOGGER.info("Client rail shapes changed; asking ANTE to rebuild baked rails");
                                AnteMirror.clearBakedRails();
                        }
                        if (++reportTicks >= REPORT_INTERVAL_TICKS) {
                                reportTicks = 0;
                                final String report = ShapeStore.collectReport();
                                if (report != null) {
                                        LOGGER.info("[client] {}", report);
                                }
                        }
                });
        }
}
