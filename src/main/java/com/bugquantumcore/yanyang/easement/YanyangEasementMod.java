package com.bugquantumcore.yanyang.easement;

import com.bugquantumcore.yanyang.easement.item.ItemEasementShapeTool;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import mtr.CreativeModeTabs;

/**
 * 晏阳缓和曲线补丁（MTR 3.x 版）入口。
 *
 * <p>将 MTR-Yanyang {@code tsc-1.21.x} 分支为 MTR 4.1.x 开发的两种轨道路径算法
 * —— 三次抛物线缓和曲线与半波正弦缓和曲线 —— 以运行时补丁（Mixin）的形式移植到
 * MTR 3.x（YMTR 3.4.4 ~ latest）。核心算法见 {@link EasedRailPath}，注入点见
 * {@code mixin.RailMixin}，版本兼容层见 {@link YanyangCompat}，存档自愈见
 * {@link SaveFileSweeper}。</p>
 */
public class YanyangEasementMod implements ModInitializer {

        public static final String MOD_ID = "yanyang_easement";

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * 诊断报告间隔（服务端 tick，20 tps ≈ 20 秒）。
         */
        private static final int REPORT_INTERVAL_TICKS = 400;

        public static final Item EASEMENT_SHAPE_TOOL = new ItemEasementShapeTool(new Item.Properties().stacksTo(1));

        @Override
        public void onInitialize() {
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MOD_ID, "easement_shape_tool"), EASEMENT_SHAPE_TOOL);

                // 加入 YMTR 的 MTR 核心创造模式物品栏（与轨道修改器等物品同栏）。
                final CreativeModeTab coreTab = CreativeModeTabs.CORE.get();
                final ResourceLocation coreTabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(coreTab);
                final ResourceKey<CreativeModeTab> coreTabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, coreTabId);
                ItemGroupEvents.modifyEntriesEvent(coreTabKey).register(entries -> entries.accept(EASEMENT_SHAPE_TOOL));

                // 存档自愈：在 RailwayData 首次加载前校验 / 修复 / 隔离损坏的 msgpack 数据文件
                // （防止单个截断文件把整份 mtr_train_data 替换成空白状态）。
                SaveFileSweeper.register();

                // legacy YMTR 的客户端线型同步：玩家加入与切换维度后全量推送当前维度的印记。
                ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ShapeStore.syncPlayerShapes(handler.getPlayer()));
                ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, originWorld, destinationWorld) -> ShapeStore.syncPlayerShapes(player));

                // v1.0.3：服务器停止时清空形状记忆（下次启动按存档重建，避免跨存档残留）。
                ServerLifecycleEvents.SERVER_STOPPED.register(server -> ShapeStore.clearServerShapeMemory());

                // 服务端周期诊断报告（线型解析统计；无新活动时不输出）。
                ServerTickEvents.END_SERVER_TICK.register(YanyangEasementMod::tickReport);

                LOGGER.info("Yanyang easement initialized: YMTR mode = {}, MTR version = {}",
                                YanyangCompat.RAILDATA_CAPABLE ? "railData-capable (3.6.2.2+)" : "legacy stamp (3.4.4 ~ 3.6.1.2)",
                                net.fabricmc.loader.api.FabricLoader.getInstance()
                                                .getModContainer("mtr")
                                                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                                                .orElse("(mtr not found)"));
        }

        private static void tickReport(MinecraftServer server) {
                // 静态计数 + 静态间隔：多个世界实例共享诊断聚合，此处只是报告泵。
                if (++reportTicks >= REPORT_INTERVAL_TICKS) {
                        reportTicks = 0;
                        final String report = ShapeStore.collectReport();
                        if (report != null) {
                                LOGGER.info("[server] {}", report);
                        }
                }
        }

        private static int reportTicks;
}
