package com.bugquantumcore.yanyang.easement;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * YMTR 存档数据文件的自愈扫描：在任何 {@code mtr.data.RailwayData} 加载之前，
 * 校验全部 MessagePack 存档文件的结构完整性，把损坏的文件修复 / 隔离。
 *
 * <p><b>为什么需要它</b>：YMTR 3.x 的 {@code RailwayDataFileSaveModule} 有两个叠加的
 * 脆弱点（已对 3.5.6 反编译核实）：</p>
 * <ol>
 * <li>{@code writeMessagePackToFile} 把 {@code MessagePacker} 直接包在文件输出流上
 * —— 打包中途抛出的任何异常都会在磁盘上留下<b>截断的半截文件</b>（缓冲区已刷新的
 * 部分已落盘，且输出流未关闭）；</li>
 * <li>{@code readMessagePackFromFile} 的逐文件 try-catch 从
 * {@code unpackMapHeader()} <b>之后</b>才开始 —— 顶映射头本身读不出来时
 * （{@code MessageInsufficientBufferException} 是 RuntimeException，
 * 不被任何 {@code catch (IOException)} 捕获）异常直接逃逸出 {@code load()}，
 * 被原版 {@code PersistentStateManager} 捕获后<b>整份 mtr_train_data 被替换成空白
 * 状态</b> —— 游戏内表现为「MTR 元素全部消失」。</li>
 * </ol>
 *
 * <p>本补丁旧版本（v1.0.0）的 {@code messagePackLength} 注入缺
 * {@code cancellable = true}，在带线型印记的轨道上每次保存都抛
 * {@code CancellationException}，恰好同时踩中上面两个脆弱点，截断了部分
 * {@code rails/<id%100>/<id>} 文件。v1.0.1 已修正写入端；本类负责修复<b>已经落盘的
 * 损坏文件</b>并防御未来任何来源的损坏（其他模组、磁盘错误、进程崩溃）。</p>
 *
 * <p><b>处理策略</b>（在 {@code SERVER_STARTED}、首次服务器 tick 之前运行，
 * 此时 {@code RailwayData} 尚未加载）：</p>
 * <ul>
 * <li>逐文件做完整结构校验（读出整个顶层 msgpack 值）；</li>
 * <li><b>rails 文件</b>：结构为 {@code {node_pos: long, rail_connections: [轨道映射…]}}，
 * 截断几乎总是发生在连接数组内部 —— 按「最后一条<b>完整</b>连接」截断重建有效文件，
 * 丢弃不完整的尾部（每条连接是独立的映射，丢弃不影响前面各条的完整性）；
 * 原始字节先复制到 {@code corrupt-backup/}，再原子替换；</li>
 * <li>其他数据文件夹（stations 等）：不尝试语义修复，损坏文件整体移入
 * {@code corrupt-backup/}（数据从本局丢失，但不再连累整份存档）；</li>
 * <li>{@code corrupt-backup/} 不是 YMTR 扫描的八个数据文件夹之一，永远不会被读取。</li>
 * </ul>
 */
public final class SaveFileSweeper {

        private static final Logger LOGGER = LoggerFactory.getLogger("YanyangEasement");

        /**
         * YMTR 逐维度存档根下的八个 MessagePack 数据文件夹（与
         * {@code RailwayDataFileSaveModule} 的字段一致）。{@code logs/}（CSV 文本）不在其列。
         */
        private static final String[] DATA_FOLDERS = {
                        "stations", "platforms", "sidings", "routes", "depots", "lifts", "rails", "signal-blocks"
        };

        /**
         * 损坏文件（及修复前的原始字节）的隔离目录，位于维度存档根下、
         * 八个数据文件夹之外 —— YMTR 的加载器不会扫描它。
         */
        private static final String BACKUP_FOLDER = "corrupt-backup";

        private static final String KEY_NODE_POS = "node_pos";
        private static final String KEY_RAIL_CONNECTIONS = "rail_connections";

        /**
         * 修复写入使用的临时文件后缀；扫描时顺带清理历史残留（半截临时文件
         * 若留在数据文件夹内会被 YMTR 读取，再次触发加载失败）。
         */
        private static final String REPAIR_TMP_SUFFIX = ".yanyang-repair.tmp";

        private SaveFileSweeper() {
        }

        /**
         * 注册服务器启动事件：在首次服务器 tick（{@code RailwayData} 首次加载）之前
         * 完成扫描。整份扫描包在顶层 catch 里 —— 自愈逻辑自身绝不能阻断服务器启动。
         */
        public static void register() {
                ServerLifecycleEvents.SERVER_STARTED.register(SaveFileSweeper::sweepServer);
        }

