package com.bugquantumcore.yanyang.easement;

import io.netty.buffer.Unpooled;
import mtr.data.Rail;
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

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 * 修改），服务端把该连接的当前真值（印记、{@code railData} 或 ANTE
 * {@code customConfigs} 中的有效线型，或空串 = 经典）一并广播
 * （{@code PacketTrainDataGuiServerMixin}），使客户端镜像与新建 / 重建后的轨道保持一致。</li>
 * </ul>
 *
 * <p><b>镜像条目是三态的</b>：无条目 = 未知（继续回退 ANTE {@code customConfigs}）；
 * 线型键 = 缓和线型；<b>空串 = 明确的经典线型</b>（由清除类操作写入，使「用线型工具
 * 切回经典」能压过客户端轨道上残留的 ANTE {@code customConfigs} 旧值）。</p>
 *
 * <p>轨道几何查询（{@code RailMixin}）解析线型时：具备 {@code railData} 的版本走
 * 原生路径；legacy 版本先查实例印记（服务端轨道），再查本镜像（客户端轨道），最后
 * 回退 ANTE {@code customConfigs}。三级都无记录时即经典线型。</p>
 *
 * <p><b>失效纪元</b>：所有线型数据变化（印记写入、镜像更新、capable 版本的
 * {@code setRailData}）都会递增 {@link #epoch}；{@code RailMixin} 为每条轨道缓存
 * 构建好的缓和路径并记录构建时的纪元，纪元变化即重建 —— 一次廉价的 volatile 读
 * 让所有客户端轨道在收到同步包后立即切换线型。</p>
 *
 * <p><b>诊断</b>：线型解析（{@link #noteResolution} / {@link #noteMiss} /
 * {@link #noteSolveFailure}）与镜像变化都计入聚合计数器，由两侧的周期 tick 报告
 * （{@link #collectReport}）——「线型为什么没生效」的排查依赖这些日志。</p>
 */
public final class ShapeStore {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * legacy 存档 messagepack 中搭载线型印记的键名。
         */
        public static final String LEGACY_KEY = "yanyang_shape";

        /**
         * 自定义 S2C 通道：{@code yanyang_easement:shape_sync}。
         * 载荷：维度 {@code ResourceLocation} + 连接键两个 long + 线型键（空串 = 经典）
         * + v1.0.4 追加的真实节点对两个 long（镜像条目携带，用于端点锚定）。
         */
        public static final ResourceLocation SHAPE_SYNC_CHANNEL =
                        new ResourceLocation(YanyangEasementMod.MOD_ID, "shape_sync");

        /**
         * v1.0.4：镜像条目 —— 线型键 + 真实节点对。客户端几何键命中后把缓和曲线
         * 端点锚定到真实节点，不再信任被 ANTE 编辑污染的经典几何字段
         * （其推导端点可能偏离节点若干格）。
         */
        public record ShapeRecord(String shapeKey, long nodeA, long nodeB) {

                /**
                 * @return 是否为明确的经典线型（空串或 {@code null} 键）
                 */
                public boolean isClassic() {
                        return shapeKey == null || shapeKey.isEmpty();
                }
        }

        /**
         * 客户端镜像：维度 → 连接键 → 镜像条目。仅在客户端环境使用
         * （专用服务端恒为空）。
         */
        private static final Map<ResourceLocation, Map<RailPair, ShapeRecord>> CLIENT_SHAPES = new ConcurrentHashMap<>();

        /**
         * v1.0.3：服务器形状记忆（维度 → 连接键 → 线型键，空串 = 明确经典）。
         * MTR GUI 重连等场景会用全新 {@code Rail} 对象重建连接（印记与
         * {@code customConfigs} 全部丢失），靠这层记忆把线型恢复回去。
         */
        private static final Map<ResourceLocation, Map<RailPair, String>> SERVER_SHAPE_MEMORY = new ConcurrentHashMap<>();

        /**
         * 失效纪元。任何线型数据变化递增。
         */
        private static volatile long epoch;

        /**
         * 客户端镜像发生了实际变化的标志：下一客户端 tick 需要让 ANTE 重建烘焙轨道。
         * 由 {@link #clientPut} 置位、{@link #consumeClientDirty} 消费（去抖：同一 tick 内
         * 的任意多次镜像更新只触发一次重建）。
         */
        private static volatile boolean clientDirty;

        /**
         * 当前客户端维度供给（由客户端入口点注入，避免公共代码引用客户端类）。
         */
        private static volatile Supplier<ResourceLocation> clientDimension;

        // ---- 诊断聚合（自上次报告以来的计数，报告后清零） ----

        private static final AtomicLong DIAG_RESOLVED_STAMP = new AtomicLong();
        private static final AtomicLong DIAG_RESOLVED_MIRROR = new AtomicLong();
        private static final AtomicLong DIAG_RESOLVED_CLASSIC = new AtomicLong();
        private static final AtomicLong DIAG_RESOLVED_CONFIGS = new AtomicLong();
        private static final AtomicLong DIAG_MISSED = new AtomicLong();
        private static final AtomicLong DIAG_SOLVE_FAILED = new AtomicLong();
        private static volatile long DIAG_LAST_MISS_PAIR_A;
        private static volatile long DIAG_LAST_MISS_PAIR_B;

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
         * @return 镜像条目（含真实节点对）；无记录时 {@code null}（继续回退）
         */
        public static ShapeRecord clientLookup(RailPair pair) {
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
                final Map<RailPair, ShapeRecord> shapes = CLIENT_SHAPES.get(dimension);
                return shapes == null ? null : shapes.get(pair);
        }

        /**
         * 客户端镜像写入（由 S2C 包处理器在客户端线程调用）。
         *
         * @param dimension 维度
         * @param pair      连接键
         * @param record    镜像条目（线型键 + 真实节点对）；{@code null} 键写入空串条目（明确经典）
         * @return 本次写入是否实际改变了镜像（需要重建烘焙轨道等派生缓存）
         */
        public static boolean clientPut(ResourceLocation dimension, RailPair pair, ShapeRecord record) {
                final String effective = record.shapeKey() == null ? "" : record.shapeKey();
                final ShapeRecord normalized = new ShapeRecord(effective, record.nodeA(), record.nodeB());
                final Map<RailPair, ShapeRecord> shapes = CLIENT_SHAPES.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
                final boolean changed = !normalized.equals(shapes.put(pair, normalized));
                if (changed) {
                        bumpEpoch();
                        clientDirty = true;
                        LOGGER.info("Client shape mirror: {} -> {} (dim {}, nodes {} <-> {}, mirror size {})",
                                        pair, effective.isEmpty() ? "(classic)" : effective, dimension,
                                        RailPair.of(normalized.nodeA(), normalized.nodeB()), RailPair.of(normalized.nodeA(), normalized.nodeB()), mirrorSize());
                }
                return changed;
        }

        /**
         * 消费「客户端镜像已变化」标志（每客户端 tick 至多触发一次派生缓存重建）。
         *
         * @return 自上次消费以来镜像是否发生过实际变化
         */
        public static boolean consumeClientDirty() {
                if (!clientDirty) {
                        return false;
                }
                clientDirty = false;
                return true;
        }

        /**
         * @return 镜像中的条目总数（诊断用）
         */
        public static int mirrorSize() {
                int size = 0;
                for (final Map<RailPair, ShapeRecord> shapes : CLIENT_SHAPES.values()) {
                        size += shapes.size();
                }
                return size;
        }

        /**
         * v1.0.4：收集一个连接的全部镜像键 —— 节点键（服务器权威 {@code BlockPos}）
         * 加上 forward/backward 两个 {@code Rail} 对象按经典几何推导的键。
         * 客户端 {@code RailMixin} 的查询用同一套几何推导，因此即便经典几何
         * 无法表达真实节点（例如被 ANTE 轨道编辑器重写过），两端推导
         * 结果仍然一致，镜像按几何键即可命中。
         */
        private static Set<RailPair> connectionKeys(BlockPos start, BlockPos end, Rail rail1, Rail rail2) {
                final Set<RailPair> keys = new LinkedHashSet<>();
                keys.add(RailPair.of(start, end));
                appendDerivedKey(keys, rail1);
                appendDerivedKey(keys, rail2);
                return keys;
        }

        /** 把单个 {@code Rail} 的几何推导键追加进键集（推导失败不影响节点键广播）。 */
        private static void appendDerivedKey(Set<RailPair> keys, Rail rail) {
                if (rail instanceof RailDuck duck) {
                        try {
                                final RailPair derived = duck.yanyang$pairKey();
                                if (derived != null) {
                                        keys.add(derived);
                                }
                        } catch (Throwable t) {
                                // 几何推导失败不影响节点键广播
                        }
                }
        }

        /**
         * 向一个维度内的所有玩家广播某条连接的线型真值（服务端，v1.0.4 多键）。
         * 对键集（节点键 + 几何推导键）中的每个键各发一包，客户端无论按哪种键
         * 查询都能命中。
         *
         * @param level    轨道所在维度
         * @param start    连接起点节点
         * @param end      连接终点节点
         * @param shapeKey 线型键；{@code null} 表示经典线型（客户端记录为明确经典）
         * @param rail1    参与同步的正向 {@code Rail}（几何推导键来源，可为 {@code null}）
         * @param rail2    参与同步的反向 {@code Rail}（可为 {@code null}）
         */
        public static void broadcastShape(Level level, BlockPos start, BlockPos end, String shapeKey, Rail rail1, Rail rail2) {
                if (!(level instanceof ServerLevel serverLevel) || serverLevel.players().isEmpty()) {
                        return;
                }
                final ResourceLocation dimension = level.dimension().location();
                final RailPair pair = RailPair.of(start, end);
                final Set<RailPair> keys = connectionKeys(start, end, rail1, rail2);
                LOGGER.info("Broadcasting shape {} for {} in {} to {} player(s) via {} key(s)",
                                shapeKey == null ? "(classic)" : shapeKey, pair, dimension, serverLevel.players().size(), keys.size());
                for (final ServerPlayer player : serverLevel.players()) {
                        for (final RailPair key : keys) {
                                sendShape(player, dimension, key, shapeKey, pair);
                        }
                }
        }

        /** 单玩家单键发送：载荷 = 维度 + 键 + 线型键 + 真实节点对（v1.0.4 追加）。 */
        private static void sendShape(ServerPlayer player, ResourceLocation dimension, RailPair key, String shapeKey, RailPair nodes) {
                final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeResourceLocation(dimension);
                buf.writeLong(key.a());
                buf.writeLong(key.b());
                buf.writeUtf(shapeKey == null ? "" : shapeKey);
                buf.writeLong(nodes.a());
                buf.writeLong(nodes.b());
                ServerPlayNetworking.send(player, SHAPE_SYNC_CHANNEL, buf);
        }

        /**
         * 玩家加入 / 切换维度后的全量同步（服务端）：发送该维度所有带线型的轨道
         * （印记 → ANTE {@code customConfigs} → 服务器形状记忆三层解析，v1.0.3）。
         * 仅在 legacy 模式下有实际内容（capable 版本的线型随 YMTR 原生数据同步）。
         */
        public static void syncPlayerShapes(ServerPlayer player) {
                if (YanyangCompat.RAILDATA_CAPABLE) {
                        return;
                }
                final ServerLevel level = player.serverLevel();
                final mtr.data.RailwayData railwayData = mtr.data.RailwayData.getInstance(level);
                if (railwayData == null) {
                        LOGGER.info("Join sync for {}: railway data not available yet", player.getGameProfile().getName());
                        return;
                }
                final Map<BlockPos, Map<BlockPos, Rail>> rails =
                                ((RailwayDataAccessorDuck) railwayData).yanyang$getRails();
                if (rails == null || rails.isEmpty()) {
                        LOGGER.info("Join sync for {}: no rails in {}", player.getGameProfile().getName(), level.dimension().location());
                        return;
                }
                final ResourceLocation dimension = level.dimension().location();
                int sent = 0;
                int connections = 0;
                for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> outgoing : rails.entrySet()) {
                        for (final Map.Entry<BlockPos, Rail> entry : outgoing.getValue().entrySet()) {
                                connections++;
                                String shapeKey = ((RailDuck) entry.getValue()).yanyang$getShapeStamp();
                                if (shapeKey == null) {
                                        // v1.0.3：ANTE customConfigs 回读（编辑器路径 / 被替换后印记丢失的兑底）。
                                        final String configured = AnteMirror.readRailShapeKey(entry.getValue());
                                        if (configured != null && EasementShape.fromKey(configured) != null) {
                                                shapeKey = configured;
                                        }
                                }
                                if (shapeKey == null) {
                                        // v1.0.3：服务器形状记忆（MTR GUI 重连等全新对象场景）。
                                        final String remembered = recallServerShape(dimension, RailPair.of(outgoing.getKey(), entry.getKey()));
                                        if (remembered != null && !remembered.isEmpty()) {
                                                shapeKey = remembered;
                                        }
                                }
                                if (shapeKey != null) {
                                        final RailPair pair = RailPair.of(outgoing.getKey(), entry.getKey());
                                        final Set<RailPair> keys = connectionKeys(outgoing.getKey(), entry.getKey(), entry.getValue(), null);
                                        for (final RailPair key : keys) {
                                                sendShape(player, dimension, key, shapeKey, pair);
                                        }
                                        sent++;
                                }
                        }
                }
                LOGGER.info("Join sync for {}: {} eased shape(s) among {} rail connection(s) in {}",
                                player.getGameProfile().getName(), sent, connections, dimension);
        }

        // ---- 诊断聚合 ----

        /**
         * 记录一次线型解析来源（每次缓和路径重建时由 {@code RailMixin} 调用）。
         *
         * @param source {@code stamp} / {@code mirror} / {@code classic} / {@code solved}
         */
        public static void noteResolution(String source) {
                switch (source) {
                        case "stamp" -> DIAG_RESOLVED_STAMP.incrementAndGet();
                        case "mirror" -> DIAG_RESOLVED_MIRROR.incrementAndGet();
                        case "classic" -> DIAG_RESOLVED_CLASSIC.incrementAndGet();
                        default -> DIAG_RESOLVED_CONFIGS.incrementAndGet();
                }
        }

        /**
         * 记录一次「三级来源全部未命中」（连客户端镜像都没有该连接的记录）。
         * 最后一次未命中的连接键会被记住 —— 与广播日志对照即可定位键不匹配。
         */
        public static void noteMiss(RailPair pair) {
                DIAG_MISSED.incrementAndGet();
                DIAG_LAST_MISS_PAIR_A = pair.a();
                DIAG_LAST_MISS_PAIR_B = pair.b();
        }

        /**
         * 记录一次「线型已知但缓和路径求解失败」（两族曲线无法表达的几何）。
         */
        public static void noteSolveFailure(String shapeKey) {
                DIAG_SOLVE_FAILED.incrementAndGet();
                if (DIAG_SOLVE_FAILED.get() == 1 || DIAG_SOLVE_FAILED.get() % 100 == 0) {
                        LOGGER.warn("Eased path solve failed for shape {} (geometry falls back to classic; further occurrences counted silently)", shapeKey);
                }
        }

        /**
         * 汇集自上次报告以来的解析计数并清零；无新活动时返回 {@code null}。
         * 由两侧的周期 tick 调用（约 20 秒一次）。
         */
        public static String collectReport() {
                final long stamp = DIAG_RESOLVED_STAMP.getAndSet(0);
                final long mirror = DIAG_RESOLVED_MIRROR.getAndSet(0);
                final long classic = DIAG_RESOLVED_CLASSIC.getAndSet(0);
                final long configs = DIAG_RESOLVED_CONFIGS.getAndSet(0);
                final long missed = DIAG_MISSED.getAndSet(0);
                final long failed = DIAG_SOLVE_FAILED.getAndSet(0);
                if (stamp == 0 && mirror == 0 && classic == 0 && configs == 0 && missed == 0 && failed == 0) {
                        return null;
                }
                final StringBuilder report = new StringBuilder("Rail shape resolution since last report: byStamp=")
                                .append(stamp)
                                .append(", byMirror=").append(mirror)
                                .append(", byAnteConfigs=").append(configs)
                                .append(", classicExplicit=").append(classic)
                                .append(", miss=").append(missed)
                                .append(", solveFailed=").append(failed);
                if (missed > 0) {
                        report.append("; lastMissPair=[").append(DIAG_LAST_MISS_PAIR_A).append(", ").append(DIAG_LAST_MISS_PAIR_B).append(']');
                }
                return report.toString();
        }

        /**
         * 由 {@code RailwayDataAccessor} Mixin 实现的轨道映射访问接口（鸭子类型）。
         * 私有字段 {@code rails} 自 YMTR 3.0 起在所有 3.x 版本上同名同构。
         */
        public interface RailwayDataAccessorDuck {
                Map<BlockPos, Map<BlockPos, Rail>> yanyang$getRails();
        }

        // ---- v1.0.3：服务器形状记忆 ----

        /**
         * 记住一个连接在服务器侧的线型真值（含空串 = 明确经典）。
         * 由 {@code PacketTrainDataGuiServerMixin} 在每次 {@code createRailS2C} 时
         * 与线型工具施印时写入 —— MTR GUI 重连用全新对象重建连接时靠它恢复。
         */
        public static void rememberServerShape(ResourceLocation dimension, RailPair pair, String shapeKey) {
                if (dimension == null || pair == null) {
                        return;
                }
                final String effective = shapeKey == null ? "" : shapeKey;
                SERVER_SHAPE_MEMORY.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>()).put(pair, effective);
        }

        /**
         * 回忆一个连接在服务器侧的线型真值；无记录时 {@code null}。
         */
        public static String recallServerShape(ResourceLocation dimension, RailPair pair) {
                if (dimension == null || pair == null) {
                        return null;
                }
                final Map<RailPair, String> memory = SERVER_SHAPE_MEMORY.get(dimension);
                return memory == null ? null : memory.get(pair);
        }

        /** 清空服务器形状记忆（存档卸载 / 服务器重置时由生命周期事件调用）。 */
        public static void clearServerShapeMemory() {
                SERVER_SHAPE_MEMORY.clear();
        }
}
