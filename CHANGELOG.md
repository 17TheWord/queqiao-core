# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 说明：本文件记录**面向使用者的行为变更**。内部重构若不影响公开行为则不在此列出。
> `[Unreleased]` 段落中的内容将在发布时改为对应版本号。

---

## [Unreleased]

本段包含三批改造：WebSocket 生命周期（WS-A~WS-F）、协议分发、以及**配置系统重写**。
**其中「破坏性变更」一节请务必阅读**——协议状态码、部分响应字段、配置读取方式与配置类的公开 API 都发生了变化。

### ⚠️ 破坏性变更

#### 1. 协议响应状态码变化

状态码此前无法区分"调用方请求错误"与"服务端内部错误"，现已按语义重新划分：

| 场景 | 旧状态码 | 新状态码 |
| --- | --- | --- |
| 请求体不是合法 JSON | `500` | `400` |
| 请求体为字面量 `null` | `500` | `400` |
| 缺少 / 空白 `api` 字段 | `404`（提示"未知 API：null"） | `400` |
| `api` 未注册 | `404` | `404`（不变） |
| Rcon 未启用 / 未连接 | `400` | **`503`** |
| Rcon 命令已下发但执行失败 | `400` | **`500`** |
| Rcon 命令为空 | 未校验 | `400` |
| Title 的 `fade_in` / `stay` / `fade_out` 为负数 | 未校验 | `400` |
| Title 的时长超过 72000 ticks（1 小时） | 未校验 | `400` |
| `send_private_msg` 的 `nickname` 为纯空白 | 视为有效 | `400` |

**升级指引**：若你的客户端按状态码分支处理，请更新判断逻辑。
特别注意 Rcon 相关的 `400` 已拆分为 `503`（服务不可用，可重试）与 `500`（服务端错误）。

#### 2. 错误响应不再回传原始请求体

解析失败时，响应 `data` 中的 `rawJsonMessage` 字段**已移除**。

旧行为会把整个请求体原样塞回响应，既放大响应体积，也可能把敏感内容回显给调用方。
调试时请开启 `debug` 配置，日志中会输出**已脱敏并截断**的请求内容。

**升级指引**：若客户端依赖 `data.rawJsonMessage` 排查问题，请改用服务端 debug 日志。

#### 3. `get_status` 结果新增最长 2 秒缓存

`get_status` 会在连接读线程上同步执行一次 Minecraft Server List Ping（socket 超时 3 秒）。
为避免高频轮询反复占用读线程，现加入 **2 秒 TTL 快照缓存**。

**影响**：2 秒内的重复请求返回同一份快照，`timestamp` 最多滞后 2 秒。
**升级指引**：若需要"绝对实时"的状态，请把轮询间隔设为大于 2 秒，或接受该滞后。

#### 4. 构造器签名变更（源码级）

仅影响**直接构造**这些类的代码；通过 `GlobalContext.init(...)` 使用的平台实现不受影响。

| 类 | 变化 |
| --- | --- |
| `WsClient` | 新增 `ScheduledExecutorService` / `ReconnectPolicy` / `HandleProtocolMessage` 参数；移除 `gson`、`reconnectMaxTimes`、`reconnectInterval` |
| `WsServer` | 以 `HandleProtocolMessage` 替换 `gson` 参数 |
| `WebsocketManager` | 新增 `HandleProtocolMessage` 参数 |
| `ProtocolRouter` | 构造器改为 `(Logger, Collection<Api<?, ?>>)`；不再自行构造处理器 |

#### 5. 配置系统重写为「Schema 驱动」的单文件模型

配置只有一个文件：**`config.yml`**（**不再生成 `config.example.yml`**）。
配置项的路径、类型、默认值、取值范围与注释集中声明在代码内的 **Schema**（`ConfigKeys`）中，
读取 / 校验 / 生成 / 持久化分别由单一职责组件承担。

| 行为 | 旧 | 新 |
| --- | --- | --- |
| `config.yml` 缺失 / 为空 | 用内置模板生成 | **生成**一份带注释的完整 `config.yml`（保证首次启动可用） |
| `config.yml` 缺失字段 | **重写文件**补默认值（丢注释 / 顺序 / 格式） | 仅在**内存**补默认值，文件不动 |
| `config.yml` 含未知字段 | **删除**未知字段 | **保留** + 告警（列出字段路径） |
| YAML 语法错误 / 根节点不是 Map | 当作空配置 → **用默认值覆盖原文件** | 原文件**绝不被覆盖**，抛错并**终止启动** |
| 字段类型 / 取值范围错误 | 部分静默接受 | 抛错并**终止启动**（指明字段路径与取值范围） |
| 用户注释 | 每次同步即丢失 | **完整保留**（正常启动不写文件） |

