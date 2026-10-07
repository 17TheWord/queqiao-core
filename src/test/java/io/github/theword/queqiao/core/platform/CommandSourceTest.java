package io.github.theword.queqiao.core.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.theword.queqiao.core.command.SubCommand;
import io.github.theword.queqiao.core.constant.CommandConstant;
import io.github.theword.queqiao.core.support.FakeCommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandSource} 与命令层协作测试
 *
 * <p>覆盖三件事：
 * <ol>
 *     <li>权限通过 → 命令正常执行，回执落到命令来源；</li>
 *     <li>权限拒绝 → 命令体不执行，只回执"无权限"；</li>
 *     <li>{@link CommandSource#NONE} 是空对象：回执被静默吞掉，权限判定恒为 true。</li>
 * </ol>
 */
class CommandSourceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandSourceTest.class);

    /**
     * 记录被执行的命令体（用于区分"命令体是否执行"）
     */
    private static final class ProbeCommand extends SubCommand {

        private final List<String> executions = new ArrayList<>();

        private ProbeCommand() {
            super(LOGGER);
        }

        @Override
        public String getName() {
            return "probe";
        }

        @Override
        public String getDescription() {
            return "测试用命令";
        }

        @Override
        protected void onExecute(CommandSource source, List<String> args) {
            executions.add(String.join(",", args));
        }

        private List<String> getExecutions() {
            return executions;
        }
    }

    // ------------------------------------------------------------------
    // 权限通过 / 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("权限通过：命令体执行，回执落到命令来源")
    void permissionGrantedExecutesCommand() {
        ProbeCommand command = new ProbeCommand();
        FakeCommandSource source = new FakeCommandSource(true);

        int signal = command.execute(source, Arrays.asList("a", "b"));

        assertEquals(CommandConstant.SUCCESS_SIGNAL, signal);
        assertEquals(Collections.singletonList("a,b"), command.getExecutions(), "命令体应被执行一次");
        assertTrue(source.hasReplyContaining("============ 鹊桥 ==========="), "应有起始分隔线");
        assertTrue(source.hasReplyContaining("============================"), "应有结束分隔线");
    }

    @Test
    @DisplayName("权限拒绝：命令体不执行，只回执无权限")
    void permissionDeniedSkipsCommand() {
        ProbeCommand command = new ProbeCommand();
        FakeCommandSource source = new FakeCommandSource(false);

        int signal = command.execute(source, Collections.<String>emptyList());

        assertEquals(CommandConstant.FAIL_SIGNAL, signal);
        assertTrue(command.getExecutions().isEmpty(), "无权限时命令体不应执行");
        assertTrue(source.hasReplyContaining("您没有权限执行此命令。"), "应回执无权限提示");
        assertFalse(source.hasReplyContaining("============ 鹊桥 ==========="), "无权限时不应输出分隔线");
    }

    @Test
    @DisplayName("命令体抛异常：回执错误信息并返回失败信号")
    void commandBodyFailureIsReported() {
        SubCommand throwing = new SubCommand(LOGGER) {
            @Override
            public String getName() {
                return "boom";
            }

            @Override
            public String getDescription() {
                return "会抛异常的命令";
            }

            @Override
            protected void onExecute(CommandSource source, List<String> args) {
                throw new IllegalStateException("炸了");
            }
        };
        FakeCommandSource source = new FakeCommandSource(true);

        int signal = throwing.execute(source, Collections.<String>emptyList());

        assertEquals(CommandConstant.FAIL_SIGNAL, signal);
        assertTrue(source.hasReplyContaining("命令执行出错"), "应回执错误信息");
    }

    // ------------------------------------------------------------------
    // NONE 空对象
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NONE.reply 静默忽略，不抛异常")
    void noneReplyIsSilentlyIgnored() {
        CommandSource.NONE.reply("没有人会看到这条消息");
        CommandSource.NONE.reply(null);
    }

    @Test
    @DisplayName("NONE.hasPermission 恒为 true（内部调用不做权限拦截）")
    void noneAlwaysHasPermission() {
        assertTrue(CommandSource.NONE.hasPermission("queqiao.server.info"));
        assertTrue(CommandSource.NONE.hasPermission(null));
    }

    @Test
    @DisplayName("用 NONE 执行命令：不抛异常，且命令体照常执行")
    void noneCanDriveCommandExecution() {
        ProbeCommand command = new ProbeCommand();

        int signal = command.execute(CommandSource.NONE, Collections.<String>emptyList());

        assertEquals(CommandConstant.SUCCESS_SIGNAL, signal, "内部调用不应被权限拦截");
        assertEquals(Collections.singletonList(""), command.getExecutions());
    }
}