        private static void sweepServer(MinecraftServer server) {
                try {
                        int repaired = 0;
                        int quarantined = 0;
                        for (final ServerLevel level : server.getAllLevels()) {
                                final ResourceLocation dimension = level.dimension().location();
                                // 与 RailwayData 构造函数的存档路径完全一致：
                                // <world>/mtr/<维度命名空间>/<维度路径>/
                                final Path savePath = server.getWorldPath(LevelResource.ROOT)
                                                .resolve("mtr").resolve(dimension.getNamespace()).resolve(dimension.getPath());
                                if (!Files.isDirectory(savePath)) {
                                        continue;
                                }
                                final int[] counts = sweepSavePath(savePath);
                                repaired += counts[0];
                                quarantined += counts[1];
                        }
                        if (repaired > 0 || quarantined > 0) {
                                LOGGER.warn("Save self-heal finished: {} rail file(s) repaired (partial connections recovered), "
                                                + "{} file(s) quarantined into '{}'. Rails lost in unrecoverable files need to be "
                                                + "re-connected in game; everything else loads normally.",
                                                repaired, quarantined, BACKUP_FOLDER);
                        }
                } catch (Throwable t) {
                        LOGGER.error("Save self-heal sweep failed unexpectedly; continuing server start", t);
                }
        }

        /**
         * 扫描一个维度存档根，返回 {@code [修复数, 隔离数]}。
         */
        private static int[] sweepSavePath(Path savePath) {
                int repaired = 0;
                int quarantined = 0;
                for (final String folder : DATA_FOLDERS) {
                        final Path folderPath = savePath.resolve(folder);
                        if (!Files.isDirectory(folderPath)) {
                                continue;
                        }
                        try (Stream<Path> idFolders = Files.list(folderPath)) {
                                for (final Path idFolder : (Iterable<Path>) idFolders::iterator) {
                                        if (!Files.isDirectory(idFolder)) {
                                                continue;
                                        }
                                        try (Stream<Path> files = Files.list(idFolder)) {
                                                for (final Path file : (Iterable<Path>) files::iterator) {
                                                        final String name = file.getFileName().toString();
                                                        if (name.endsWith(REPAIR_TMP_SUFFIX)) {
                                                                // 历史修复残留：半截临时文件会被 YMTR 当数据读取，直接清除。
                                                                try {
                                                                        Files.deleteIfExists(file);
                                                                } catch (IOException e) {
                                                                        LOGGER.warn("Could not delete stale repair temp file {}", file, e);
                                                                }
                                                                continue;
                                                        }
                                                        if (!Files.isRegularFile(file) || isStructurallyValid(file)) {
                                                                continue;
                                                        }
                                                        LOGGER.warn("Corrupt MTR data file detected: {}", file);
                                                        final Outcome outcome = "rails".equals(folder)
                                                                        ? tryRepairRailsFile(savePath, idFolder, file)
                                                                        : quarantine(savePath, folder, idFolder, file) ? Outcome.QUARANTINED : Outcome.FAILED;
                                                        switch (outcome) {
                                                                case REPAIRED -> repaired++;
                                                                case QUARANTINED -> quarantined++;
                                                                case FAILED -> LOGGER.error("Could not handle corrupt file {} — "
                                                                                + "MTR data for this dimension may fail to load. "
                                                                                + "Remove or restore the file manually (e.g. from a backup).", file);
                                                        }
                                                }
                                        }
                                }
                        } catch (IOException e) {
                                LOGGER.warn("Failed to scan MTR data folder {}", folderPath, e);
                        }
                }
                return new int[]{repaired, quarantined};
        }

        private enum Outcome {
                REPAIRED, QUARANTINED, FAILED
        }

        /**
         * 完整读出文件的顶层 msgpack 值：任何截断 / 格式错误都会抛异常。
         * 与 YMTR 加载器的读取深度等价（其逐条目同样经 {@code unpackValue} 全量读取）。
         * 文件无法打开（IOException）不算损坏 —— YMTR 对此有捕获，不会致命。
         */
        private static boolean isStructurallyValid(Path file) {
                try (InputStream in = Files.newInputStream(file);
                                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(in)) {
                        unpacker.unpackValue();
                        return true;
                } catch (IOException e) {
                        return true;
                } catch (RuntimeException e) {
                        return false;
                }
        }