**升级提示**：
- 配置目录**不会**再多出 `config.example.yml`（该文件已删除）。想了解当前版本支持哪些配置项、
  取值范围与说明，请查看发布说明，或直接看 `config.yml` 首次生成时的注释。
- 配置存在 **YAML 语法错误**或**字段类型 / 范围错误**时，程序**不再带默认值继续启动，而是直接启动失败**。
  这是有意为之：把错误配置静默换成默认值，会造成"服务看起来正常、实际配置没生效"，
  比启动失败更难排查。失败时原文件**保持不动**，修正后即可正常启动。
- 若你有依赖"程序自动删除未知字段"的用法，请注意该行为已取消（这是修复，不是回归）。

#### 6. 配置 API 变更（源码级）

配置状态现在只有一个来源：`Config`（配置运行时值存储）。
读取方式统一为 `GlobalContext.getConfig().get(ConfigKeys.XXX)`。

| 移除 | 替代 |
| --- | --- |
| `CommonConfig` | `ConfigKeys`（Schema 声明）+ `ConfigLoader`（文档 → 运行时） |
| `ConfigFieldRules` | `ConfigKey` 上的 `validator`，如 `ConfigValidators.range(1, 65535)` |
| `ConfigSynchronizer`（旧版，模板合并 + 写盘） | `config.sync.ConfigChecker` / `ConfigSynchronizer`（**能力已保留，尚未接线**） |
| `WebSocketServerConfig` / `WebSocketClientConfig` / `SubscribeEventConfig` / `RconConfig` | `ConfigKeys.WebSocket.*` / `ConfigKeys.WebSocketClient.*` / `ConfigKeys.SubscribeEvent.*` / `ConfigKeys.Rcon.*` |
| `SyncReport` | `config.sync.ConfigSyncResult` / `ConfigCheckResult` |
| `Config.getWebsocketServer()` 等嵌套 getter | `Config.get(ConfigKeys.WebSocket.HOST)` 等 |
| `Config.isDebug()` 等标量 getter | `Config.get(ConfigKeys.DEBUG)` 等 |
| `Config.loadConfig(isModServer, logger, baseDirectory)` | 由 `QueQiaoRuntime.start()` 按固定顺序完成（先加载配置，再启动依赖配置的服务） |
| `Config.defaults(logger)` | `new Config(registry)`：未加载任何文档时 `get` 即返回 Schema 默认值 |

**升级提示**：`GlobalContext.getConfig()` 的**返回类型名仍是 `Config`**，但语义已完全改变——
它不再是原来的 POJO 门面，而是"配置运行时值存储"。此前依赖 `config.getWebsocketServer().getHost()`
这类调用链的代码需要改写为 `config.get(ConfigKeys.WebSocket.HOST)`。

#### 7. 协议层改为「Api 注册制」，两个 handle 抽象类被移除

协议处理从"每个接口一个 Handler 类 + 平台实现一个胖接口"改为
"**Api 自描述 + 由使用方决定注册哪些**"。

| 移除 | 替代 |
| --- | --- |
| `handle.HandleApiService`（4 个方法的胖接口） | `platform.AbstractPlatformContext` 的 `broadcast` / `sendTitle` / `sendActionBar` / `sendPrivateMessage` |
| `handle.HandleCommandReturnMessageService` | `platform.AbstractPlatformContext` 的 `checkPermission` / `returnCallBackMessage` |
| `protocol.AbstractProtocolHandler` 及全部 7 个 Handler | `api.Api<P, R>`，内置实现见 `api.DefaultApis` |
| `api.ApiRegistry`（此前未接线） | 直接向 `ProtocolRouter` 传入 Api 集合 |

**`QueQiaoRuntime.create` 收敛为 3 个参数**：

```java
QueQiaoRuntime.create(
    Consumer<ConfigRegistry> configurer,             // 可空：启动期 Schema 注册
    AbstractPlatformContext<?, ?, ?, ?> platformContext,
    Consumer<List<Api<?, ?>>> apiConfigurer);        // 可空：null = 启用内置完整批次
```

