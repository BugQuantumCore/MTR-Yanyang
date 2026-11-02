package com.bugquantumcore.yanyang.easement;

import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端逻辑：把所选缓和曲线线型写入一条轨道连接，并同步给客户端。
 *
 * <p>按当前 YMTR 能力选择存储路径（见 {@link YanyangCompat}）：</p>
 * <ul>
 * <li><b>YMTR 3.6.2.2+</b>：写入轨道附加数据（{@code railData} 键值对），随存档持久化、
 * 由 YMTR 原生同步（{@code setRailData} → ANTE 镜像 → {@code createRailS2C} 广播 →
 * 标记存档保存），且可在游戏内「轨道数据编辑器」中查看；</li>
 * <li><b>legacy YMTR 3.4.4 ~ 3.6.1.2</b>：写入轨道实例印记（{@link RailDuck}，随存档
 * MessagePack 搭载持久化），经本补丁自定义 {@code yanyang_easement:shape_sync} 包广播；
 * YMTR 自身状态未被改动，无需调用其广播。</li>
 * </ul>
 * <p>两种路径写入的数据键值完全一致（{@code rail_shape} = {@code cubic_parabola} /
 * {@code half_wave_sine}），ANTE（MTRSteamLoco）的 {@code customConfigs} 镜像内容也一致。</p>
 */
public final class EasementShapeApplier {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        private EasementShapeApplier() {
        }

        /**
         * 循环切换一个轨道节点上所有连接的线型：
         * 经典 → 三次抛物线 → 半波正弦 → 经典……
         *
         * @param level     服务端世界
         * @param player    执行操作的玩家
         * @param node      被右键的轨道节点
         * @param backwards 潜行时反向循环
         */
        public static void cycleShapesAtNode(Level level, ServerPlayer player, BlockPos node, boolean backwards) {
                if (RailwayData.hasNoPermission(player)) {
                        player.displayClientMessage(Text.translatable("message.yanyang_easement.no_permission"), true);
                        return;
                }

                final RailwayData railwayData = RailwayData.getInstance(level);
                if (railwayData == null) {
                        return;
                }

                final Map<BlockPos, Map<BlockPos, Rail>> rails = ((ShapeStore.RailwayDataAccessorDuck) railwayData).yanyang$getRails();
                final List<Connection> connections = collectConnections(rails, node);
                if (connections.isEmpty()) {
                        player.displayClientMessage(Text.translatable("message.yanyang_easement.no_rails"), true);
                        return;
                }

                final EasementShape current = readShape(connections.get(0).forward);
                final EasementShape next = EasementShape.next(current, backwards);

                int stamped = 0;
                for (final Connection connection : connections) {
                        if (applyShape(level, connection, next)) {
                                stamped++;
                        }
                }
                railwayData.setDirty();

                LOGGER.info("Shape tool at node {}: {} of {} connection(s) -> {}",
                                node.toShortString(), stamped, connections.size(), next == null ? "classic" : next.key());

                final MutableComponent shapeName = next == null
                                ? Text.translatable("easement.yanyang_easement.classic")
                                : Text.translatable(next.translationKey());
                player.displayClientMessage(Text.translatable("message.yanyang_easement.shape_set", shapeName), true);
                player.displayClientMessage(Text.translatable("message.yanyang_easement.regen_hint"), false);
        }

        /**
         * 读取一条轨道当前的线型（按能力选择载体：railData → 印记 → ANTE
         * {@code customConfigs}）。
         */
        private static EasementShape readShape(Rail rail) {
                if (YanyangCompat.RAILDATA_CAPABLE) {
                        return EasementShape.fromRailData(rail.getRailData());
                }
                final EasementShape stamped = EasementShape.fromKey(((RailDuck) rail).yanyang$getShapeStamp());
                if (stamped != null) {
                        return stamped;
                }
                // 被 ANTE 编辑器替换过的轨道印记丢失，但 customConfigs 随网络包保留。
                return EasementShape.fromKey(AnteMirror.readRailShapeKey(rail));
        }

        /**
         * 将线型写入一条连接的正反两条轨道并同步。
         *
         * @return 是否实际执行了写入（连接两条轨道齐全时为 {@code true}）
         */
        private static boolean applyShape(Level level, Connection connection, EasementShape shape) {
                if (YanyangCompat.RAILDATA_CAPABLE) {
                        applyShapeViaRailData(level, connection, shape);
                } else {
                        applyShapeViaStamp(level, connection, shape);
                }
                return true;
        }

