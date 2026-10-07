# 鹊桥工具包

一个用于 Minecraft 服务端（插件 / 模组）与外部应用之间进行实时通信与事件分发的轻量级工具库。

- WebSocket 正反向连接。
- 统一的 JSON 请求 / 响应协议（自定义轻量协议）。
- 游戏内事件实时推送（`聊天`、`加入`、`离开`、`死亡`、`命令`、`成就`/`进度` 等）。
- 消息发送能力（`广播`、`ActionBar`、`Title` & `Subtitle`、`私聊`）。
- `Rcon` 支持。

## 快速开始

1. 在服务端启动完成后阶段创建并启动 Runtime：
   ```java
   QueQiaoRuntime runtime = QueQiaoRuntime.create(
       /* configurer     */ null,                       // 可选：启动期 Schema 注册
       /* platformContext */ new YourPlatformContext(), // 平台唯一接入点
       /* apiConfigurer  */ null                        // null = 启用内置的完整 API 批次
   );
   runtime.start();
   // 只有 start() 成功后再保存引用：启动失败时 Runtime 已自行清理已启动的资源
   this.runtime = runtime;
   ```
   类型为 `io.github.theword.queqiao.core.runtime.QueQiaoRuntime`。
2. 接口实现：
    - `io.github.theword.queqiao.core.platform.AbstractPlatformContext`：**平台唯一接入点**，
      描述"平台运行环境与跨平台公共能力"。
      实现其中的原语（JSON → 平台组件、在线玩家、按玩家发送、广播），
      并声明平台类型（`getServerType()`）与服务端版本（`getServerVersion()`）。
      标题与 ActionBar 有默认实现（表示"不支持"，返回 503），支持时覆盖即可。
      **不负责命令来源**——回执与权限判定属于 `CommandExecutionContext`（见下）。
    - `io.github.theword.queqiao.core.api.DefaultApis`：内置的协议 API 批次
      （`broadcast`/`send_msg`、`send_title`、`send_actionbar`、`send_private_msg`、
      `send_command`、`send_rcon_command`、`get_status`）。默认全部启用；
      想裁剪或追加自定义 API，在 `apiConfigurer` 里增删列表即可——
      **未注册的 api 一律返回 404**。
    - `io.github.theword.queqiao.core.command`：命令子系统。
      `CommandNode<NCS>` 是命令树节点（业务扩展点 `onExecute`），
      `CommandRouter<NCS>` 负责路径路由与 tab 补全，
      `CommandExecutionContext<NCS>` 表示**一次命令调用**。
      内置命令位于 `command.builtin`；第三方命令继承 `CommandNode` 并通过
      `CommandRouter.getRootCommand().addChild(...)` 注册（须在首次 dispatch 之前完成，
      之后命令树会被冻结）。
3. 在服务端关闭前调用：
   ```java
   runtime.shutdown();
   ```
4. 外部应用通过 WebSocket 发送 JSON 请求（含 `api`、`data`、可选 `echo`）。
5. 订阅所需事件：收到的事件是服务端主动推送。

> **迁移提示**：`GlobalContext` 已移除。原本通过它访问的能力改为从 Runtime 实例获取，
> 例如 `runtime.sendEvent(...)`、`runtime.getConfig()`、`runtime.getLogger()`；
> 命令层所需的依赖请从该 Runtime 显式取出后注入（`Config` / `Logger` /
> `WebsocketManager` / 重载动作），且命令树须在 `runtime.start()` 之后构建。
> 命令层**不再**需要 `AbstractPlatformContext`。

### 平台适配：实现一次命令调用的上下文

`CommandExecutionContext<NCS>` 的 `NCS` 是 **Native Command Source**（平台原生命令来源）。
平台适配层继承它并持有原生来源；Core 不认识任何平台类型：

```java
public final class XxxCommandExecutionContext
        extends CommandExecutionContext<NativeCommandSource> {

    public XxxCommandExecutionContext(NativeCommandSource nativeSource) {
        super(nativeSource);
    }

    @Override
    public void reply(String message) {
        // 用平台自己的 API 把文本发给 nativeSource
    }

    @Override
    public boolean hasPermission(String permission) {
        // 平台自己的权限判定
    }
}
```

`nativeSource` 是 `public final` 字段，在整次 invocation 内保持不变且不可为 null；
它既是平台适配层的工作对象，也是第三方命令访问平台特有能力时的入口。

## 接口说明

- `V2` 完整协议：[`ApiFox`](https://queqiao.apifox.cn/)：适用于 鹊桥 `0.3.0` 及以上版本
- `V1` 协议：[`ApiFox`](https://rxylncffzr.apifox.cn)：适用于 鹊桥 `0.2.7` 及以下版本
- `V1` 事件：[`QueQiao GitHub Wiki`](https://github.com/17TheWord/QueQiao/wiki/4.-%E5%9F%BA%E6%9C%AC%E4%BA%8B%E4%BB%B6%E7%B1%BB%E5%9E%8B)适用于 鹊桥 `0.2.7` 及以下版本

## 配置文件说明

文件：[`src/main/resources/config.yml`](./src/main/resources/config.yml)

## Rcon 支持

> Minecraft 原生的远程控制协议，允许通过网络发送命令到 Minecraft 服务器并获取响应。

- 依赖 `org.glavo:rcon-java:3.0`
- 为节约端口资源，直接将 `Rcon` 功能集成在本工具包中。
- 开放 `send_rcon_command` 接口供外部应用调用。

## 开发与测试

1. 克隆项目

    ```shell
    git clone https://github.com/17TheWord/queqiao-core.git
    ```

2. `JDK`：为支持 `1.7.10` - `1.16.5`（Java 8），项目编译目标为 **Java 8**。
   构建脚本显式设置了 `options.release = 8`，因此误用 `List.of()` 等 Java 9+ API
   会在编译期直接失败，而不会拖到 Java 8 运行时才 `NoSuchMethodError`。

3. 使用 `IDE` 打开项目（推荐 `IntelliJ IDEA`），或使用 `gradlew`。

    ```bash
    ./gradlew.bat test
    ./gradlew.bat build
    ```

## 构建与依赖

- 前往 `Release` 查看版本与构建产物。
- 新版本计划发布到 Maven Central，坐标为 `io.github.17theword.queqiao:core`；首个 Central 版本发布后可直接从 `mavenCentral()` 获取。
- 旧版本仍可使用 GitHub Packages 坐标 `com.github.theword.queqiao:queqiao-tool`，对应仓库凭证配置见 [GitHub Packages 文档](https://docs.github.com/zh/packages)。

Gradle (Kotlin DSL)：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.17theword.queqiao:core:<version>")
}
```

## 社群

- [`Discord`](https://discord.gg/SBUkMYsyf2)

## 许可证

本项目使用 [MIT](./LICENSE) 许可证开源。