- `modServer` / `serverType` / `serverVersion` 不再作为参数传入，改由
  `AbstractPlatformContext#isModServer()` / `getServerType()` / `getServerVersion()` 提供。
  后两者只要求 **`start()` 之后**可用，因此平台可以先 `create()`、等服务器就绪再 `start()`。
- `ProtocolRouter` 构造器改为 `(Logger, Collection<Api<?, ?>>)`；
  api 名重复会在**构造期**抛 `IllegalArgumentException`（取代此前的静默覆盖）。
- **未注册的 api 返回 404**——"自愿注册"直接复用既有的 404 分支，无需新逻辑。

**行为变化**：
- `send_private_msg` 的响应改由 core 构造：找不到玩家返回 `Target player not found.`；
  成功时 `target_player` 只填 `nickname` 与 `uuid`（不再由平台回填其它字段）。
- 平台未实现标题 / ActionBar 时，`send_title` / `send_actionbar` 返回 **503**（此前落到 500）。
- `AbstractPlatformContext#checkPermission` / `returnCallBackMessage` 的入参类型是 `Object`，
  平台侧需保证传入的确实是本平台的命令源类型；类型不符会抛 `ClassCastException`。

### 新增

- **Schema 驱动的配置系统**（单文件 `config.yml`）：
  - `ConfigKeys` —— 全部配置项的**唯一声明处**（路径 / 类型 / 默认值 / 取值范围 / 注释）；
  - `ConfigKey` / `ConfigRegistry` / `ConfigTree` / `ConfigNode` / `ConfigSectionNode` —— Schema 模型与节点树；
  - `Config` —— 配置运行时值存储（`get` / `set` / `reset` / `contains` / `isDefault` / `load` / `snapshot`）；
  - `ConfigCodec`（`Boolean` / `Integer` / `String` / `List`）、`ConfigValidator` / `ConfigValidators`、
    `ConfigValueSource`（`DEFAULT` / `USER`）、`ConfigSnapshot`、`ConfigValidationException`。
- `ConfigFileState`（`MISSING` / `EMPTY` / `VALID` / `INVALID` 四态）、`ConfigFileReader`（文件 + YAML 解析）、
  `ConfigDocument`（路径解析 + 未知字段保留）、`ConfigLoader`（文档 → 运行时，**原子提交**）、
  `ConfigWriteSnapshot` / `ConfigWriter`（Runtime → YAML）、`ConfigStore`（**原子写** + 5 份备份轮换）。
- `config.sync` 包：`ConfigChecker` / `ConfigSynchronizer` / `ConfigSyncPlan` / `ConfigSyncResult` /
  `ConfigSchemaIndex` / `ConfigPaths` / `ConfigIssue`——配置查漏补缺（**只算计划、不写盘**）。
  作为**保留能力**提供（与运行时解耦、可独立测试），当前启动路径不调用。
- `addons` 扩展命名空间：供 Addon 存放自己的配置。Core 只校验 `addons` 本身是 Mapping，
  内部结构不校验、不删除、不告警；`addons` 下的未知字段与核心未知字段**分开记录**。
- **`Tool.format(String template, Object... args)`**：支持 `{}` 占位符的格式化工具。
  项目内的共享消息常量（`WebsocketConstantMessage`、`CommandConstant`）统一使用 `{}`（与 SLF4J 一致），
  但同一常量往往还要用于非日志场景（关闭原因、命令输出），而 `String.format` 需要 `%s`——
  把 `{}` 常量交给它会导致**占位符原样输出且参数被静默忽略**（见「修复」第一条）。
  约定：**本项目自己的消息一律用 `Tool.format`**；
  **来自 Minecraft 语言文件的翻译模板必须继续用 `String.format`**（它使用 `%1$s` 位置参数，`Tool.format` 不支持）。
- `protocol.RconCommandExecutor`：RCON 命令执行器函数式接口。协议层通过它请求执行 RCON，
  不再直接依赖 `GlobalContext` 或 Rcon 实现细节。