        /**
         * 修复一个截断的 rails 文件：按「最后一条完整连接」截断重建。
         *
         * <p>写入端（{@code RailEntry.toMessagePack}）固定先写 {@code node_pos} 再写
         * {@code rail_connections} 数组；截断几乎总发生在数组内部（打包器缓冲区分段
         * 刷新）。逐条读取数组元素，收集异常抛出前已完整读出的连接，重写为计数一致的
         * 有效文件。原始字节先备份到 {@code corrupt-backup/}；重写经临时文件原子替换。</p>
         *
         * <p>文件名即节点打包坐标（{@code BlockPos.asLong()}），在 {@code node_pos}
         * 本身也丢失时可作兜底。无任何完整连接可救时退回隔离。</p>
         */
        private static Outcome tryRepairRailsFile(Path savePath, Path idFolder, Path file) {
                Long nodePos = null;
                List<Value> connections = null;
                try (InputStream in = Files.newInputStream(file);
                                MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(in)) {
                        try {
                                final int topSize = unpacker.unpackMapHeader();
                                for (int i = 0; i < topSize; i++) {
                                        final String key = unpacker.unpackString();
                                        if (KEY_NODE_POS.equals(key)) {
                                                nodePos = unpacker.unpackLong();
                                        } else if (KEY_RAIL_CONNECTIONS.equals(key)) {
                                                final int count = unpacker.unpackArrayHeader();
                                                connections = new ArrayList<>(Math.max(0, count));
                                                for (int j = 0; j < count; j++) {
                                                        connections.add(unpacker.unpackValue());
                                                }
                                        } else {
                                                unpacker.unpackValue(); // 未知键：整值跳过
                                        }
                                }
                        } catch (IOException | RuntimeException truncated) {
                                // 截断点：保留已完整读出的 nodePos / connections。
                                LOGGER.warn("Rail data file {} is truncated; recovering complete connections before the cut", file, truncated);
                        }
                } catch (IOException e) {
                        LOGGER.warn("Could not re-read corrupt rail file {} for repair", file, e);
                        return quarantine(savePath, "rails", idFolder, file) ? Outcome.QUARANTINED : Outcome.FAILED;
                }

                if (nodePos == null) {
                        nodePos = parseLongOrNull(file.getFileName().toString());
                }
                if (nodePos == null || connections == null || connections.isEmpty()) {
                        // 没有任何完整连接可救（或连 node_pos 都无法恢复）→ 隔离。
                        return quarantine(savePath, "rails", idFolder, file) ? Outcome.QUARANTINED : Outcome.FAILED;
                }

                final Path backup = backupPath(savePath, "rails", idFolder, file);
                try {
                        Files.createDirectories(backup.getParent());
                        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                        LOGGER.error("Could not back up corrupt rail file {} to {} — leaving it untouched", file, backup, e);
                        return Outcome.FAILED;
                }

                final Path tmp = file.resolveSibling(file.getFileName() + REPAIR_TMP_SUFFIX);
                try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                                MessagePacker packer = MessagePack.newDefaultPacker(out)) {
                        packer.packMapHeader(2);
                        packer.packString(KEY_NODE_POS).packLong(nodePos);
                        packer.packString(KEY_RAIL_CONNECTIONS).packArrayHeader(connections.size());
                        for (final Value connection : connections) {
                                packer.packValue(connection);
                        }
                        packer.close();
                        moveReplace(tmp, file);
                        LOGGER.warn("Repaired rail data file {}: recovered {} complete connection(s); "
                                        + "the rest were lost to truncation and need re-connecting in game. "
                                        + "Original bytes kept at {}", file, connections.size(), backup);
                        return Outcome.REPAIRED;
                } catch (IOException e) {
                        LOGGER.error("Could not write repaired rail file {}", file, e);
                        try {
                                Files.deleteIfExists(tmp);
                        } catch (IOException ignored) {
                        }
                        // 修复写失败：退回隔离（原始字节已有备份，隔离后加载即可继续）。
                        return quarantine(savePath, "rails", idFolder, file) ? Outcome.QUARANTINED : Outcome.FAILED;
                }
        }

        /**
         * 把损坏文件移入 {@code corrupt-backup/<文件夹>/<id%100>/<文件名>}。
         *
         * @return 是否成功隔离；失败时文件留在原地（YMTR 加载会再次失败并留下日志）
         */
        private static boolean quarantine(Path savePath, String folder, Path idFolder, Path file) {
                final Path backup = backupPath(savePath, folder, idFolder, file);
                try {
                        Files.createDirectories(backup.getParent());
                        Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.warn("Quarantined corrupt MTR data file {} -> {} (its contents are lost from this world; "
                                        + "the file is preserved for manual inspection / backup restore)", file, backup);
                        return true;
                } catch (IOException e) {
                        LOGGER.error("Could not quarantine corrupt MTR data file {} -> {}", file, backup, e);
                        return false;
                }
        }

        private static Path backupPath(Path savePath, String folder, Path idFolder, Path file) {
                return savePath.resolve(BACKUP_FOLDER)
                                .resolve(folder)
                                .resolve(idFolder.getFileName())
                                .resolve(file.getFileName());
        }

        private static void moveReplace(Path from, Path to) throws IOException {
                try {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
        }

        private static Long parseLongOrNull(String raw) {
                try {
                        return Long.parseLong(raw);
                } catch (NumberFormatException e) {
                        return null;
                }
        }
}
