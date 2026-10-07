package io.github.theword.queqiao.core.command;


import io.github.theword.queqiao.core.config.Config;
import io.github.theword.queqiao.core.runtime.ReloadResult;
import io.github.theword.queqiao.core.utils.WebsocketManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 命令路由：路径查找 / dispatch / tab completion
 *
 * <p><b>职责</b>：把平台传入的原始参数数组按命令树逐层匹配到目标节点并执行；
 * 以及基于同样的匹配规则给出 tab 补全候选。
 *
 * <p><b>不做 parse</b>：参数切分由平台侧的 command API（如 Brigadier）完成，
 * Core 只接收已经切分好的 {@code String[]}。因此本类叫 Router 而不是 Dispatcher。
 *
 * <p><b>依赖由构造器显式传入</b>，不从静态全局上下文获取。
 *
 * <p><b>不依赖平台上下文</b>：权限过滤与回执都已归属 {@link CommandExecutionContext}，
 * 因此本类只认识 {@link CommandExecutionContext}，不认识任何平台类型。
 *
 * <h2>注册生命周期</h2>
 * <pre>
 * 1. 构造 CommandRouter（内部构建 QueQiao 内置命令树）
 * 2. registration phase：平台可继续 addChild 注册第三方命令
 * 3. 首次 execute / tabComplete 时自动 freeze（幂等）
 * 4. dispatch phase：结构不可再修改
 * </pre>
 * 见 {@link CommandNode#freeze()}。
 *
 * @param <NCS> Native Command Source，平台原生命令来源类型
 */
public class CommandRouter<NCS> {

    private final RootCommand<NCS> rootCommand;

    /**
     * 构造命令路由
     *
     * @param logger           日志实现，不得为 null
     * @param config           配置运行时状态，不得为 null
     * @param websocketManager WebSocket 管理器（须在 Runtime.start() 之后获取），不得为 null
     * @param reloadAction     触发 Runtime 重载的动作，返回重载结果，不得为 null
     */
    public CommandRouter(
            Logger logger,
            Config config,
            WebsocketManager websocketManager,
            Supplier<ReloadResult> reloadAction) {
        this.rootCommand = new RootCommand<>(logger, config, websocketManager, reloadAction);
    }

    /**
     * 获取根命令节点
     *
     * <p>平台适配器用它把 Core 命令树映射到平台自己的命令 API；
     * 也可在 dispatch 开始前用它注册第三方命令。
     *
     * @return 根命令节点
     */
    public RootCommand<NCS> getRootCommand() {
        return rootCommand;
    }

    /**
     * 执行命令
     *
     * <p>首次调用会自动冻结命令树结构（见类级注册生命周期）。
     *
     * @param context 命令执行上下文，不得为 null
     * @param args    已切分的命令参数，不得为 null（无参数传空数组）
     * @return 执行信号
     * @throws NullPointerException context 或 args 为 null
     */
    public int execute(CommandExecutionContext<NCS> context, String[] args) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(args, "args");

        // 幂等：首次调用冻结整棵树，之后为 O(1) 早退
        rootCommand.freeze();

        if (args.length == 0) {
            return rootCommand.execute(context, new ArrayList<>());
        }

        CommandNode<NCS> current = rootCommand;
        int index = 0;

        // 尝试逐层向下查找匹配的子命令
        while (index < args.length) {
            String arg = args[index];
            CommandNode<NCS> next = null;

            for (CommandNode<NCS> child : current.getChildren()) {
                if (child.getName().equalsIgnoreCase(arg)) {
                    next = child;
                    break;
                }
            }

            if (next != null) {
                current = next;
                index++;
            } else {
                // 找不到匹配的子命令，停止查找
                break;
            }
        }

        // 将剩余的参数传递给最终找到的命令
        // 例如 /queqiao client list，匹配到 list 命令，剩余参数为空
        // 例如 /queqiao client reconnect all，匹配到 reconnect 命令，剩余参数为 ["all"] (假设 reconnect 没有子命令 all)
        List<String> remainingArgs = new ArrayList<>();
        if (index < args.length) {
            remainingArgs.addAll(Arrays.asList(args).subList(index, args.length));
        }

        return current.execute(context, remainingArgs);
    }

    /**
     * Tab 补全
     *
     * <p>首次调用会自动冻结命令树结构（见类级注册生命周期）。
     *
     * @param context 命令执行上下文，不得为 null
     * @param args    已切分的命令参数，不得为 null
     * @return 补全候选列表；路径中断时返回空列表
     * @throws NullPointerException context 或 args 为 null
     */
    public List<String> tabComplete(CommandExecutionContext<NCS> context, String[] args) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(args, "args");

        rootCommand.freeze();

        if (args.length == 0) {
            return Collections.emptyList();
        }

        CommandNode<NCS> current = rootCommand;
        // 遍历到倒数第二个参数，找到“当前正在输入的参数”的父命令
        for (int i = 0; i < args.length - 1; i++) {
            String arg = args[i];
            boolean found = false;
            for (CommandNode<NCS> child : current.getChildren()) {
                if (child.getName().equalsIgnoreCase(arg)) {
                    current = child;
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 路径中断，无法补全
                return Collections.emptyList();
            }
        }

        // 最后一个参数是用户正在输入的内容
        String lastArg = args[args.length - 1].toLowerCase();

        // 返回匹配前缀的子命令名称，并过滤无权限的命令
        return current.getChildren().stream()
                .filter(child -> context.hasPermission(child.getPermissionNode()))
                .map(CommandNode<NCS>::getName)
                .filter(name -> name.toLowerCase().startsWith(lastArg))
                .collect(Collectors.toList());
    }
}