- `ProtocolConstants.Status.SUCCESS`（`200`）。
- `BaseEvent.fillServerContext(serverName, serverVersion, serverType)`：发布前填充服务器上下文。
- `WebsocketConstantMessage.SHUTDOWN`：整体关闭时使用的关闭原因（纯文本）。
- `ReconnectPolicy`：重连退避策略纯组件，无网络与线程依赖，可独立测试。
- `ReconnectReason`：重连原因枚举（`REMOTE_CLOSE` / `MANUAL`）。
- `WebSocketUrlNormalizer`：WebSocket URL 归一化（trim / 去空 / 去重 / `ws://` `wss://` scheme 校验）与日志脱敏。
- `LogSanitizer`：日志脱敏工具，按字段名递归遮蔽 `access_token` / `authorization` / `token` / `password` / `passwd` / `secret` / `api_key` / `apikey`，并按 1 KB 截断。
- `ProtocolConstants.Status.SERVICE_UNAVAILABLE`（`503`）。
- `ProtocolException.internalError(String, Object)` / `ProtocolException.serviceUnavailable(String, Object)`。
- `RconException.Kind`（`DISABLED` / `DISCONNECTED` / `COMMAND_FAILED`），用于区分 Rcon 失败性质。
- `Tool.isDebugEnabled()` 由 private 改为 public，便于调用方在构造昂贵的日志参数前先行判断。
- `WsClient#isReconnectInProgress()` / `getReconnectAttempts()` / `isStopped()`：重连状态诊断接口。

### 变更

- **正常启动不再写 `config.yml`**：只有文件**缺失或为空**时才会生成一份带注释的完整配置；
  文件正常时**只读不写**——缺失字段在**内存**中取 Schema 默认值，用户文件一个字都不动。
  真正需要覆盖用户文件的场景（未来的配置迁移、显式"同步配置"命令）由
  `ConfigChecker` + `ConfigSynchronizer` + `ConfigWriter` 承担，且必须走"备份 + 原子写"。
- **未知配置字段一律保留**：不再因为"Schema 里没有"就删除。用户可能在参考其它版本文档、
  使用第三方扩展、或提前写好未来配置；自动删除会让用户以为"程序吃掉了自己的配置"。
  未知字段会记录 WARN 并列出**具体字段路径**，但不参与运行时配置；
  `addons` 下的未知字段按"扩展配置"处理（INFO 级），与核心未知字段区分。
- **`addons` 扩展命名空间**：Core 只校验 `addons` 本身是 Mapping；其内部结构完全交给 Addon，
  既不校验也不告警。`addons` 本身不是 Mapping 时会被显式识别为结构不合法并报错。
- **字段类型 / 范围错误改为"明确报错"**：取值范围集中声明在 Schema 上
  （`websocket_server.port` / `rcon.port` 1~65535；`reconnect_interval` 1~3600；
  `reconnect_max_times` 0~1000）。错误信息包含**字段完整路径**与**允许范围**，
  不再只给"共 N 项"这类无法定位的汇总。
- **用户配置错误与程序缺陷严格区分**：只有显式识别的配置错误（`ConfigValidationException`）
  才会按"配置问题"处理；其它 `RuntimeException` 一律**原样上抛**（带堆栈），
  绝不静默转换成默认配置——否则真实缺陷会被伪装成"配置问题"而永远查不出来。
- **传输层不再依赖全局状态**：`WebsocketManager` 改为通过构造器接收 `Config` 快照，
  reload 时调用 `restart(Config, Object)` 传入新快照。
  此前它会在 14 处直接读取 `GlobalContext.getConfig()`。
- **协议层不再依赖全局状态**：`ProtocolRouter` 与各处理器改为通过构造器接收
  `HandleApiService`（平台 API）与 `RconCommandExecutor`（RCON 执行器），
  不再访问 `GlobalContext.getHandleApiService()` / `GlobalContext.sendRconCommand(...)`。
- **事件的服务器上下文改为"发布时填充"**：`BaseEvent` 的 `server_name` / `server_version` / `server_type`
  不再在字段初始化器中读取全局状态，而由 `GlobalContext.sendEvent(...)` 在序列化前调用
  `fillServerContext(...)` 填充。
  **升级提示**：若代码自行构造事件后**不经发布路径**直接序列化，
  这三个字段将由"构造时的全局值"变为 `null`。
- **构造器签名变更**：`WebsocketManager`（新增 `Config`）、
  `ProtocolRouter`（新增 `HandleApiService` 与 `RconCommandExecutor`）、
  `HandleProtocolMessage`（新增 `HandleApiService` 与 `RconCommandExecutor`）、
  `AbstractProtocolHandler` 及全部 7 个处理器（新增 `HandleApiService`）。
  仅影响**直接构造**这些类的代码；通过 `GlobalContext.init(...)` 使用的平台实现不受影响。
