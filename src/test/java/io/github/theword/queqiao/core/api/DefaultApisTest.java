package io.github.theword.queqiao.core.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.theword.queqiao.core.platform.AbstractPlatformContext;
import io.github.theword.queqiao.core.support.PlatformStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultApis} 与 Api / PlatformApi 构造契约测试
 *
 * <p>锁定两件事：
 * <ol>
 *     <li><b>默认集合不变</b>：7 个 api 的名称与别名集合与重构前一致；</li>
 *     <li><b>依赖划分不变</b>：4 个需要平台上下文（{@link PlatformApi}），
 *         3 个不需要（{@link Api}）。</li>
 * </ol>
 *
 * <p>同时验证"缺少平台上下文"与"缺少 logger"都会在<b>构造期</b>快速失败
 * （{@link NullPointerException}，属运行期校验而非编译期强制）。
 *
 * <p>注意：本模块以 {@code release = 8} 编译，因此这里不使用 {@code Set.of()} 等 Java 9+ API。
 */
class DefaultApisTest {

    static {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultApisTest.class);

    private static final Set<String> EXPECTED_NAMES = new HashSet<>(Arrays.asList(
            "broadcast",
            "send_title",
            "send_actionbar",
            "send_private_msg",
            "send_command",
            "send_rcon_command",
            "get_status"));

    private static final Set<String> EXPECTED_PLATFORM_API_NAMES = new HashSet<>(Arrays.asList(
            "broadcast",
            "send_title",
            "send_actionbar",
            "send_private_msg"));

    private static List<Api<?, ?>> allApis() {
        return DefaultApis.all(
                PlatformStubs.noopPlatformContext(),
                PlatformStubs.newStatusCollector(LOGGER),
                PlatformStubs.rconExecutorReturning(""),
                LOGGER);
    }

    private static Api<?, ?> byName(List<Api<?, ?>> apis, String name) {
        return apis.stream()
                .filter(api -> name.equals(api.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("默认集合中未找到 api：" + name));
    }

    @Test
    @DisplayName("默认集合固定为 7 个 api，名称与别名均不变")
    void defaultSetIsStable() {
        List<Api<?, ?>> apis = allApis();

        assertEquals(7, apis.size(), "默认集合成员数量不应变化");

        Set<String> names = apis.stream().map(Api::name).collect(Collectors.toSet());
        assertEquals(EXPECTED_NAMES, names, "默认集合的 api 名称不应变化");

        assertEquals(
                Collections.singleton("send_msg"),
                byName(apis, "broadcast").aliases(),
                "broadcast 的别名应仍为 send_msg");
        assertTrue(
                apis.stream()
                        .filter(api -> !"broadcast".equals(api.name()))
                        .allMatch(api -> api.aliases().isEmpty()),
                "除 broadcast 外不应有其它 api 声明别名");
    }

    @Test
    @DisplayName("按是否依赖平台能力划分：4 个 PlatformApi + 3 个普通 Api")
    void platformApisAreSplitCorrectly() {
        List<Api<?, ?>> apis = allApis();

        Set<String> platformApiNames = apis.stream()
                .filter(api -> api instanceof PlatformApi)
                .map(Api::name)
                .collect(Collectors.toSet());
        assertEquals(
                EXPECTED_PLATFORM_API_NAMES,
                platformApiNames,
                "只有需要平台上下文的 api 才应继承 PlatformApi");

        assertFalse(byName(apis, "get_status") instanceof PlatformApi, "get_status 不应依赖平台上下文");
        assertFalse(byName(apis, "send_rcon_command") instanceof PlatformApi, "send_rcon_command 与平台无关");
        assertFalse(byName(apis, "send_command") instanceof PlatformApi, "send_command 无依赖");
    }

    @Test
    @DisplayName("GetStatusApi 不需要平台上下文即可构造")
    void getStatusApiDoesNotRequirePlatformContext() {
        // 只需 (logger, collector) 两个参数即可构造，这本身就是"不依赖平台上下文"的证明。
        // 注意：声明为 GetStatusApi 时 `instanceof PlatformApi` 会被编译器直接拒绝
        // （final 类且与 PlatformApi 无继承关系），这是编译期的额外保证；
        // 这里放宽到 Api<?, ?> 以保留一条运行期断言。
        Api<?, ?> api = new GetStatusApi(LOGGER, PlatformStubs.newStatusCollector(LOGGER));

        assertEquals("get_status", api.name());
        assertFalse(api instanceof PlatformApi, "GetStatusApi 不应依赖平台上下文");
    }

    @Test
    @DisplayName("PlatformApi 缺少平台上下文时构造期快速失败")
    void platformApiRejectsNullContext() {
        assertThrows(NullPointerException.class, () -> new BroadcastApi(LOGGER, null));
        assertThrows(NullPointerException.class, () -> new SendTitleApi(LOGGER, null));
        assertThrows(NullPointerException.class, () -> new SendActionBarApi(LOGGER, null));
        assertThrows(NullPointerException.class, () -> new SendPrivateMessageApi(LOGGER, null));
    }

    @Test
    @DisplayName("所有 Api 都拒绝 null logger（由基类统一校验）")
    void everyApiRejectsNullLogger() {
        AbstractPlatformContext<?, ?, ?> platform = PlatformStubs.noopPlatformContext();

        assertThrows(NullPointerException.class, () -> new BroadcastApi(null, platform));
        assertThrows(NullPointerException.class, () -> new SendTitleApi(null, platform));
        assertThrows(NullPointerException.class, () -> new SendActionBarApi(null, platform));
        assertThrows(NullPointerException.class, () -> new SendPrivateMessageApi(null, platform));
        assertThrows(NullPointerException.class, () -> new SendCommandApi(null));
        assertThrows(
                NullPointerException.class,
                () -> new GetStatusApi(null, PlatformStubs.newStatusCollector(LOGGER)));
        assertThrows(
                NullPointerException.class,
                () -> new SendRconCommandApi(null, PlatformStubs.rconExecutorReturning("")));
    }

    @Test
    @DisplayName("返回的列表可变，调用方可自由增删")
    void returnedListIsMutable() {
        List<Api<?, ?>> apis = allApis();

        apis.clear();

        assertTrue(apis.isEmpty(), "DefaultApis 应返回可变列表");
    }
}
