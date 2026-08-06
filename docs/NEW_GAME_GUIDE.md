# 新小游戏拓展指南（Wifi 卡牌棋类游戏）

本文档说明如何在 `card-game`（Fabric 1.21.1 模组）中添加一个新小游戏。
以斗地主（`io.wifi.cards.doudizhu`）为完整参考模板，新游戏照抄其结构，
并继承公共基类即可——**菜单、统一命令 `/cardgames`、跨游戏防护、房间列表、大厅/对局 UI 全部自动生效**。

## 一、架构总览

```
io.wifi.cards
├── common/                    # 公共层（新游戏零改动，只使用）
│   ├── Room                   # 房间基类：id / announce(公开) / settledAtMillis / spectators / isConnected
│   ├── GameRegistry           # 注册表：菜单条目 + /cardgames 路由 + 跨游戏防护 + 房间码前缀
│   ├── GameInfo               # 注册条目 record（15 个字段，见下方模板）
│   ├── CommonMod / CommonPackets / CardGamesCommands   # 菜单协议 + /cardgames 命令
│   └── client/
│       ├── AbstractLobbyScreen    # 大厅基类：标题条/主菜单/公开房间列表/滚动条拖拽/房间码复制/onClose
│       ├── AbstractGameScreen     # 对局界面基类：T 键聊天/倒计时/tick 模板/退出确认/头像渲染
│       ├── AbstractSubScreen      # 规则/历史子界面基类：父级背景/返回/滚动全套
│       ├── CardGameChatScreen     # 对局内聊天框（Esc/Enter 返回对局界面）
│       ├── GameMenuScreen/GameMenuClient/GameClientSession  # 菜单 + 会话恢复
│       ├── GuiUtil / LobbyPrefs   # 滚动条工具 / 开房选项记忆（config 持久化）
│       └── ...
├── doudizhu/                  # 游戏模板（参考实现）
├── uno/
└── board/
```

新游戏 = 一个 `io.wifi.cards.<gameId>` 包 + 公共层接线（约 10 步）。

## 二、十步接入模板

### 第 1 步：建包结构

```
src/main/java/io/wifi/cards/<gameId>/
├── <X>Mod.java            # 服务端入口（注册包/命令/tick/断线/GameInfo）
├── <X>Client.java         # 客户端入口（S2C 接收器/断线清理/会话注册）
├── command/<X>Commands.java
├── game/<X>Game.java      # 服务端权威对局状态机
├── gui/<X>ClientState.java + <X>LobbyScreen + <X>GameScreen + 子界面
├── manager/<X>MemoryManager.java + <X>Room.java
├── model/                 # 阶段/玩家等枚举与模型
├── network/<X>Packets.java
└── (可选) sound/<X>Sounds.java, rule/ 等
```

### 第 2 步：房间类继承 `Room`

```java
public class MyRoom extends Room {
    public final ServerPlayer[] members = new ServerPlayer[3]; // 座位布局按游戏定
    public final String[] botNames = new String[3];
    public int size = 0;
    public MyGame game; // 对局状态机

    public MyRoom(String id, boolean announce) {
        super(id, announce); // id 由 manager 生成（前缀-5位码），announce=是否公开
    }
    // 座位管理（addPlayer/addBot/removePlayer/quitToBot/seatOf/...）按斗地主模板
}
```
`Room` 已提供：`id`、`announce`、`settledAtMillis`、`spectators`、`addSpectator/removeSpectator`、`isConnected`（判活）。

### 第 3 步：Manager（房间生命周期 + 防护）

`<X>MemoryManager`（单例 `INSTANCE`）必须包含（方法名与斗地主一致，逐项照抄模板并替换类型）：