- `MinecraftPingClient` 改用 `GsonUtils.getGson()`，不再经 `GlobalContext`。
- **`Authorization` 仍支持通过 URL query 传递，并已在 javadoc 中说明其安全代价**：
  浏览器的 WebSocket API **无法设置自定义请求头**（`new WebSocket(url)` 不接受 headers 选项），
  去掉 query 支持会让浏览器客户端完全无法接入，因此保留。
  但 URL 会进入反向代理日志、监控 / APM、浏览器历史与抓包工具，
  **能设置请求头的客户端应优先使用请求头**；仅浏览器等无法设置请求头的场景使用 query。
  失败日志（debug 级）会指出凭据来源，便于运维发现 token 被放进 URL 的情况。
- **握手字段的解码策略改为按字段显式决定**（不再共用一套解析）：
  `x-self-name` 两个来源都解码一次（客户端约定对该字段编码）；
  `x-client-origin` 与 `Authorization` 的请求头值按原样、query 值解码一次
  （请求头由客户端原样发送，若也解码会破坏 token 中合法的 `%` / `+`；
  query 值在 URL 中必然被编码，例如空格会变成 `+`）。
- **未配置 `access_token` 却绑定非回环地址时输出告警**：
  该组合意味着任何能访问该端口的人都可发送消息并执行 Rcon 命令。
  仅告警不拒绝——"置于反向代理 / 私有网络之后由外层鉴权"是合法部署。
  `config.yml` 相应位置也补充了说明。
- **凭据校验使用常量时间比较**（`MessageDigest.isEqual`）替代 `String.equals`。
- **未携带凭据与凭据错误现在有不同的日志与关闭原因**：
  此前两者共用一条日志（`Authorization Header is wrong`），排查时无法区分；
  现在未携带凭据的关闭原因为 `Authorization is required`。
- **`client reconnect` 的结束提示改为"已安排重连，实际结果请查看日志"**：
  该命令只是把重连任务**投递到调度器**，并不保证连接成功，此前提示"已重新连接"会误导执行者。
  同时起始提示由陈述句"存在未处于打开状态的 Websocket Client..."改为动作描述"正在重连未打开的 Websocket Client..."。
- **`server info` 对无法获取远端地址的连接输出"来自未知地址的连接"**，不再抛 NPE；
  远端地址改用 `getHostString()` / `getPort()` 获取（与 `WsServer` 保持一致）。
- **`server info` 的连接集合、`client list` 的配置 URL 列表均只读取一次**：
  此前 `getConnections()` 被调用三次、`getConfig().getWebsocketClient().getUrlList()` 在循环条件里每轮调用两次，
  期间集合/列表可能变化，会出现"显示 N 个却列出 M 条"的自相矛盾输出。
- **`WsServer` 的连接丢失检测周期显式设为 60 秒**，不再依赖 Java-WebSocket 的默认值
  （该检测会 ping 客户端，超时未收到 pong 时关闭连接）。
- **`WsServer` 构造器把 `serverName` / `accessToken` 的 `null` 归一化为空串**。
  `accessToken` 为空表示不鉴权——即传 `null` 与传 `""` 现在等价，不会再因 NPE 进入异常路径。
- **明确协议只支持文本帧**：二进制帧由 Java-WebSocket 的空实现静默忽略，本项目有意不覆写
  （覆写只能多打一条日志、不改变行为，反而会在客户端持续发送二进制帧时刷屏）。
  若排查"客户端称已发送但服务端无响应"，这是需要确认的方向之一。
- **`GlobalContext.init()` 现在幂等**：若已初始化，会**先关闭旧实例**再创建新实例。
  此前重复初始化会直接覆盖运行时引用，导致旧实例持有的 WebSocket 连接、共享重连调度器、
  Rcon 连接与线程变成无法再关闭的孤儿对象。对"插件热重载"这类场景现在是正确行为。
- **未初始化 / 已关闭状态下上下文可用**：`GlobalContext.getConfig()` 与 `getLogger()`
  不再返回 `null`，而是返回默认配置与不输出内容的 NOP 日志；
  `shutdown()` / `sendEvent()` 在该状态下为安全空操作。
  依赖"未初始化时读配置会抛 NPE"来探测状态的代码需调整。
- **重连机制统一**：自动重连与手动重连共用同一套 pipeline（`requestReconnect`），
  引入代际号（generation）与 `reconnectInProgress` 非阻塞互斥，保证同一 Client 任意时刻
  最多一个待执行、最多一个正在执行的重连。
- **共享重连调度器**：由 `WebsocketManager` 持有单个调度器（corePoolSize = 2、daemon、命名线程），
  取代"每个 Client 一条调度线程"。调度器仅在 Manager 永久销毁时关闭。
