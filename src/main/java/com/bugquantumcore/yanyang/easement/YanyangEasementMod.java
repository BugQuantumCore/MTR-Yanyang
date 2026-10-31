package com.bugquantumcore.yanyang.easement;

import com.bugquantumcore.yanyang.easement.item.ItemEasementShapeTool;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import mtr.CreativeModeTabs;

/**
 * 晏阳缓和曲线补丁（MTR 3.x 版）入口。
 *
 * <p>将 MTR-Yanyang {@code tsc-1.21.x} 分支为 MTR 4.1.x 开发的两种轨道路径算法
 * —— 三次抛物线缓和曲线与半波正弦缓和曲线 —— 以运行时补丁（Mixin）的形式移植到
 * MTR 3.x（YMTR）。核心算法见 {@link EasedRailPath}，注入点见
 * {@code mixin.RailMixin}。</p>
 */
public class YanyangEasementMod implements ModInitializer {

        public static final String MOD_ID = "yanyang_easement";

        public static final Item EASEMENT_SHAPE_TOOL = new ItemEasementShapeTool(new Item.Properties().stacksTo(1));

        @Override
        public void onInitialize() {
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MOD_ID, "easement_shape_tool"), EASEMENT_SHAPE_TOOL);

                // 加入 YMTR 的 MTR 核心创造模式物品栏（与轨道修改器等物品同栏）。
                final CreativeModeTab coreTab = CreativeModeTabs.CORE.get();
                final ResourceLocation coreTabId = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(coreTab);
                final ResourceKey<CreativeModeTab> coreTabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, coreTabId);
                ItemGroupEvents.modifyEntriesEvent(coreTabKey).register(entries -> entries.accept(EASEMENT_SHAPE_TOOL));
        }
}
