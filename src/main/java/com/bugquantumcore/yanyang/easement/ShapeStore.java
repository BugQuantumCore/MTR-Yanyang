package com.bugquantumcore.yanyang.easement;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 线型数据的客户端镜像与自定义 S2C 同步（legacy YMTR 3.4.4 ~ 3.6.1.2 专用）。
 *
 * <p>legacy YMTR 没有 {@code railData}，线型在服务端存放于轨道实例印记
 * （{@link RailDuck}，随存档 messagepack 搭载持久化，见 {@code RailPersistenceMixin}）。
 * 客户端轨道由 YMTR 的网络包重建（不含印记），因此客户端通过本类的
 * 「维度 × 连接键 → 线型」镜像寻址，镜像由两种自定义 S2C 包维护：</p>
 * <ul>
 * <li><b>全量同步</b>——玩家加入 / 切换维度后，服务端把该维度所有带印记的轨道发送过去
 * （{@link #syncPlayerShapes}，由主入口的事件处理器调用）；</li>
 * <li><b>真值广播</b>——每当 YMTR 自身广播某条轨道（{@code createRailS2C}：新建 / 替换 /
 * 修改），服务端把该连接的当前真值（印记或 {@code null}）一并广播
 * （{@code PacketTrainDataGuiServerMixin}），使客户端镜像与新建 / 重建后的轨道保持一致。</li>
 * </ul>
 *
 * <p>轨道几何查询（{@code RailMixin}）解析线型时：具备 {@code railData} 的版本走
 * 原生路径；legacy 版本先查实例印记（服务端轨道），未命中再查本镜像（客户端轨道）。
 * 两级查找都为 {@code null} 时即经典线型。</p>
 *
 * <p><b>失效纪元</b>：所有线型数据变化（印记写入、镜像更新、capable 版本的
 * {@code setRailData}）都会递增 {@link #epoch}；{@code RailMixin} 为每条轨道缓存
 * 构建好的缓和路径并记录构建时的纪元，纪元变化即重建 —— 一次廉价的 volatile 读
 * 让所有客户端轨道在收到同步包后立即切换线型。</p>
 */
public final class ShapeStore {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * legacy 存档 messagepack 中搭载线型印记的键名。
         */
        public static final String LEGACY_KEY = "yanyang_shape";

        /**
         * 自定义 S2C 通道：{@code yanyang_easement:shape_sync}。
         * 载荷：维度 {@code ResourceLocation} + 连接键两个 long + 线型键（空串 = 经典）。
         */
        public static final ResourceLocation SHAPE_SYNC_CHANNEL =
                        new ResourceLocation(YanyangEasementMod.MOD_ID, "shape_sync");

        /**
         * 客户端镜像：维度 → 连接键 → 线型键。仅在客户端环境使用（专用服务端恒为空）。
         */
        private static final Map<ResourceLocation, Map<RailPair, String>> CLIENT_SHAPES = new ConcurrentHashMap<>();

        /**
         * 失效纪元。任何线型数据变化递增。
         */
        private static volatile long epoch;

        /**
         * 当前客户端维度供给（由客户端入口点注入，避免公共代码引用客户端类）。
         */
        private static volatile Supplier<ResourceLocation> clientDimension;

        private ShapeStore() {
        }

        public static long epoch() {
                return epoch;
        }

        public static void bumpEpoch() {
                epoch++;
        }

        public static void setClientDimensionSupplier(Supplier<ResourceLocation> supplier) {
                clientDimension = supplier;
        }

        /**
         * 客户端轨道的线型查询（legacy 路径）。非客户端环境（专用服务端）恒返回 {@code null}。
         *
         * @param pair 连接键（由经典几何复原的节点对）
         * @return 线型键；无记录时 {@code null}
         */
        public static String clientLookup(RailPair pair) {
                if (FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) {
                        return null;
                }
                final Supplier<ResourceLocation> supplier = clientDimension;
                if (supplier == null) {
                        return null;
                }
                final ResourceLocation dimension = supplier.get();
                if (dimension == null) {
                        return null;
                }
                final Map<RailPair, String> shapes = CLIENT_SHAPES.get(dimension);
                return shapes == null ? null : shapes.get(pair);
        }

        /**
         * 客户端镜像写入（由 S2C 包处理器在客户端线程调用）。
         *
         * @param dimension 维度
         * @param pair      连接键
         * @param shapeKey  线型键；{@code null} 表示清除（回到经典线型）
         */
        public static void clientPut(ResourceLocation dimension, RailPair pair, String shapeKey) {
                if (shapeKey == null) {
                        final Map<RailPair, String> shapes = CLIENT_SHAPES.get(dimension);
                        if (shapes != null) {
                                shapes.remove(pair);
                                if (shapes.isEmpty()) {
                                        CLIENT_SHAPES.remove(dimension, shapes);
                                }
                        }
                } else {
                        CLIENT_SHAPES.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>()).put(pair, shapeKey);
                }
                bumpEpoch();
        }

        /**
         * 向一个维度内的所有玩家广播某条连接的线型真值（服务端）。
         *
         * @param level    轨道所在维度
         * @param start    连接起点节点
         * @param end      连接终点节点
         * @param shapeKey 线型键；{@code null} 表示经典线型（清除客户端记录）
         */
        public static void broadcastShape(Level level, BlockPos start, BlockPos end, String shapeKey) {
                if (!(level instanceof ServerLevel serverLevel) || serverLevel.players().isEmpty()) {
                        return;
                }
                final ResourceLocation dimension = level.dimension().location();
                final RailPair pair = RailPair.of(start, end);
                for (final ServerPlayer player : serverLevel.players()) {
                        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                        buf.writeResourceLocation(dimension);
                        buf.writeLong(pair.a());
                        buf.writeLong(pair.b());
                        buf.writeUtf(shapeKey == null ? "" : shapeKey);
                        ServerPlayNetworking.send(player, SHAPE_SYNC_CHANNEL, buf);
                }
        }

        /**
         * 玩家加入 / 切换维度后的全量同步（服务端）：发送该维度所有带线型印记的轨道。
         * 仅在 legacy 模式下有实际内容（capable 版本的线型随 YMTR 原生数据同步）。
         */
        public static void syncPlayerShapes(ServerPlayer player) {
                if (YanyangCompat.RAILDATA_CAPABLE) {
                        return;
                }
                final ServerLevel level = player.serverLevel();
                final mtr.data.RailwayData railwayData = mtr.data.RailwayData.getInstance(level);
                if (railwayData == null) {
                        return;
                }
                final Map<BlockPos, Map<BlockPos, mtr.data.Rail>> rails =
                                ((RailwayDataAccessorDuck) railwayData).yanyang$getRails();
                if (rails == null || rails.isEmpty()) {
                        return;
                }
                final ResourceLocation dimension = level.dimension().location();
                int sent = 0;
                for (final Map.Entry<BlockPos, Map<BlockPos, mtr.data.Rail>> outgoing : rails.entrySet()) {
                        for (final Map.Entry<BlockPos, mtr.data.Rail> entry : outgoing.getValue().entrySet()) {
                                final String stamp = ((RailDuck) entry.getValue()).yanyang$getShapeStamp();
                                if (stamp != null) {
                                        final RailPair pair = RailPair.of(outgoing.getKey(), entry.getKey());
                                        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                                        buf.writeResourceLocation(dimension);
                                        buf.writeLong(pair.a());
                                        buf.writeLong(pair.b());
                                        buf.writeUtf(stamp);
                                        ServerPlayNetworking.send(player, SHAPE_SYNC_CHANNEL, buf);
                                        sent++;
                                }
                        }
                }
                if (sent > 0) {
                        LOGGER.info("Synced {} eased rail shape(s) in {} to {}", sent, dimension, player.getGameProfile().getName());
                }
        }

        /**
         * 由 {@code RailwayDataAccessor} Mixin 实现的轨道映射访问接口（鸭子类型）。
         * 私有字段 {@code rails} 自 YMTR 3.0 起在所有 3.x 版本上同名同构。
         */
        public interface RailwayDataAccessorDuck {
                Map<BlockPos, Map<BlockPos, mtr.data.Rail>> yanyang$getRails();
        }
}