- **连接成功时清理**：`onOpen` 会取消待执行的重连任务、推进代际号并重置连续失败计数，
  避免"迟到的重连任务"把刚建立的健康连接拆掉。
- **重连判据**：不再依赖 Java-WebSocket 的 `remote` 参数（连接失败时它也是 `false`），
  改用 `stopped` 表达"是否仍需要维持连接"。
- **`connectionLostTimeout` 显式设置**为 60 秒，不再依赖库默认值。
- **协议层不再依赖全局状态**：`ProtocolRouter` 与各 handler 的日志实现改为构造器注入，
  协议分发可脱离 `GlobalContext` 独立测试。
- **`sendEvent` 分发**：先取接收方快照再序列化；无任何接收方时跳过序列化。
  （序列化与分发仍在调用线程同步完成——实测单次约 1~2.3 µs，未引入异步队列。）
- **日志级别调整**：`get_status` 请求日志由 `INFO` 降为 `DEBUG`（该接口可能被高频轮询）。
- **Rcon 命令日志脱敏**：`INFO` 级别只记录命令长度，完整命令内容仅在 `debug` 级别输出。
- **Rcon 日志来源修正**：错误日志按实际来源输出 `http` / `websocket`，不再固定写 `webSocket`。
- **URL 归一化**：配置中的 URL 会 trim、去空、去重，并校验 scheme；
  非 `ws://` / `wss://` 的地址在加载阶段即被拒绝并告警（不再拖到连接阶段才报错）。
- **`EmptyPayload` 的 `data` 字段保持容忍**：对无负载的 api（如 `get_status`），
  `data` 会被忽略而不校验——客户端习惯性发送 `"data": {}` 或 `"data": null`，拒绝只会造成无谓的兼容性破坏。
- **`nickname` 判空改为 `trim()` 后判断**，纯空白字符串视为未提供；
  `nickname` 与 `uuid` 同时提供时的优先级仍由平台实现决定。

### 修复

- **配置文件语法错误会导致整份配置被默认值覆盖**（严重）：此前 YAML 解析失败时被当作"空配置"，
  于是所有字段都被视为缺失、进而用默认值重写整个文件——用户少一个缩进就会丢掉全部配置。
  现在严格区分 `EMPTY` 与 `INVALID`：**解析失败时原文件绝不被覆盖**，
  并明确报错、终止启动（不再用默认值静默顶替）。
- **未知字段被自动删除**：此前同步逻辑会删掉所有不在模板中的键，破坏 Addon 生态与用户自定义字段。现改为保留 + 告警。
- **配置重写丢失用户注释**：此前每次判定"有变化"就用 snakeyaml 重新 dump 整个文件，
  注释、空行、字段顺序全部丢失。现在正常启动不重写文件。
- **数值范围无校验**：`port: -1`、`reconnect_interval: 0`（会导致立即重连风暴）等此前会被直接接受，
  现在会识别为非法并报错，错误信息中给出**字段路径与允许范围**。
- **`RconClient.connect()` 在已连接状态下仍继续建立新连接**（A#29）：
  "已连接"分支缺少 `return`，会执行 `new Rcon(...)` 覆盖 `client` 字段，
  旧连接的 socket 不被关闭 → **socket 泄漏**，且日志自相矛盾（说"无需重复连接"却重新连接）。
  现已补 `return`。
- **关闭帧的原因里带着字面 `{}`**：`WebsocketManager` 用 `String.format` 处理一个使用 `{}` 占位符的
  消息常量，而该字符串不含 `%s`，`String.format` 会**原样返回并忽略参数**，
  于是客户端收到的关闭原因是 `连接至：{} 的 WebSocket Client 正在关闭，Code {}，Reason：{}。`。
  现已统一改用 `Tool.format`。
  **升级提示**：关闭原因文本发生变化，若客户端按关闭原因做字符串匹配需适配。
- **`client list` 的编号从 0 开始**：同一条命令的两个分支编号规则不一致
  （"未启用"分支从 1 起、"已启用"分支从 0 起）。现统一为**从 1 开始**。
- **平台实现为 null 时启动阶段不报错**：`GlobalContext.init(...)` 现在会校验
  `handleApiImpl` 与 `handleCommandReturnMessageImpl`，为 null 时**立即抛出并指明参数名**。
  此前会拖到"第一条协议请求"或"第一条命令"执行时才抛 NPE，定位成本很高。
