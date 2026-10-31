package com.bugquantumcore.yanyang.easement;

import mtr.block.BlockNode;
import mtr.client.AnteRailCompat;
import mtr.data.Rail;
import mtr.data.RailwayData;
import mtr.mappings.Text;
import mtr.packet.PacketTrainDataGuiServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端逻辑：把所选缓和曲线线型写入轨道的附加数据（{@code railData}），并按 YMTR
 * 「轨道数据编辑器」保存时的同一套流程同步给所有客户端 ——
 * {@code setRailData} → 镜像到 ANTE customConfigs →
 * {@code PacketTrainDataGuiServer.createRailS2C} 广播 → 标记存档保存。
 *
 * <p>线型标记随轨道的 MessagePack / 网络包 / NBT 序列化自动持久化，因此本补丁
 * 无需触碰 MTR 的任何存档格式；服务端与客户端在反序列化 {@code Rail} 时都会经由
 * {@code RailMixin} 惰性重建缓和路径。</p>
 */
public final class EasementShapeApplier {

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

                final Map<BlockPos, Map<BlockPos, Rail>> rails = railwayData.getRailsMap();
                final List<Connection> connections = collectConnections(rails, node);
                if (connections.isEmpty()) {
                        player.displayClientMessage(Text.translatable("message.yanyang_easement.no_rails"), true);
                        return;
                }

                final EasementShape current = EasementShape.fromRailData(connections.get(0).forward.getRailData());
                final EasementShape next = EasementShape.next(current, backwards);

                for (final Connection connection : connections) {
                        applyShape(level, connection, next);
                }
                railwayData.setDirty();

                final MutableComponent shapeName = next == null
                                ? Text.translatable("easement.yanyang_easement.classic")
                                : Text.translatable(next.translationKey());
                player.displayClientMessage(Text.translatable("message.yanyang_easement.shape_set", shapeName), true);
                player.displayClientMessage(Text.translatable("message.yanyang_easement.regen_hint"), false);
        }

        /**
         * 将线型写入一条连接的正反两条轨道并广播。数据与 YMTR 原生
         * {@code PacketUpdateRailData} 的写法保持一致（正反向共享同一份键值对）。
         */
        private static void applyShape(Level level, Connection connection, EasementShape shape) {
                final Map<String, String> railData = new HashMap<>(connection.forward.getRailData());
                if (shape == null) {
                        railData.remove(EasementShape.RAIL_DATA_KEY);
                        railData.remove(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY);
                } else {
                        railData.put(EasementShape.RAIL_DATA_KEY, shape.key());
                        railData.put(EasementShape.RAIL_DATA_TYPE_PREFIX + EasementShape.RAIL_DATA_KEY, "string");
                }

                final Map<String, String> railDataBackward = new HashMap<>(railData);
                connection.forward.setRailData(railData);
                connection.backward.setRailData(railDataBackward);

                AnteRailCompat.setRailCustomConfigsMirror(connection.forward, railData);
                AnteRailCompat.setRailCustomConfigsMirror(connection.backward, railDataBackward);

                PacketTrainDataGuiServer.createRailS2C(level, connection.forward.transportMode, connection.start, connection.end, connection.forward, connection.backward, 0);
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
