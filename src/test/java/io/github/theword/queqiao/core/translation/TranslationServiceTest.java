package io.github.theword.queqiao.core.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TranslationService} 的行为基线
 *
 * <p>本类是该服务<b>首次</b>获得测试覆盖（迁移前的 {@code LanguageService} 没有任何对应用例），
 * 因此这里锁定的是"迁移前后必须一致"的行为：
 * 构造不加载、多文件合并与文件名排序、重复键后者覆盖、占位符格式化、缺失键回退、
 * 无效内容跳过，以及 I/O 失败时保留上一份完整快照。
 */
@DisplayName("TranslationService：目录驱动的翻译加载与查询")
class TranslationServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationServiceTest.class);

    @TempDir
    Path tempDir;

    private static TranslationService serviceOver(Path directory) {
        return new TranslationService(directory, LOGGER);
    }

    private static void writeJson(Path directory, String fileName, String json) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve(fileName), json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 构造与停用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("构造不加载：未 reload 时服务关闭，translate 原样返回 key")
    void constructorDoesNotLoad() throws IOException {
        writeJson(tempDir, "zh_cn.json", "{\"death.attack.player\": \"%s 被击败了\"}");
        TranslationService service = serviceOver(tempDir);

        assertFalse(service.isEnabled(), "构造不应加载任何翻译");
        assertEquals("death.attack.player",
                service.translate("death.attack.player", new Object[] {"Steve"}));
    }

    @Test
    @DisplayName("reload(false) 等价于停用：不读目录，translate 返回 key")
    void reloadDisabledStaysOff() throws IOException {
        writeJson(tempDir, "zh_cn.json", "{\"a\": \"甲\"}");
        TranslationService service = serviceOver(tempDir);

        service.reload(false);

        assertFalse(service.isEnabled(), "关闭开关时不应启用翻译");
        assertEquals("a", service.translate("a", new Object[0]));
    }

    @Test
    @DisplayName("启用但目录不存在：视为没有翻译内容，服务保持关闭")
    void missingDirectoryStaysOff() {
        TranslationService service = serviceOver(tempDir.resolve("nope"));

        service.reload(true);

        assertFalse(service.isEnabled(), "目录不存在时不应启用翻译");
    }

    @Test
    @DisplayName("目录存在但没有可加载条目：服务保持关闭")
    void emptyDirectoryStaysOff() throws IOException {
        Files.createDirectories(tempDir);
        TranslationService service = serviceOver(tempDir);

        service.reload(true);

        assertFalse(service.isEnabled(), "没有条目时不应启用翻译");
    }

    @Test
    @DisplayName("disable 后回到关闭状态，translate 返回 key")
    void disableClearsState() throws IOException {
        writeJson(tempDir, "zh_cn.json", "{\"a\": \"甲\"}");
        TranslationService service = serviceOver(tempDir);
        service.reload(true);
        assertTrue(service.isEnabled(), "前置条件：应已启用");

        service.disable();

        assertFalse(service.isEnabled());
        assertEquals("a", service.translate("a", new Object[0]));
    }

    // ------------------------------------------------------------------
    // 加载语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("多文件按文件名排序加载，后加载文件覆盖同名键")
    void loadsMultipleFilesInNameOrderAndLaterWins() throws IOException {
        writeJson(tempDir, "a.json", "{\"shared\": \"来自 a\", \"only_a\": \"甲\"}");
        writeJson(tempDir, "b.json", "{\"shared\": \"来自 b\"}");
        TranslationService service = serviceOver(tempDir);

        service.reload(true);

        assertTrue(service.isEnabled());
        assertEquals("来自 b", service.translate("shared", new Object[0]),
                "b.json 排在 a.json 之后，应覆盖同名键");
        assertEquals("甲", service.translate("only_a", new Object[0]),
                "只有 a.json 提供的键应保留");
    }

    @Test
    @DisplayName("无效条目与无效根节点被跳过，不影响其它文件")
    void skipsInvalidEntriesAndFiles() throws IOException {
        writeJson(tempDir, "a_bad_root.json", "[\"根节点不是 object\"]");
        writeJson(tempDir, "b_mixed.json", "{\"ok\": \"有效\", \"num\": 1, \"obj\": {}, \"arr\": []}");
        TranslationService service = serviceOver(tempDir);

        service.reload(true);

        assertTrue(service.isEnabled(), "存在有效条目时应启用");
        assertEquals("有效", service.translate("ok", new Object[0]));
        assertEquals("num", service.translate("num", new Object[0]), "非字符串值不应被载入");
        assertEquals("arr", service.translate("arr", new Object[0]), "数组值不应被载入");
    }

    @Test
    @DisplayName("再次 reload 能拾取新增的翻译文件（重载生效）")
    void reloadPicksUpNewFiles() throws IOException {
        writeJson(tempDir, "zh_cn.json", "{\"a\": \"甲\"}");
        TranslationService service = serviceOver(tempDir);
        service.reload(true);
        assertEquals("added", service.translate("added", new Object[0]), "前置条件：新键尚不存在");

        writeJson(tempDir, "zh_cn_extra.json", "{\"added\": \"新增\"}");
        service.reload(true);

        assertEquals("新增", service.translate("added", new Object[0]));
    }

    // ------------------------------------------------------------------
    // 查询语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("占位符：%s 与 %1$s 位置参数都能格式化")
    void formatsPlaceholders() throws IOException {
        writeJson(tempDir, "zh_cn.json",
                "{\"seq\": \"%s 击败了 %s\", \"pos\": \"%2$s 被 %1$s 击败\"}");
        TranslationService service = serviceOver(tempDir);
        service.reload(true);

        assertEquals("Steve 击败了 Zombie",
                service.translate("seq", new Object[] {"Steve", "Zombie"}));
        assertEquals("Zombie 被 Steve 击败",
                service.translate("pos", new Object[] {"Steve", "Zombie"}));
    }

    @Test
    @DisplayName("缺失键返回 key 本身")
    void missingKeyReturnsKey() throws IOException {
        writeJson(tempDir, "zh_cn.json", "{\"known\": \"已知\"}");
        TranslationService service = serviceOver(tempDir);
        service.reload(true);

        assertEquals("unknown.key", service.translate("unknown.key", new Object[] {"x"}));
    }

    // ------------------------------------------------------------------
    // 故障语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("目录读取失败时保留上一份完整快照，不清空翻译")
    void ioFailureKeepsPreviousSnapshot() throws IOException {
        Path directory = tempDir.resolve("translate");
        writeJson(directory, "zh_cn.json", "{\"a\": \"甲\"}");
        TranslationService service = serviceOver(directory);
        service.reload(true);
        assertEquals("甲", service.translate("a", new Object[0]), "前置条件：应已加载");

        // 把目录换成同名普通文件：枚举目录会失败（IOException），而不是"没有内容"
        Files.delete(directory.resolve("zh_cn.json"));
        Files.delete(directory);
        Files.write(directory, "not a directory".getBytes(StandardCharsets.UTF_8));

        service.reload(true);

        assertTrue(service.isEnabled(), "I/O 失败不应改变启用状态");
        assertEquals("甲", service.translate("a", new Object[0]), "I/O 失败不应清空已有翻译");
    }
}