- **`WebsocketManager` 的必填依赖改为构造期校验**：`handleCommandReturnMessageService` 为 null 时，
  此前会在 `stop()` 中途抛 NPE——此时客户端已全部停止、而**共享调度器尚未关闭**，
  导致调度器与线程泄漏，且 `QueQiaoRuntime.shutdown()` 后续的 Rcon 关闭与日志都不执行。
  现在接线错误在构造阶段即暴露。
- **`x-self-name` 通过 URL query 传递时被解码两次**：解析函数此前对 query 值解码、对请求头值不解码，
  而调用方又统一解码一次，导致 query 来源被解码两次——
  服务器名含 `%` 时第二次解码会因非法转义抛异常，连接被以"解码失败"拒绝。
  现已改为每个字段在使用处显式决定解码策略，且**恰好解码一次**。
- **握手异常导致鉴权旁路**（严重，安全）：Java-WebSocket 的 `WebSocketImpl.open()` 会
  **吞掉 `onOpen` 抛出的 `RuntimeException` 并让连接保持 `OPEN`**（只上报、不关闭、不重抛）。
  因此一旦 `onOpen` 内部抛异常，未通过鉴权的连接会被保留、后续消息仍被正常处理。
  现已改为：`onOpen` 内部兜底，**任何异常都主动关闭连接**，绝不让异常逃出。
  （该库行为已用一次性实验实证：客户端未被关闭、服务端仍持有该未鉴权连接。）
- **服务端级错误时日志抛 NPE**：库在 selector 等致命错误时会以 `onError(null, e)` 回调，
  此前 `getClientAddress` 直接对 null 取远端地址会抛 `NullPointerException`。
  现已做空值防护，并分别返回 `<server>`（无具体客户端）与 `<unknown>`（无法获取远端地址）占位符。
  同时地址获取改用 `InetSocketAddress#getHostString()` / `getPort()`，
  不再依赖 `InetSocketAddress.toString()` 的格式做字符串清理。
- **`onError` 日志出现无意义的 `null`**：异常 `getMessage()` 为 null 时现回退为异常类名。
- **重复初始化泄漏资源**（严重）：`GlobalContext.init()` 此前无幂等保护，
  重复调用会覆盖运行时引用，旧实例的 WebSocket 连接、共享调度器、Rcon 连接与线程永久泄漏。
- **未初始化时上下文方法直接崩溃**（严重）：运行时空对象此前是"所有字段为 null"的失效对象，
  导致 `GlobalContext.shutdown()` / `sendEvent()` 在未初始化时抛 `NullPointerException`。
  现已改为真正可用的最小运行时。
- **运行时的跨线程可见性缺陷**（严重）：`GlobalContext.runtime` 此前非 `volatile`，
  且运行时字段既非 `final` 也无 `volatile`，由初始化线程写入、由游戏线程与 WebSocket 线程读取，
  可能读到旧引用或半初始化对象（且难以复现）。现已用 `volatile` + `final` 显式声明修复。
- **忽略命令列表可能为 null**：该字段此前无初始值，
  一旦配置加载中途失败便会保持 null，导致 `Tool.isIgnoredCommand` 抛 `NullPointerException`。
  现在 `ConfigKeys.IGNORED_COMMANDS` 的默认值为空列表（可变类型每次取用都是独立副本），
  且注册 / 登录命令作为**强制项**由 `ConfigKeys.effectiveIgnoredCommands(...)` 与用户配置取并集，
  用户无法通过配置放开它们。
- **重连退避溢出**（严重）：原实现 `reconnectInterval * (1L << reconnectTimes)` 在
  `reconnectTimes >= 63` 时移位溢出为负数，负数延迟被调度器当作"立即执行"，
  会形成**无间隔重连风暴**。现改为无位移的封顶翻倍算法，并保证延迟恒在合法区间。
- **连接失败后不自动重连**（严重）：原 `onClose` 中的 `if (remote && !stopped)` 判定
  对"服务端未监听"这类最常见的失败恒为 `false`，自动重连完全依赖 `onError` 兜底。
  现已修正为以 `stopped` 为唯一判据。
- **一次故障被安排两次重连**：`onError` 与 `onClose` 此前各自调度重连，导致重试计数被双倍消耗、
  退避曲线失真。现 `onError` 只记录错误，`onClose` 作为自动重连唯一入口。
- **停止后仍会重连**：`stopWithoutReconnect` 与待执行任务之间存在竞态，现已通过
  代际号失效 + 取消任务 + 停止标志三者共同保证。