        /**
         * capable 路径：写入 {@code railData}（正反向共享同一份键值对，与 YMTR 原生
         * {@code PacketUpdateRailData} 的写法一致），按 YMTR 流程镜像与广播。清除线型
         * 时在 ANTE 镜像里对线型键写入 {@code null}（= 删除），避免残留旧值。
         */
        private static void applyShapeViaRailData(Level level, Connection connection, EasementShape shape) {
                final Map<String, String> existing = connection.forward.getRailData();
                final Map<String, String> railData = new HashMap<>(existing == null ? new HashMap<>() : existing);
                putShapeEntries(railData, shape);

                final Map<String, String> railDataBackward = new HashMap<>(railData);
                connection.forward.setRailData(railData);
                connection.backward.setRailData(railDataBackward);

                final Map<String, String> anteMirror = new HashMap<>(railData);
                if (shape == null) {
                        anteMirror.put(EasementShape.RAIL_DATA_KEY, null);
                        anteMirror.put(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY, null);
                }
                AnteMirror.setRailCustomConfigsMirror(connection.forward, anteMirror);
                AnteMirror.setRailCustomConfigsMirror(connection.backward, new HashMap<>(anteMirror));

                PacketTrainDataGuiServer.createRailS2C(level, connection.forward.transportMode, connection.start, connection.end, connection.forward, connection.backward, 0);
        }

        /**
         * legacy 路径：写入轨道实例印记，镜像到 ANTE 后用本补丁的自定义包广播
         * （YMTR 自身状态未变，无需调用其广播）。清除线型时从 ANTE
         * {@code customConfigs} 一并移除对应键（值为 {@code null} = 删除），
         * 避免回读路径复活旧线型。
         */
        private static void applyShapeViaStamp(Level level, Connection connection, EasementShape shape) {
                final String key = shape == null ? null : shape.key();
                ((RailDuck) connection.forward).yanyang$setShapeStamp(key);
                ((RailDuck) connection.backward).yanyang$setShapeStamp(key);

                final Map<String, String> mirror = new HashMap<>();
                mirror.put(EasementShape.RAIL_DATA_KEY, key);
                mirror.put(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY, key == null ? null : "string");
                AnteMirror.setRailCustomConfigsMirror(connection.forward, mirror);
                AnteMirror.setRailCustomConfigsMirror(connection.backward, mirror);

                ShapeStore.broadcastShape(level, connection.start, connection.end, key);
        }

        /**
         * 把线型键值（含 YMTR 编辑器的类型标记）写入映射；{@code shape == null} 时清除。
         */
        private static void putShapeEntries(Map<String, String> map, EasementShape shape) {
                if (shape == null) {
                        map.remove(EasementShape.RAIL_DATA_KEY);
                        map.remove(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY);
                } else {
                        map.put(EasementShape.RAIL_DATA_KEY, shape.key());
                        map.put(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY, "string");
                }
        }

        /**
         * 收集一个节点上的全部轨道连接（去重，按发现顺序）。一个连接包含其正向
         * （start→end 行车方向）与反向两条 {@code Rail} 实例。
         */
        private static List<Connection> collectConnections(Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos node) {
                final Map<Long, Connection> connections = new LinkedHashMap<>();

                final Map<BlockPos, Rail> outgoing = rails.get(node);
                if (outgoing != null) {
                        for (final Map.Entry<BlockPos, Rail> entry : outgoing.entrySet()) {
                                addConnection(connections, rails, node, entry.getKey(), entry.getValue());
                        }
                }
                for (final Map.Entry<BlockPos, Map<BlockPos, Rail>> entry : rails.entrySet()) {
                        final Rail backward = entry.getValue().get(node);
                        if (backward != null) {
                                addConnection(connections, rails, entry.getKey(), node, backward);
                        }
                }

                return new ArrayList<>(connections.values());
        }

        private static void addConnection(Map<Long, Connection> connections, Map<BlockPos, Map<BlockPos, Rail>> rails, BlockPos start, BlockPos end, Rail forward) {
                final long startLong = start.asLong();
                final long endLong = end.asLong();
                final long key = startLong < endLong ? startLong * 31L + endLong : endLong * 31L + startLong;
                if (connections.containsKey(key)) {
                        return;
                }
                final Map<BlockPos, Rail> backwardMap = rails.get(end);
                if (backwardMap == null) {
                        return;
                }
                final Rail backward = backwardMap.get(start);
                if (backward != null) {
                        connections.put(key, new Connection(start, end, forward, backward));
                }
        }

        private static final class Connection {
                final BlockPos start;
                final BlockPos end;
                final Rail forward;
                final Rail backward;

                Connection(BlockPos start, BlockPos end, Rail forward, Rail backward) {
                        this.start = start;
                        this.end = end;
                        this.forward = forward;
                        this.backward = backward;
                }
        }
}
