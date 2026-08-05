## 斗地主 Fabric 模组（1.21.1 / Mojang 映射）— 第一轮实施计划

**范围**（按你的选择）：规则引擎 + 状态机 + 网络 + 文字化 GUI + 房间/邀请 + 托管/超时；**语音(.ogg占位音)、纹理美化、匹配队列明确留到第二轮**。纯内存无持久化。

### 1. 入口架构（伞状设计，为后续牌类预留）
- `io.wifi.cards.CardGameMod` — 主入口（@Mod，id=`wifi-card-games`）：`onInitialize()` 中调用 `io.wifi.cards.doudizhu.DoudizhuMod.init()` 载入斗地主初始化
- `io.wifi.cards.CardGameModClient` — 客户端入口：调用 `io.wifi.cards.doudizhu.DoudizhuClient.init()`
- fabric.mod.json 的 main/client entrypoint 分别指向这两个伞类；后续其他牌类游戏以同样方式挂载
- zh_cn 语言文件（assets/wifi-card-games/lang/）

### 2. 数据模型（io.wifi.cards.doudizhu.card / model）
- CardRank（3~2=3~15，小王16，大王17）、CardSuit（♠♥♣♦）、Card（固定id 0~53，花牌=54）、Deck（54/55张洗牌）
- GameMode（CLASSIC/FLOWER）、GamePhase（WAITING→DEALING→CALLING→ROBBING→PLAYING→SETTLED）、Player

### 3. 牌型引擎（rule/，核心）
- CardType：三王炸/火箭/炸弹/软炸弹/单/对/三张/三带一/三带二/单顺/连对/飞机/飞机带翅/四带二/PASS
- 经典识别：频次统计+连续性校验（顺子≥5、连对≥3、飞机≥2，不含2与王）
- 花牌：枚举替换值(3~17)记录所有合法解读；F+大小王=三王炸、F+三张=软炸弹优先；压制用"存在任一解读能压过"语义，多解读按 三王炸>软炸弹/炸弹>最大key 确定性选优
- 边界：F单出=单牌(大王值)；F+王=对王（提纲允许）；四带二不互压；顺子等长；等值不能压
- 炸弹/火箭/三王炸/软炸弹当场倍数×2 并广播

### 4. 游戏状态机（game/DoudizhuGame，服务端权威）
- 发牌17张+底牌3/4张，随机起始叫分
- 叫分：不叫/1/2/3 且必须更高；叫3→抢地主；一轮结束取最高分者（底分1/2）；全不叫→重发
- 抢地主：抢→换候选人+倍数×2+计数清零；不抢→+1；**连续2人不抢终止**；底分固定3
- 出牌：地主先出，两 Pass 后自由出牌；出完即胜
- 结算：地主胜+2×底分×倍数，农民各−底分×倍数（败则相反），仅展示不存储
- 托管/超时：15秒 tick 驱动；超时/断线自动（叫分→不叫、抢→不抢、出牌→找第一个能压的否则Pass）；WAITING 掉线则解散房间

### 5. 网络（新版 CustomPayload API）
- C2S：创建/加入/离开房间、叫分、抢/不抢、出牌、不出、托管、再来一局
- S2C：房间状态、发牌、叫分/抢地主广播、地主确定（亮底牌+倍数+底分）、出牌/Pass广播（含剩余张数）、轮到谁+倒计时、倍数更新、结算、房间关闭/错误

### 6. GUI（文字化牌面）
- LobbyScreen：创建（经典/花牌）、加入（房间码输入）、离开
- GameScreen：对手信息+出牌区（倍数/轮到谁/倒计时/连续不抢X/2）+手牌（色块文字，花牌金色⭐，点击选牌）+动态按钮（叫分/抢/出牌+提示+托管）；「提示」客户端复用引擎算第一个可压组合
- ResultScreen：胜负+分数明细+再来一局/返回大厅

### 7. 房间管理（manager/）
- GameMemoryManager 单例：rooms/sessions 全内存；房间码加入；满3人自动开
- 命令：/doudizhu（打开大厅）、/doudizhu invite <玩家>（点击消息接受）、/doudizhu leave

### 8. 文件清单
```
io.wifi.cards/
├── CardGameMod.java / CardGameModClient.java
└── doudizhu/
    ├── DoudizhuMod.java / DoudizhuClient.java   ← 被伞类入口载入
    ├── card/    Card, CardRank, CardSuit, Deck
    ├── rule/    CardType, PlayResult, CardTypeRecognizer
    ├── model/   GamePhase, GameMode, Player
    ├── game/    DoudizhuGame（状态机+托管+超时+结算）
    ├── manager/ GameMemoryManager, Room, PlayerSession
    ├── network/ ModPackets（含payload记录类）
    ├── gui/     LobbyScreen, GameScreen, ResultScreen
    └── command/ DoudizhuCommands
```

### 9. 实施顺序与验证
骨架（伞入口+子模块init）→ 牌型引擎+单元测试（先绿）→ 状态机 → 网络接线 → 客户端三屏 → 命令/断线/房间 → `gradlew build` 全量编译通过；沙箱无法联网时交付代码+本地构建说明

### 10. 第二轮（本次不做）
语音.ogg占位音+SoundEvent、牌面纹理PNG、匹配队列、大厅在线列表