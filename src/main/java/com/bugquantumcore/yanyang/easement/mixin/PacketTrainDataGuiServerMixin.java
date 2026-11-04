package com.bugquantumcore.yanyang.easement.mixin;

import com.bugquantumcore.yanyang.easement.AnteMirror;
import com.bugquantumcore.yanyang.easement.EasementShape;
import com.bugquantumcore.yanyang.easement.RailDuck;
import com.bugquantumcore.yanyang.easement.RailPair;
import com.bugquantumcore.yanyang.easement.ShapeStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.data.TransportMode;
import mtr.packet.PacketTrainDataGuiServer;

import java.util.HashMap;
import java.util.Map;

/**
 * 轨道广播的真值同步（<b>仅 legacy YMTR 3.4.4 ~ 3.6.1.2</b>，由
 * {@code YanyangMixinPlugin} 按能力加载）。
 *
 * <p>YMTR 在轨道新建 / 替换 / 修改时都会调用 {@code createRailS2C} 广播该连接的最新
 * 轨道。legacy 版本的网络载荷不含线型印记，客户端镜像可能残留旧值 —— 本 Mixin 在
 * 广播末尾把该连接的服务端真值一并发给客户端（v1.0.4 起经
 * {@link ShapeStore#broadcastShape} 多键广播，见其注释），并在真值解析成功后
 * <b>恢复</b>（v1.0.3）：</p>
 * <ol>
 * <li>解析顺序：rail1 / rail2 实例印记 → 两者 ANTE {@code customConfigs} 的
 * {@code rail_shape}（{@code PacketUpdateRail} 用新实例替换轨道后印记丢失，但
 * {@code customConfigs} 随网络包往返保留）→ 服务器形状记忆（MTR GUI 重连等
 * 全新对象场景）；</li>
 * <li>{@code reapplyShape} 把解析出的印记 + ANTE 镜像重新套回参与本次广播的对象
 * 以及 {@code rails} 表中当前存放的同连接对象（防御调用方传副本的情况）——
 * 覆盖刷子编辑、GUI 重连、直接在 ANTE 配置界面手填 {@code rail_shape} 的用法；</li>
 * <li>显式回退经典（工具循环到 classic）时记忆记录为空串，不会被误恢复；</li>
 * <li>解析结果（含空串 = 明确经典）写入服务器形状记忆，供后续重连恢复。</li>
 * </ol>
 *
 * <p>{@code createRailS2C} 在 YMTR 3.4.4 ~ 3.6.3 上签名完全一致且无重载，按方法名
 * 匹配即可；处理器参数会被 loom 正常重映射。</p>
 */
@Mixin(PacketTrainDataGuiServer.class)
public abstract class PacketTrainDataGuiServerMixin {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        @Inject(method = "createRailS2C", at = @At("TAIL"), remap = false)
        private static void yanyang$syncShapeTruth(Level level, TransportMode transportMode, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, long ridingCarNumber, CallbackInfo ci) {
                try {
                        // v1.0.4：把参与同步的两个 Rail 对象一并传入，让广播同时覆盖
                        // 「节点键」和「几何推导键」—— 几何被 ANTE 编辑污染的轨道也能命中。
                        ShapeStore.broadcastShape(level, pos1, pos2, effectiveShapeKey(level, pos1, pos2, rail1, rail2), rail1, rail2);
                } catch (Throwable t) {
                        LOGGER.error("Failed to sync rail shape truth for {} <-> {}", pos1, pos2, t);
                }
        }

        /**
         * 解析一条连接的有效线型（印记 → ANTE {@code customConfigs}（仅认可识别的
         * 线型键）→ 服务器形状记忆），解析成功后把印记 + ANTE 镜像套回相关对象，
         * 并把结果（含空串 = 明确经典）写入服务器形状记忆。
         */
        private static String effectiveShapeKey(Level level, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2) {
                String shapeKey = ((RailDuck) rail1).yanyang$getShapeStamp();
                if (shapeKey == null) {
                        shapeKey = ((RailDuck) rail2).yanyang$getShapeStamp();
                }
                if (shapeKey == null) {
                        shapeKey = sanitized(AnteMirror.readRailShapeKey(rail1));
                }
                if (shapeKey == null) {
                        shapeKey = sanitized(AnteMirror.readRailShapeKey(rail2));
                }
                if (shapeKey == null) {
                        final String remembered = ShapeStore.recallServerShape(level.dimension().location(), RailPair.of(pos1, pos2));
                        if (remembered != null && !remembered.isEmpty()) {
                                shapeKey = remembered;
                        }
                }

                if (shapeKey != null) {
                        reapplyShape(level, pos1, pos2, rail1, rail2, shapeKey);
                }

                ShapeStore.rememberServerShape(level.dimension().location(), RailPair.of(pos1, pos2), shapeKey);
                return shapeKey;
        }

        /** 仅接受可识别的线型键，避免编辑器残留的脏值复活成假线型。 */
        private static String sanitized(String shapeKey) {
                return shapeKey != null && EasementShape.fromKey(shapeKey) != null ? shapeKey : null;
        }

        /**
         * v1.0.3：把解析出的线型套回参与本次 {@code createRailS2C} 的对象与
         * {@code rails} 表中当前存放的同连接对象（GUI 重连 / 刷子编辑会传副本或
         * 已被替换的新对象，存档真值需要回到权威对象上才能随下次存档写入）。
         */
        private static void reapplyShape(Level level, BlockPos pos1, BlockPos pos2, Rail rail1, Rail rail2, String shapeKey) {
                int restored = 0;
                restored += stampOne(rail1, shapeKey);
                restored += stampOne(rail2, shapeKey);

                try {
                        final RailwayData railwayData = RailwayData.getInstance(level);
                        if (railwayData != null) {
                                final Map<BlockPos, Map<BlockPos, Rail>> rails = ((ShapeStore.RailwayDataAccessorDuck) railwayData).yanyang$getRails();
                                if (rails != null) {
                                        final Map<BlockPos, Rail> forwardMap = rails.get(pos1);
                                        if (forwardMap != null) {
                                                restored += stampOne(forwardMap.get(pos2), shapeKey);
                                        }
                                        final Map<BlockPos, Rail> backwardMap = rails.get(pos2);
                                        if (backwardMap != null) {
                                                restored += stampOne(backwardMap.get(pos1), shapeKey);
                                        }
                                }
                        }
                } catch (Throwable t) {
                        LOGGER.debug("Could not re-stamp stored rails for {} <-> {}", pos1, pos2, t);
                }

                if (restored > 0) {
                        LOGGER.info("Restored easement shape {} onto {} rail object(s) at {} <-> {}", shapeKey, restored, pos1.toShortString(), pos2.toShortString());
                }
        }

        /** 给单个对象套印记（值相同则跳过）；返回是否实际写入。 */
        private static int stampOne(Rail rail, String shapeKey) {
                if (rail == null) {
                        return 0;
                }
                final String current = ((RailDuck) rail).yanyang$getShapeStamp();
                if (shapeKey.equals(current)) {
                        return 0;
                }
                ((RailDuck) rail).yanyang$setShapeStamp(shapeKey);
                final Map<String, String> mirror = new HashMap<>();
                mirror.put(EasementShape.RAIL_DATA_KEY, shapeKey);
                mirror.put(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY, "string");
                AnteMirror.setRailCustomConfigsMirror(rail, mirror);
                return 1;
        }
}
