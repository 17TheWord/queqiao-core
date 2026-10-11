package io.github.theword.queqiao.core.config.io;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 配置文件写入器
 *
 * <p><b>本类不在正常启动路径上被调用</b>——正常启动只读不写 {@code config.yml}。
 * 它提供给<b>真正需要覆盖用户文件</b>的场景：
 * 未来的 {@code ConfigMigration}、显式的"同步配置"命令。
 *
 * <p>保证：
 * <ul>
 *     <li><b>原子写</b>：临时文件 → 完整写入 → fsync → {@code ATOMIC_MOVE}。
 *         文件系统不支持时降级为 {@code REPLACE_EXISTING} 并<b>记录日志</b>；</li>
 *     <li><b>可恢复备份</b>：轮换保留最多 {@value #MAX_BACKUPS} 份，
 *         避免单份备份被下一次写入覆盖。</li>
 * </ul>
 *
 * @since 0.6.11
 */
public final class ConfigStore {

    /**
     * 备份最多保留份数
     */
    public static final int MAX_BACKUPS = 5;

    private static final String BACKUP_SUFFIX = ".bak";

    private ConfigStore() {
    }

    /**
     * 解析配置文件路径
     *
     * <p>配置文件恒为平台数据目录下的 {@code config.yml}。目录<b>不再由布尔量推导</b>：
     * 插件端 {@code plugins/QueQiao} 与模组端 {@code config/QueQiao} 的差异已经收敛在
     * {@code AbstractPlatformContext#getDataDirectory()}，这里只负责拼上文件名。
     * 这样"目录从哪来"只有一个来源，不会出现两处各自推导而漂移。
     *
     * @param dataDirectory 平台数据目录，通常取自
     *                      {@code AbstractPlatformContext#getDataDirectory()}
     * @return 配置文件路径
     */
    public static Path resolveConfigPath(Path dataDirectory) {
        return dataDirectory.resolve("config.yml");
    }

    /**
     * 备份后原子写入
     *
     * @param target  目标文件
     * @param content 完整内容
     * @param logger  日志实现，可为 null
     * @throws IOException 写入失败
     */
    public static void backupAndWriteAtomically(Path target, String content, Logger logger) throws IOException {
        rotateBackup(target, logger);
        writeAtomically(target, content, logger);
    }

    /**
     * 原子写入文本
     *
     * @param target  目标文件
     * @param content 完整内容
     * @param logger  日志实现，可为 null
     * @throws IOException 写入失败
     */
    public static void writeAtomically(Path target, String content, Logger logger) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                // 尽量 fsync：确保内容真正落盘后再替换原文件
                channel.force(true);
            }

            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                if (logger != null) {
                    logger.warn("文件系统不支持原子替换，已降级为普通替换：{}（{}）", target, e.getMessage());
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 轮换备份
     *
     * <p>命名与轮换方向（最多 {@value #MAX_BACKUPS} 份）：
     * <pre>
     * config.yml.bak.4  ← 最旧，将被删除
     * config.yml.bak.3
     * config.yml.bak.2
     * config.yml.bak.1
     * config.yml.bak    ← 最新，即本次写入前的内容
     * </pre>
     *
     * @param target 目标文件；不存在时直接返回
     * @param logger 日志实现，可为 null
     * @throws IOException 轮换失败
     */
    public static void rotateBackup(Path target, Logger logger) throws IOException {
        if (!Files.exists(target)) {
            return;
        }

        Path newest = backupPath(target, 0);
        Files.deleteIfExists(backupPath(target, MAX_BACKUPS - 1));

        for (int index = MAX_BACKUPS - 2; index >= 1; index--) {
            Path from = backupPath(target, index);
            if (Files.exists(from)) {
                Files.move(from, backupPath(target, index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (Files.exists(newest)) {
            Files.move(newest, backupPath(target, 1), StandardCopyOption.REPLACE_EXISTING);
        }

        Files.copy(target, newest, StandardCopyOption.REPLACE_EXISTING);
        if (logger != null) {
            logger.info("已创建配置备份：{}（最多保留 {} 份）", newest, MAX_BACKUPS);
        }
    }

    /**
     * 备份路径
     *
     * @param target 目标文件
     * @param index  0 表示最新备份（{@code .bak}），其余为 {@code .bak.<index>}
     */
    private static Path backupPath(Path target, int index) {
        String base = target.getFileName().toString() + BACKUP_SUFFIX;
        return target.resolveSibling(index == 0 ? base : base + "." + index);
    }
}