| 方法 | 必须做的事 |
|---|---|
| `createRoom(server, player, announce, botCount)` | `leaveSpectateInternal` → `currentRoom` 检查 → **`GameRegistry.busyInOtherGame(player, MY_GAME_ID)` 防护** → 上限 → `new MyRoom(generateCode(), announce)` → 广播（点击命令用 `/cardgames accept <code>`） |
| `joinRoom(player, code)` | `leaveSpectateInternal` → 长度检查 → `rooms.get(cleanCode(code))` → currentRoom 检查 → **跨游戏防护** → 满/阶段检查 → 加入 |
| `spectate(player, code)` / `forceJoin(target, code)` | **跨游戏防护**（返回错误串） |
| `sendRoomList(player)` | **仅 `r.announce` 的房间** + 500ms/玩家限频（`lobbyQueryTimes` map）→ `RoomListS2C(codes, lines, statuses)`（0=等待 1=对局中 2=已结束） |
| `onPlayerDisconnect(player)` | 开头 `lobbyQueryTimes.remove(uuid)`；旁观清理；等待/结算=离开；对局=托管 |
| `onPlayerJoin(player)` | 重连恢复（`replacePlayerByUuid` + 快照） |
| `roomCount()/playerCount()` | 菜单统计 |
| `generateCode()` | 返回 `GameRegistry.PREFIX_<X> + "-" + 5位码`（字符集见斗地主） |
| `cleanCode(code)` | 剥前缀（兼容完整码与裸码） |
| `roomSnapshot()/roomByCode()/deleteRoom()/clearAllRooms()` | 管理命令用 |

### 第 4 步：Packets

`<X>Packets` 模板（照抄 `DdzPackets`）：
- 全部 C2S/S2C 为 `CustomPacketPayload` record + `StreamCodec`
- `register()`：注册全部 codec + `registerServerReceivers()`
- C2S 接收器统一 `ctx.server().execute(() -> guarded(...))`（主线程调度 + 异常兜底）
- **必须包含** `LobbyQueryC2S`（空包）与 `RoomListS2C(String[] codes, String[] lines, byte[] statuses)`（大厅列表协议，codec 见 `DdzPackets.RoomListS2C`）

### 第 5 步：Commands

`<X>Commands` 命令树（`/mygame`，可含 accept/invite/leave/spectate/unspectate + debug）：
- **必须公开静态**：`public static void openLobby(ServerPlayer)`（对局中重发快照/旁观重发/否则 `OpenLobbyS2C`）——GameInfo 的 opener 引用它
- **必须公开静态**：`public static String invite(ServerPlayer owner, ServerPlayer target)`（邀请消息点击命令用 `/cardgames accept <code>`）
- **必须公开静态**：`public static String phaseName(MyPhase)`（管理命令/房间摘要用）

### 第 6 步：GUI（全部继承公共基类）

| 界面 | 继承 | 实现 |
|---|---|---|
| `<X>LobbyScreen` | `AbstractLobbyScreen` | 钩子：`gameId/inRoomState/lobbyTitle/contentTop/sendRoomQuery/lobbyRoomList/joinRoom/spectateRoom/lobbyChat/currentRoomCode/roomInfoCodeRect/reopenHint/roomActionBottomY` + `buildContent()`（创建区按钮，选项变化调 `LobbyPrefs.set`）；基类自带 T 键聊天与等待房间的"关闭界面"按钮 |
| `<X>GameScreen` | `AbstractGameScreen` | 钩子：`isSpectator/turnEndGameTime/onTick/rebuildActionButtons/onEscPressed/handleCloseRequest/reopenHint/exitConfirmFirstLine`；`init()` 用 `addRulesButton/addHistoryButton`；按钮用 `button()` 工厂；头像用 `drawHead()`；退出弹层用 `drawExitConfirm()` |
| 规则/历史界面 | `AbstractSubScreen` | `buildContent()`（行文本）、`renderContent()`（标题条+内容区+滚动文本，滚动条用 `drawScrollbar`、提示用 `drawScrollHint`）、`contentHeight()`、`fallbackScreen()` |
| `<X>ClientState` | `implements GameClientSession` | `gameId/hasSession/restoreScreen`（会话恢复） |
| 聊天框 | 直接用 `CardGameChatScreen` | `new CardGameChatScreen(this)` |

`ClientState` 另需：`roomList`（`List<AbstractLobbyScreen.RoomEntry>`）+ `onRoomList(payload)`（通知大厅 `onRoomListChanged`）。

### 第 7 步：`<X>Mod.init()`

照抄 `DdzMod.init()`：
1. `<X>Packets.register()`；可选 `<X>Sounds.init()`
2. `<X>Commands.registerServer()`
3. `ServerTickEvents.END_SERVER_TICK.register(Manager.INSTANCE::tick)`
4. **`GameRegistry.register(new GameInfo(...))`**（模板）：

