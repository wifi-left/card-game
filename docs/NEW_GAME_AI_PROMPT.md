# 给 AI 的新小游戏开发提示词

把下面整段（含【任务】与【背景】）直接发给 AI 即可。
请先阅读模板再动手；输出必须是可编译代码 + 接线 + 自测。

---

## 任务

给 Minecraft 1.21.1 Fabric 模组 `wifi-card-games`（项目根目录：`F:\codes\java\Fabric\StarRailExpress\card-game`，包根 `io.wifi.cards`）新增一个小游戏【游戏名：____】，接入现有公共架构。

## 背景

模组已有 3 个游戏：斗地主（`doudizhu`，模板参考）、UNO（`uno`）、棋类（`board`，chess）。新游戏必须继承公共层，而不是复制粘贴游戏逻辑。公共层（`io.wifi.cards.common`）已提供：

- `Room`（抽象房间基类）：`id`/`announce`/`settledAtMillis`/`spectators`/`addSpectator`/`removeSpectator`/`isConnected(ServerPlayer)`，新房间 `extends Room` 并实现座位/对局管理
- `GameRegistry`：游戏注册表（`GAME_*`/`PREFIX_*` 常量 + `register(GameInfo)`），驱动小游戏菜单、`/cardgames` 路由、跨游戏防护 `busyInOtherGame`、房间码前缀
- `GameInfo`（record）：注册条目，字段含义见 `doudizhu/DdzMod.init()` 的注册代码
- `AbstractLobbyScreen`（大厅基类）：标题条/主菜单按钮/公开房间列表（自动轮询+复制房间码+滚动条拖拽）/onClose 模板，子类只实现钩子
- `AbstractGameScreen`（对局界面基类）：T 键聊天、倒计时、tick 模板（`onTick`）、退出确认弹层、`drawHead` 头像、按钮工厂 `button()`、`addRulesButton`/`addHistoryButton`；子类实现 `rebuildActionButtons`/`onEscPressed`/`handleCloseRequest`/`reopenHint`/`exitConfirmFirstLine`/`isSpectator`/`turnEndGameTime`
- `AbstractSubScreen`（规则/历史子界面基类）：父级背景渲染、返回按钮、滚动全套（滚轮+拖拽）、`contentHeight`/`buildContent`/`renderContent`/`fallbackScreen` 钩子
- `CardGameChatScreen`：对局内聊天框，直接 `new CardGameChatScreen(gameScreen)`
- `GameClientSession`（客户端会话接口：`gameId`/`hasSession`/`restoreScreen`）+ `GameMenuClient.registerSession(state)`：跨游戏屏幕恢复
- `GuiUtil`（滚动条绘制）、`LobbyPrefs`（开房选项 config 持久化，`set/get` 见 `doudizhu/gui/DdzLobbyScreen`）
- `common/network/CommonPackets`（OpenMenuS2C/MenuQueryC2S/OpenGameC2S，客户端菜单轮询）、`common/command/CardGamesCommands`（/cardgames 树，用 GameInfo 路由）

## 必读模板文件（照抄结构，替换类型/名字）

1. `src/main/java/io/wifi/cards/doudizhu/DdzMod.java` —— 入口（Packets.register / Commands.registerServer / tick / DISCONNECT(server.execute+try/catch) / JOIN / GameRegistry.register(GameInfo)）
2. `src/main/java/io/wifi/cards/doudizhu/DdzClient.java` —— S2C 接收（`ctx.client().execute`）/ OpenLobbyS2C→setScreen / 断线清理 / registerSession
3. `src/main/java/io/wifi/cards/doudizhu/manager/DdzRoom.java` + `DdzMemoryManager.java` —— 房间与生命周期（含 4 处跨游戏防护、announce 过滤、500ms 限频、generateCode/cleanCode）
4. `src/main/java/io/wifi/cards/doudizhu/network/DdzPackets.java` —— 包协议（含必须的 `LobbyQueryC2S` 空包 + `RoomListS2C(String[] codes,String[] lines,byte[] statuses)`）
5. `src/main/java/io/wifi/cards/doudizhu/command/DdzCommands.java` —— 命令树（必须公开 `openLobby(ServerPlayer)`/`invite(ServerPlayer,ServerPlayer)`/`phaseName(...)`）
6. `src/main/java/io/wifi/cards/doudizhu/gui/DdzClientState.java`（implements GameClientSession）、`DdzLobbyScreen.java`、`DdzGameScreen.java`、`DdzRulesScreen.java`、`DdzHistoryScreen.java` —— 界面（全部继承公共基类）

## 接线要求

- `CardGameMod.onInitialize()`：在 `CommonMod.init()` 之前加一行 `<X>Mod.init();`
- `CardGameModClient.onInitializeClient()`：在 `GameMenuClient.init()` 之前加一行 `<X>Client.init();`
- 语言文件 `src/main/resources/assets/wifi_card_games/lang/zh_cn.json` 补条目；如需音效再改 `sounds.json`

## 强制规则（违反即不合格）

1. **服务端/客户端分离**：服务端可达类（Mod/Manager/Room/Commands/Packets/Game）严禁引用 `net.minecraft.client.*` 与 `io.wifi.cards.common.client.*`；客户端代码只在 `<X>Client` 与 `gui/`、`common.client`。
2. **跨游戏防护**：`createRoom`/`joinRoom`/`spectate`/`forceJoin` 必须调 `GameRegistry.busyInOtherGame(player, gameId)`，被占用时返回中文错误提示。
3. **房间码**：`GameRegistry.PREFIX_<X> + "-" + 5 位码`；`cleanCode` 兼容用户输入裸码。
4. **公开过滤**：房间列表只下发 `announce == true` 的房间；状态字节 0=等待 1=对局中 2=已结束。
5. **限频**：大厅列表查询 500ms/玩家（Map 记录时间，断线时清理）。
6. **线程安全**：C2S 接收器与断线/重连回调一律 `server.execute(() -> guarded(...))`；客户端 S2C 一律 `client.execute(...)`。
7. **防御性**：所有网络输入校验长度/范围/空值；房间与对局 tick 全部 try/catch，只记日志。
8. **编码**：UTF-8；界面文案用中文；消息统一 `Component.literal`。
9. **不破坏现有游戏**：不要改动 `doudizhu`/`uno`/`board` 的行为逻辑；只允许新增包 + 入口接线 + 资源。

## 输出要求

1. 完整可编译的 Java 源码（新包 `io.wifi.cards.<gameId>` 全部文件）+ 资源文件修改
2. `./gradlew compileJava` 与 `./gradlew build`（含测试）通过，汇报测试数量与失败数
3. 游戏内自测清单（/cardgames 菜单、开房/公开列表/旁观/复制房间码、跨游戏防护、T 聊天、Esc 退出确认、规则/历史子界面、断线重连、OP 管理命令）
4. 如果新增了核心算法（如出牌/落子校验），附带单元测试（JUnit 5，`src/test/java/io/wifi/cards/<gameId>/`，参考 `src/test/java/io/wifi/cards/doudizhu/`）
5. 不写文档，不重构其他文件；如发现公共层缺能力，指出缺口并给出最小改动建议，不要擅自大改公共层