- **每个 WsClient 泄漏一条非 daemon 线程**：`Executors.newSingleThreadScheduledExecutor()`
  为每实例创建且从不命名、异常路径不回收，非 daemon 还会阻止 JVM 退出。已改为共享 daemon 调度器。
- **`init()` 重复调用泄漏**：`WebsocketManager.start()` 此前不幂等，重复调用会追加重复 Client；
  现 `start()` / `stop()` 均幂等。
- **单个 endpoint 失败会阻断其余 endpoint**：`startClients` 此前只捕获 `URISyntaxException`，
  其它异常会中断整个循环；现每个 endpoint 独立隔离，失败实例会被回收。
- **业务异常打断连接**：`WsClient.onMessage` 此前无异常防护，
  业务层抛出的 `RuntimeException` 会穿透到 Java-WebSocket 读循环，
  被其捕获后执行 `closeConnection(ABNORMAL_CLOSE)`——**一条异常消息即可打断连接**。
  现已在 callback 内隔离，异常只记日志、不上抛。
- **认证失败日志泄漏 token**：`WsServer` 此前会把客户端提交的 `Authorization` 值原样写入日志。
  现已移除该值，只记录来源地址（`Authorization` 字段名仍保留以便定位问题）。
- **`Tool.debugLog` 空指针**：未初始化全局上下文时 `GlobalContext.getConfig()` 返回 null，
  导致 `debugLog` 抛 `NullPointerException`。已加空值防护。
- **解析失败被误判为服务端错误**：见「破坏性变更」第 1 条。

### 移除

- 移除 `WsServer.broadcast(String)` 的**纯透传覆写**（方法体只有 `super.broadcast(text)`，无任何附加逻辑）。
- 删除两个无有效断言的测试类：`WsClientTest`、`WsServerTest`。
  前者只断言 JDK 的 `URLEncoder` 行为（对 `WsClient` 零覆盖），
  后者同样只断言 `URLEncoder` / `URLDecoder`，且其 `testOnOpen` 仅在固定端口 25565
  启动服务端后断言非空、从不关闭。已由真实 socket 集成测试取代。
- 删除旧配置体系的全部类（见「破坏性变更」第 6 条）：`CommonConfig`、`ConfigFieldRules`、
  旧 `ConfigSynchronizer`、`SyncReport`、旧 `ConfigValidationException`、旧 `ConfigWriter`，
  以及 4 个配置 POJO（`WebSocketServerConfig` / `WebSocketClientConfig` /
  `SubscribeEventConfig` / `RconConfig`）与其对应测试。
- 删除随包资源 `config.example.yml`：配置项的唯一来源改为代码中的 Schema（`ConfigKeys`），
  不再维护一份可能与代码漂移的 YAML 副本。

### 测试

- **测试与工作目录彻底解耦**：
  - 构建层：`tasks.test` 的工作目录重定向到 `build/test-workdir`，测试不再触碰项目根目录下的
    `plugins/queqiao/config.yml`；
  - 用例层：配置相关用例通过 `@TempDir` 逐用例隔离，或直接构造 `new Config(registry)`
    （完全不触碰文件系统）。
- 配置测试覆盖：Schema 声明与路径（`ConfigKeyTest` / `ConfigRegistryTest`）、
  编解码与校验（`ConfigCodecTest` / `ConfigValidatorsTest`）、运行时值存储不变量（`ConfigTest`）、
  四态加载与未知字段（`ConfigLoaderTest`）、写出与闭环（`ConfigWriterTest` / `ConfigWriterRoundTripTest`）、
  原子写与备份轮换（`ConfigStoreTest`）、查漏补缺（`ConfigCheckerTest` / `ConfigSynchronizerTest`）、
  旧配置兼容性回归（`LegacyConfigCompatibilityTest`）、
  全链路与首次生成（`ConfigIntegrationTest` / `GeneratedConfigIntegrationTest`）。
- 新增 `RconClientTest`（A#29 回归）。

- 测试用例数由 68 增至 **267**（44 个测试类），项目整体行覆盖率由 37.7% 提升至 **71.5%**（分支 60.1%）。
- 新增真实 socket 集成测试（随机端口 + `CountDownLatch`，不使用 `Thread.sleep` 做断言）：
  重连回归、停止隔离、多客户端共享调度器、线程数上界与 daemon 校验、握手认证矩阵。
- 新增协议分发矩阵测试与日志脱敏测试。