```java
GameRegistry.register(new GameInfo(
        GameRegistry.GAME_MY, GameRegistry.PREFIX_MY,   // 常量加在 GameRegistry
        "游戏名", "首字", 0xFFRRGGBB,                    // 菜单显示名/图标首字/配色
        "一句话简介",
        MyCommands::openLobby,                          // opener
        (p, c) -> MyMemoryManager.INSTANCE.joinRoom(p, c),   // joiner
        MyMemoryManager.INSTANCE::spectate,             // spectater
        MyMemoryManager.INSTANCE::leaveRoom,            // leaver
        MyCommands::invite,                             // inviter
        p -> MyMemoryManager.INSTANCE.currentRoom(p) != null
                || MyMemoryManager.INSTANCE.spectatingRoomId(p) != null,  // busy
        MyMemoryManager.INSTANCE::roomCount,            // 菜单统计
        MyMemoryManager.INSTANCE::playerCount,
        () -> MyMemoryManager.INSTANCE.roomSnapshot().stream()
                .map(r -> r.id + " · 人数 " + r.size + "/3 · " + MyCommands.phaseName(r.phase()))
                .toList(),                              // 管理房间摘要
        MyMemoryManager.INSTANCE::deleteRoom,           // roomDeleter
        MyMemoryManager.INSTANCE::clearAllRooms));      // roomClearer
```
5. DISCONNECT（`server.execute` 主线程调度 + try/catch）与 JOIN 事件

### 第 8 步：`<X>Client.init()`

照抄 `DdzClient.init()`：
1. 全部 S2C 接收器（`ctx.client().execute(() -> state.onXxx(payload))`）
2. `OpenLobbyS2C` 接收器：主线程 `setScreen(new XxxLobbyScreen())`（先清调试幽灵状态）
3. 断线/进服清理（`ClientPlayConnectionEvents` → `state.clearAll()`）
4. `GameMenuClient.registerSession(XxxClientState.INSTANCE)`

### 第 9 步：入口接线（唯一需要改的两个文件）

```java
// CardGameMod.onInitialize() 末尾（CommonMod.init() 之前）
MyMod.init();
// CardGameModClient.onInitializeClient() 末尾（GameMenuClient.init() 之前）
MyClient.init();
```

### 第 10 步：资源与验证

- `lang/zh_cn.json`、`sounds.json`（如需音效，参考 `tools/` 生成脚本）
- `gradlew build`（编译 + 全部单元测试）
- 游戏内自测清单：
  - [ ] `/cardgames` 菜单出现新游戏（图标首字/配色/统计）
  - [ ] 大厅开房（选项记忆）、公开房间出现在列表（加入/旁观/复制房间码/滚动条拖拽）
  - [ ] 跨游戏防护：对局中进其他游戏被拒；ESC 返回原对局
  - [ ] T 键聊天、Esc 退出确认、规则/历史子界面
  - [ ] `/cardgames open <id>`、`/cardgames join|spectate <前缀-码>`、OP `debug rooms/roomdelete/roomclear`

## 三、必须遵守的规则

1. **服务端/客户端分离**：服务端可达的类（Mod/Manager/Room/Commands/Packets/Game）不得引用 `net.minecraft.client.*` 或 `common.client.*`；客户端专属逻辑只放 `XxxClient`/`gui/`/`common.client/`。构建后字节码验证。
2. **跨游戏防护**：`createRoom/joinRoom/spectate/forceJoin` 四处必须调 `GameRegistry.busyInOtherGame`。
3. **房间码**：统一"前缀-5位码"（前缀注册在 `GameRegistry`），`cleanCode` 兼容裸码输入。
4. **公开过滤**：大厅房间列表只下发 `announce=true` 的房间。
5. **限频**：`sendRoomList`/`LobbyQueryC2S` 必须 500ms/玩家限频 + 断线清理。
6. **线程**：C2S 接收器与断线回调必须调度服务器主线程；客户端接收器调度客户端主线程。
7. **编码**：源码 UTF-8，中文注释/文案直接内联。
8. **防御**：所有网络输入（长度/越界/空值）校验；房间/对局 tick 异常只记日志不崩服。

## 四、Manager 层说明

三个现有游戏的 `XxxMemoryManager`（约 700 行）结构相同但尚未抽象（游戏逻辑差异大）。
新游戏直接照抄模板 + 替换类型即可；未来如需抽象可单独立项（建议以 `busyInOtherGame`/`sendRoomList`/`generateCode` 等公共方法为切入点）。
