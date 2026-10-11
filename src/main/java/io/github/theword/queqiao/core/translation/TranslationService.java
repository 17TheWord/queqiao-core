package io.github.theword.queqiao.core.translation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 服务端游戏文本的翻译补全服务。
 *
 * <p>从指定目录加载 JSON 翻译表（键 → 文本），按翻译键查询并格式化文本。数据以不可变
 * 快照发布，查询无需加锁。它只服务于 QueQiao 自身事件文本的补全，不是通用多语言框架。
 *
 * <p>归属平台：目录取自 {@code AbstractPlatformContext#getDataDirectory()}，
 * Runtime 只按配置调度 {@link #reload(boolean)} / {@link #disable()}。构造不读磁盘。
 *
 * <p>失败分两级：目录无法枚举时保留上一份快照，不清空已有翻译；单个文件不可用时
 * 只跳过该文件，其余文件照常合并。
 *
 * @since 0.6.0
 */
public class TranslationService {

    private final Set<String> missingKeys = ConcurrentHashMap.newKeySet();

    private final Logger logger;

    /**
     * 翻译文件所在目录，构造后不再变化。
     */
    private final Path directory;

    /**
     * 启用状态和翻译映射一起发布，避免读线程看到彼此不匹配的状态。
     */
    private volatile TranslationState state = TranslationState.disabled();

    /**
     * 构造翻译服务，不加载任何文件。
     *
     * @param directory 翻译文件目录，不得为 null；目录不存在时视为没有翻译内容
     * @param logger    日志实现，不得为 null
     */
    public TranslationService(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * @return true 表示已加载翻译条目且服务处于启用状态
     */
    public boolean isEnabled() {
        return state.enabled;
    }

    /**
     * 按开关重新加载翻译目录并发布新状态。
     *
     * <p>{@code enabled == false} 等价于停用，不触碰磁盘。启用时先把全部文件加载到局部
     * Map 再整体发布，读线程不会看到中间状态；并发 reload / disable 由实例锁串行化。
     *
     * <p>目录无法枚举时保留上一份快照；单个文件不可用时只跳过该文件。
     * 只有成功加载的条目为零时才停用。
     *
     * @param enabled 是否启用翻译，对应配置项 {@code enable_translation}
     */
    public synchronized void reload(boolean enabled) {
        if (!enabled) {
            publish(TranslationState.disabled());
            logger.info("翻译功能已在配置中禁用。");
            return;
        }

        final Map<String, String> translations;
        try {
            translations = scanAndLoad(directory);
        } catch (IOException e) {
            // 本轮无法可靠读取目录时保留上一份完整状态，避免短暂 I/O 故障清空翻译。
            logger.warn("读取翻译目录失败，保留当前翻译状态：{}，原因：{}", directory, e.getMessage());
            return;
        }

        if (translations.isEmpty()) {
            publish(TranslationState.disabled());
            logger.warn("未加载到有效翻译内容，翻译服务已禁用。");
            return;
        }

        publish(new TranslationState(true, translations));
        logger.info("翻译服务已就绪，成功载入 {} 条翻译条目。", translations.size());
    }

    /**
     * 扫描目录并载入翻译数据。单文件错误不会冒泡到这里。
     *
     * @throws IOException 目录无法枚举时抛出；调用方保留上一份完整状态
     */
    private Map<String, String> scanAndLoad(Path folder) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        if (Files.notExists(folder)) {
            return result;
        }
        if (!Files.isDirectory(folder)) {
            throw new IOException("翻译路径不是目录");
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(files::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (Path file : files) {
            Map<String, String> fileContent = loadTranslateFile(file);
            for (Map.Entry<String, String> entry : fileContent.entrySet()) {
                if (result.containsKey(entry.getKey())) {
                    logger.warn("翻译键重复，后加载文件将覆盖前值：key={}, file={}", entry.getKey(), file.getFileName());
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 读取并解析单个 JSON 翻译文件。文件为 JSON object，值必须为字符串。
     *
     * <p>失败被隔离在本方法内：读取失败、解析失败或根节点不是 JSON object 时记日志并返回
     * 空 Map，调用方据此跳过该文件。单条取值不是字符串时只跳过该条目。
     *
     * @param file 待读取的翻译文件
     * @return 该文件提供的键值对；文件不可用时为空 Map（非 null）
     */
    private Map<String, String> loadTranslateFile(Path file) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            String jsonContent = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement root = new JsonParser().parse(jsonContent);
            if (root == null || !root.isJsonObject()) {
                logger.warn("加载翻译文件失败：{}，根节点必须是 JSON object", file.getFileName());
                return Collections.emptyMap();
            }

            JsonObject object = root.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    logger.warn("跳过无效翻译条目：file={}, key={}，翻译值必须是字符串", file.getFileName(), entry.getKey());
                    continue;
                }
                data.put(entry.getKey(), value.getAsString());
            }

            if (!data.isEmpty()) {
                logger.info("加载文件成功: {} ({} 条)", file.getFileName(), data.size());
            }
        } catch (Exception e) {
            // 单文件失败只影响本文件：丢弃本次可能已解析出的部分结果，返回空 Map，
            // 让调用方继续合并其余文件。目录级失败不经过这里（见 scanAndLoad）。
            logger.warn("加载翻译文件失败: {}, 原因: {}", file.getFileName(), e.getMessage());
            return Collections.emptyMap();
        }
        return data;
    }

    /**
     * 按翻译键查询并格式化文本。
     *
     * <p>未启用、key 为 null 或未命中时返回 key 本身。
     *
     * @param key  翻译键，例如 {@code death.attack.player}
     * @param args 与模板中 {@code %s} / {@code %1$s} 对应的参数
     * @return 格式化后的文本，或 key 本身
     */
    public String translate(String key, Object[] args) {
        TranslationState current = state;
        if (!current.enabled || key == null) {
            return key;
        }

        String template = current.translations.get(key);
        if (template == null || template.isEmpty()) {
            if (missingKeys.add(key)) {
                logger.warn("未找到翻译内容，Key: {} (仅提示一次)", key);
            }
            return key;
        }

        if (args == null || args.length == 0 || !template.contains("%")) {
            return template;
        }

        try {
            // Minecraft 翻译文件使用 %s / %1$s 等位置参数，不能用 Tool.format 的 {} 语义替代。
            return String.format(template, args);
        } catch (Exception e) {
            logger.warn("格式化异常，Key: {}", key);
            return template;
        }
    }

    /**
     * 停用翻译服务：发布空状态，不修改任何已发布的 Map。
     */
    public synchronized void disable() {
        publish(TranslationState.disabled());
    }

    private void publish(TranslationState next) {
        state = next;
        // 刷新缺失记录，允许新状态中的补全条目生效并重新提示仍缺失的 key。
        missingKeys.clear();
    }

    /**
     * 不可变翻译快照。Map 在构造时复制后包装，调用方无法修改已发布状态。
     */
    private static final class TranslationState {
        private final boolean enabled;
        private final Map<String, String> translations;

        private TranslationState(boolean enabled, Map<String, String> translations) {
            this.enabled = enabled;
            this.translations = Collections.unmodifiableMap(new LinkedHashMap<>(translations));
        }

        private static TranslationState disabled() {
            return new TranslationState(false, Collections.<String, String>emptyMap());
        }
    }
}
