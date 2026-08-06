package io.wifi.cards.uno.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.CardGameChatScreen;
import io.wifi.cards.common.client.GameClientSession;
import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoColor;
import io.wifi.cards.uno.model.UnoGamePhase;
import io.wifi.cards.uno.network.UnoPackets.DrawBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawPenaltyS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawResultS2C;
import io.wifi.cards.uno.network.UnoPackets.DebugSpectatorS2C;
import io.wifi.cards.uno.network.UnoPackets.GameResultS2C;
import io.wifi.cards.uno.network.UnoPackets.GameStartS2C;
import io.wifi.cards.uno.network.UnoPackets.HistoryS2C;
import io.wifi.cards.uno.network.UnoPackets.PassBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.PlayBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.ReconnectS2C;
import io.wifi.cards.uno.network.UnoPackets.RoomStateS2C;
import io.wifi.cards.uno.network.UnoPackets.SpectatorHandsS2C;
import io.wifi.cards.uno.network.UnoPackets.TrustStateS2C;
import io.wifi.cards.uno.network.UnoPackets.TurnS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoCatchS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoDeclaredS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端游戏状态（单一数据源）：由 S2C 包驱动，GUI 直接读取。
 * 座位约定：0~size-1 为绝对座位；对局中座位不变（等待中离开会压缩座位）。
 * 座位 0 即房主（最早加入者）。
 */
public final class UnoClientState implements GameClientSession {
    public static final UnoClientState INSTANCE = new UnoClientState();

    // ---- 房间/大厅 ----
    public String roomCode;
    public final List<String> names = new ArrayList<>();
    public final List<String> playerUuids = new ArrayList<>();
    public final List<Boolean> connected = new ArrayList<>();
    public int mySeat = -1;
    public UnoGamePhase phase = UnoGamePhase.WAITING;
    /** 旁观 UI 调试模式（/uno debug spectateui 虚拟数据，无真实房间；标题显示"（调试）"）。 */
    public boolean debugView;

    // ---- 牌局 ----
    public final List<UnoCard> hand = new ArrayList<>();
    public int[] remaining = new int[0];
    public int currentSeat = -1;
    public int direction = 1;
    public long turnEndGameTime;
    /** 弃牌堆顶牌（null = 尚未开局）。 */
    public UnoCard topCard;
    /** 当前有效颜色（万能牌为出牌者所选颜色）。 */
    public UnoColor topColor = UnoColor.NONE;
    /** 本回合是否已抽牌且抽到的牌可打（可打出或跳过）。 */
    public boolean drawnPlayable;
    /** 各座位 UNO 抓捕窗口（剩 1 张未喊 UNO，可被抓）。
     *  客户端窗口完全由服务端事件镜像驱动：开窗=onPlay 镜像（remaining==1），
     *  关窗=手牌变化镜像（onDraw/onDrawResult/onDrawPenalty）与被抓/自动抓（onUnoCatch）；
     *  服务端宽限（2 人局功能牌回轮保持窗口）无对应事件，客户端自然保持，
     *  因此 onTurn 不做窗口关闭（否则宽限期间"抓 UNO"按钮会被错误隐藏）。 */
    public boolean[] unoCatchable = new boolean[0];
    /** 各座位本局是否已喊 UNO。 */
    public boolean[] declaredUno = new boolean[0];
    /** 本人是否处于托管（机器人自动出牌）。 */
    public boolean myTrust;
    /** 各家完整手牌（仅旁观模式填充：SpectatorHandsS2C 透视视角）。 */
    public final List<List<UnoCard>> spectatorHands = new ArrayList<>();

    // ---- 大厅房间列表 ----
    /** 中央事件提示行（最新一条）。 */
    public String lastEvent = "";
    /** 服务端拒绝了最近一次出牌（GameScreen 消费后清空选中）。 */
    public boolean playRejected;
    /** 一条事件历史（历史界面文本行：玩家名 + 事件描述）。 */
    public record HistoryLine(String name, String text) {
    }
    /** 本局完整事件历史（历史界面，由 HistoryS2C 下发填充，最新在前）。 */
    public final List<HistoryLine> historyLines = new ArrayList<>();

    // ---- 结算 ----
    public int winnerSeat = -1;
    public String winnerName = "";

    private UnoClientState() {
    }

    public boolean inRoom() {
        return roomCode != null;
    }

    public boolean inGame() {
        return roomCode != null && phase != UnoGamePhase.WAITING;
    }

    public boolean isSpectator() {
        return mySeat < 0;
    }

    public boolean isHost() {
        return inRoom() && !isSpectator() && mySeat == 0;
    }

    public boolean isMyTurn() {
        return currentSeat == mySeat;
    }

    public String nameOf(int seat) {
        return seat >= 0 && seat < names.size() ? names.get(seat) : "";
    }

    public int countOf(int seat) {
        return seat >= 0 && seat < remaining.length ? remaining[seat] : 0;
    }

    public int roomSize() {
        int count = 0;
        for (String n : names) {
            if (n != null && !n.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ---------------- 小游戏菜单会话（跨游戏恢复界面） ----------------

    @Override
    public String gameId() {
        return GameRegistry.GAME_UNO;
    }

    @Override
    public boolean hasSession() {
        return inRoom();
    }

    /** 按当前会话状态重开对应界面（菜单/其它大厅关闭后回到牌桌/结算/大厅）。 */
    @Override
    public void restoreScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (phase == UnoGamePhase.WAITING) {
            mc.setScreen(new UnoLobbyScreen());
        } else if (phase == UnoGamePhase.SETTLED) {
            mc.setScreen(new UnoResultScreen());
        } else {
            mc.setScreen(new UnoGameScreen());
        }
    }

    // ---------------- S2C 处理 ----------------

    public void onRoomState(RoomStateS2C payload) {
        boolean wasInRoom = this.roomCode != null;
        int prevSeat = this.mySeat;
        int prevSize = names.size();
        this.debugView = false; // 真实房间状态到达：退出调试模式
        this.roomCode = payload.roomCode();
        this.phase = safePhase(payload.phaseOrdinal());
        this.mySeat = payload.mySeat();
        names.clear();
        playerUuids.clear();
        connected.clear();
        for (String n : payload.names()) {
            names.add(n);
        }
        for (String u : payload.uuids()) {
            playerUuids.add(u);
        }
        for (boolean c : payload.connected()) {
            connected.add(c);
        }
        Minecraft mc = Minecraft.getInstance();
        if (phase == UnoGamePhase.WAITING) {
            // 刚进入房间（或座位/人数变化）时重建大厅：切换创建区/等待房间视图
            boolean stateChanged = !wasInRoom || prevSeat != this.mySeat || prevSize != names.size();
            if (!(mc.screen instanceof UnoLobbyScreen) || stateChanged) {
                mc.setScreen(new UnoLobbyScreen());
            }
        } else if (phase == UnoGamePhase.SETTLED) {
            // 本局已结束：打开结算界面（数据由随后的 GameResultS2C 填充），不打开打牌界面
            if (!(mc.screen instanceof UnoResultScreen)) {
                mc.setScreen(new UnoResultScreen());
            }
        } else if (!(mc.screen instanceof UnoGameScreen)
                && !(mc.screen instanceof UnoRulesScreen)
                && !(mc.screen instanceof UnoHistoryScreen)
                && !(mc.screen instanceof CardGameChatScreen)) {
            // 打牌中：切到牌桌界面。规则/历史子界面（渲染牌桌为背景，状态实时同步）
            // 与聊天框不强制弹回，避免其他玩家断线/退出触发 RoomState 时被打断
            mc.setScreen(new UnoGameScreen());
        }
    }

    public void onGameStart(GameStartS2C payload) {
        this.debugView = false; // 真实开局到达：退出调试模式
        this.mySeat = payload.mySeat();
        this.myTrust = false; // 新局重置托管（服务端 start() 同步重置）
        this.phase = UnoGamePhase.PLAYING;
        this.hand.clear();
        for (int id : payload.hand()) {
            hand.add(UnoCard.byId(id));
        }
        UnoCard.sortByValue(hand);
        this.currentSeat = payload.starterSeat();
        this.direction = 1;
        this.turnEndGameTime = 0; // 等待 TurnS2C 下发截止刻
        this.topCard = payload.topCardId() >= 0 ? UnoCard.byId(payload.topCardId()) : null;
        this.topColor = safeColor(payload.topColorOrdinal());
        this.remaining = toIntArray(payload.remainingCounts());
        this.drawnPlayable = false;
        this.unoCatchable = new boolean[payload.remainingCounts().length];
        this.declaredUno = new boolean[payload.remainingCounts().length];
        this.spectatorHands.clear(); // 新局/入房：清空旁观透视快照（防残留旧局数据）
        this.historyLines.clear(); // 新局：历史随服务端重建（打开历史界面时重新请求）
        this.lastEvent = "游戏开始！";
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof UnoGameScreen)) {
            mc.setScreen(new UnoGameScreen());
        }
    }

    /** 断线重连：用服务端快照恢复当前对局完整状态（房间信息已由 RoomStateS2C 先行同步）。 */
    public void onReconnect(ReconnectS2C payload) {
        this.debugView = false; // 真实快照到达：退出调试模式
        this.phase = safePhase(payload.phaseOrdinal());
        this.hand.clear();
        for (int id : payload.hand()) {
            hand.add(UnoCard.byId(id));
        }
        UnoCard.sortByValue(hand);
        this.currentSeat = payload.currentSeat();
        this.turnEndGameTime = payload.endGameTime();
        this.direction = payload.direction();
        this.topCard = payload.topCardId() >= 0 ? UnoCard.byId(payload.topCardId()) : null;
        this.topColor = safeColor(payload.topColorOrdinal());
        this.remaining = toIntArray(payload.remainingCounts());
        this.drawnPlayable = payload.drawnPlayable();
        this.unoCatchable = copyBooleans(payload.unoCatchable());
        this.declaredUno = copyBooleans(payload.declaredUno());
        this.myTrust = false; // 服务端随后补发 TrustStateS2C 修正
        this.winnerSeat = payload.winnerSeat();
        this.winnerName = payload.winnerName();
        this.lastEvent = "";
        this.historyLines.clear(); // 历史以快照为准不沿用旧局残留（打开历史界面时重新请求）
        Minecraft mc = Minecraft.getInstance();
        if (phase == UnoGamePhase.SETTLED) {
            // 结算中：打开结算界面（数据由服务端随后重发的 GameResultS2C 填充）
            if (!(mc.screen instanceof UnoResultScreen)) {
                mc.setScreen(new UnoResultScreen());
            }
        } else {
            // 强制重建 GameScreen：倒计时、按钮、选中状态全部重置
            mc.setScreen(new UnoGameScreen());
        }
    }

    public void onPlay(PlayBroadcastS2C payload) {
        this.topCard = UnoCard.byId(payload.cardId());
        this.topColor = safeColor(payload.colorOrdinal());
        this.remaining = toIntArray(payload.remainingCounts());
        // 镜像服务端抓捕窗口：出牌后手牌剩 1 张 → 该玩家进入 UNO 抓捕窗口
        // （其本人可点"喊 UNO"，其他玩家可点"抓 UNO"；关闭由手牌变化/被抓事件镜像）
        int seat = payload.seat();
        if (seat >= 0 && seat < remaining.length && seat < unoCatchable.length
                && remaining[seat] == 1) {
            unoCatchable[seat] = true;
            // 镜像服务端：新的"剩 1 张"周期重置已喊标记（此前喊过但被罚牌离开 1 张
            // 状态者须重新喊；不重置会导致客户端显示"UNO"而服务端实际可抓）
            declaredUno[seat] = false;
        }
        if (payload.seat() == mySeat) {
            hand.removeIf(c -> c.id() == payload.cardId());
            this.drawnPlayable = false;
        }
        String cardName = topCard.display();
        this.lastEvent = payload.playerName() + " 打出 " + cardName
                + (topCard.isWild() ? "（选" + topColor.displayName() + "）" : "");
        // 音效：打出（本人按键声，他人轻微提示音）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
        }
    }

    /** 抽牌结果（仅本人收到）：抽到的牌入列；playable 决定可否打出/跳过。 */
    public void onDrawResult(DrawResultS2C payload) {
        for (int id : payload.cardIds()) {
            hand.add(UnoCard.byId(id));
        }
        UnoCard.sortByValue(hand);
        this.drawnPlayable = payload.playable();
        // 镜像服务端：手牌离开"剩 1 张"状态时关闭抓捕窗口（否则"喊 UNO"按钮残留）
        if (mySeat >= 0 && mySeat < unoCatchable.length && hand.size() != 1) {
            unoCatchable[mySeat] = false;
        }
        if (payload.cardIds().length == 1) {
            String cardName = UnoCard.byId(payload.cardIds()[0]).display();
            this.lastEvent = payload.playable()
                    ? "抽到了 " + cardName + "，可以打出或跳过"
                    : "抽到了 " + cardName + "，不能打出";
        }
    }

    /** 抽牌广播（他人抽牌，牌面保密）。 */
    public void onDraw(DrawBroadcastS2C payload) {
        this.remaining = toIntArray(payload.remainingCounts());
        // 镜像服务端：他人手牌离开"剩 1 张"状态时关闭其抓捕窗口
        if (payload.seat() >= 0 && payload.seat() < unoCatchable.length
                && remaining[payload.seat()] != 1) {
            unoCatchable[payload.seat()] = false;
        }
        this.lastEvent = nameOf(payload.seat()) + " 抽了一张牌";
    }

    /** 罚牌广播（+2/+4）：目标被罚抽并跳过。 */
    public void onDrawPenalty(DrawPenaltyS2C payload) {
        this.remaining = toIntArray(payload.remainingCounts());
        // 镜像服务端：罚牌后目标手牌离开"剩 1 张"状态时关闭其抓捕窗口
        if (payload.seat() >= 0 && payload.seat() < unoCatchable.length
                && remaining[payload.seat()] != 1) {
            unoCatchable[payload.seat()] = false;
        }
        this.lastEvent = nameOf(payload.seat()) + " 被罚抽 " + payload.count() + " 张并跳过";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 1.0F);
        }
    }

    public void onPass(PassBroadcastS2C payload) {
        this.remaining = toIntArray(payload.remainingCounts());
        if (payload.seat() == mySeat) {
            this.drawnPlayable = false;
        }
        this.lastEvent = nameOf(payload.seat()) + " 跳过";
    }

    public void onTurn(TurnS2C payload) {
        this.currentSeat = payload.seat();
        this.turnEndGameTime = payload.endGameTime();
        if (payload.seat() != mySeat) {
            // 新回合开始：轮到自己时由 UI 保留（抽到可打的牌后的按钮状态已重置）
            this.drawnPlayable = false;
        }
        // 注意：不在 onTurn 关闭抓捕窗口——服务端宽限（2 人局功能牌回轮窗口保持）
        // 与自动罚（UnoCatchS2C 同步关闭）均由事件驱动，客户端窗口保持纯镜像一致。
        // 轮到本人且未托管：播放原版提示音效提醒操作（旁观者 mySeat=-1 不会匹配）
        if (payload.seat() == this.mySeat && this.mySeat >= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.0F);
            }
        }
    }

    /** 喊 UNO 广播。 */
    public void onUnoDeclared(UnoDeclaredS2C payload) {
        if (payload.seat() >= 0 && payload.seat() < declaredUno.length) {
            declaredUno[payload.seat()] = true;
        }
        this.lastEvent = nameOf(payload.seat()) + " 喊了 UNO！";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F, 1.0F);
        }
        // 语音：喊 UNO（edge-tts 生成，tools/input_uno.txt）
        UnoSoundPlayer.playUno();
    }

    /** 抓 UNO 广播：目标未喊 UNO 被抓住，罚 2 张（牌面已私发）。
     *  catcherSeat=-1 表示服务端自动抓取（窗口期内无人抓、轮到自己时系统代抓）。 */
    public void onUnoCatch(UnoCatchS2C payload) {
        if (payload.targetSeat() >= 0 && payload.targetSeat() < unoCatchable.length) {
            unoCatchable[payload.targetSeat()] = false;
        }
        this.remaining = toIntArray(payload.remainingCounts());
        this.lastEvent = payload.catcherSeat() >= 0
                ? nameOf(payload.catcherSeat()) + " 抓住了 " + nameOf(payload.targetSeat()) + " 没喊 UNO，罚 2 张！"
                : nameOf(payload.targetSeat()) + " 没喊 UNO，被自动罚 2 张！";
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 1.0F, 0.5F);
        }
        // 语音：罚两张（edge-tts 生成，tools/input_uno.txt）
        UnoSoundPlayer.playCatch();
    }

    /** 托管状态回传：按钮（托管/取消托管）与服务端保持一致（含 debug trust 命令、断线托管、重连）。 */
    public void onTrustState(TrustStateS2C payload) {
        this.myTrust = payload.enabled();
    }

    public void onResult(GameResultS2C payload) {
        this.phase = UnoGamePhase.SETTLED;
        this.winnerSeat = payload.winnerSeat();
        this.winnerName = payload.winnerName();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof UnoResultScreen)) {
            mc.setScreen(new UnoResultScreen());
        }
    }

    public void onRoomClosed(String reason) {
        reset();
        if (reason != null && !reason.isEmpty()) {
            chat(reason);
        }
        // 无条件重建大厅：离开/解散后必须回到"未在房间"的创建/加入布局
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new UnoLobbyScreen());
    }

    public void onNotice(String message) {
        if (message.contains("这张牌不能打") || message.contains("你手里没有这张牌")) {
            playRejected = true;
        }
        chat(message);
        // 状态自愈：服务端查无本玩家的房间/旁观记录（如断线重进后本地残留旁观 UI，
        // 而服务端已清理旁观关系或房间已销毁）→ 强制回大厅，避免卡死在旁观界面
        if (inRoom() && (message.contains("你不在任何房间里") || message.contains("你不在旁观任何房间"))) {
            reset();
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new UnoLobbyScreen());
        }
    }

    /** 各家完整手牌下发（仅旁观者收到）：透视视角，随时覆盖为最新快照。 */
    public void onSpectatorHands(SpectatorHandsS2C payload) {
        this.spectatorHands.clear();
        for (int[] ids : payload.hands()) {
            List<UnoCard> h = UnoCard.byIds(ids);
            UnoCard.sortByValue(h);
            spectatorHands.add(h);
        }
    }


    /**
     * 旁观 UI 调试快照（/uno debug spectateui）：无房间的虚拟旁观数据，
     * 填充状态后打开"（调试）"旁观界面，仅用于检查旁观 UI 渲染/滚动。
     */
    public void onDebugSpectator(DebugSpectatorS2C payload) {
        reset();
        this.debugView = true;
        this.roomCode = "调试";
        this.mySeat = -1;
        this.phase = UnoGamePhase.PLAYING;
        names.clear();
        playerUuids.clear();
        connected.clear();
        for (String n : payload.names()) {
            names.add(n);
            playerUuids.add(""); // 虚拟玩家无头像
            connected.add(true);
        }
        this.spectatorHands.clear();
        for (int[] ids : payload.hands()) {
            List<UnoCard> h = UnoCard.byIds(ids);
            UnoCard.sortByValue(h);
            spectatorHands.add(h);
        }
        this.remaining = new int[payload.hands().length];
        for (int i = 0; i < remaining.length; i++) {
            remaining[i] = payload.hands()[i].length;
        }
        this.currentSeat = payload.currentSeat();
        this.direction = payload.direction();
        this.topCard = payload.topCardId() >= 0 ? UnoCard.byId(payload.topCardId()) : null;
        this.topColor = safeColor(payload.topColorOrdinal());
        this.turnEndGameTime = 0; // 调试数据无倒计时
        this.unoCatchable = copyBooleans(payload.unoCatchable());
        this.declaredUno = copyBooleans(payload.declaredUno());
        this.lastEvent = "（调试）虚拟旁观数据，非真实对局";
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new UnoGameScreen());
    }

    /** 事件历史下发（历史界面）：填充完整本局记录（最新在前）。 */
    public void onHistory(HistoryS2C payload) {
        this.historyLines.clear();
        String[] names = payload.names();
        String[] texts = payload.texts();
        // 防御：两数组长度不一致时按较短者遍历（服务端恒等长，防版本不匹配越界）
        int count = Math.min(names.length, texts.length);
        for (int i = 0; i < count; i++) {
            historyLines.add(new HistoryLine(names[i], texts[i]));
        }
    }

    /** 显示一条消息到聊天栏。 */
    public static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("[UNO] " + message));
        }
    }

    /**
     * 关闭界面提示：输入命令或点击可点击文本重新打开。
     * 例：已关闭大厅，输入 /uno 或点击 [/uno] 重新打开
     */
    public static void chatReopenHint(String closedDesc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.gui.getChat().addMessage(Component.literal("[UNO] 已" + closedDesc + "，输入 /uno 或 ")
                .append(Component.literal("[点击此处]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/uno"))))
                .append(Component.literal(" 重新打开")));
    }

    /** 清空全部本地状态（离开服务器/世界时调用，避免房间缓存残留影响下次进入）。 */
    public void clearAll() {
        reset();
    }

    private void reset() {
        roomCode = null;
        debugView = false;
        names.clear();
        playerUuids.clear();
        connected.clear();
        mySeat = -1;
        phase = UnoGamePhase.WAITING;
        hand.clear();
        remaining = new int[0];
        currentSeat = -1;
        direction = 1;
        turnEndGameTime = 0;
        topCard = null;
        topColor = UnoColor.NONE;
        drawnPlayable = false;
        unoCatchable = new boolean[0];
        declaredUno = new boolean[0];
        myTrust = false;
        spectatorHands.clear();
        lastEvent = "";
        playRejected = false;
        historyLines.clear();
        winnerSeat = -1;
        winnerName = "";
    }

    private static int[] toIntArray(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[i];
        }
        return result;
    }

    private static boolean[] copyBooleans(boolean[] src) {
        boolean[] dst = new boolean[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    // ---- 防御性解析：S2C 序号越界时回退默认值（服务端可信，但防版本不匹配） ----

    private static UnoGamePhase safePhase(byte ordinal) {
        UnoGamePhase[] values = UnoGamePhase.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UnoGamePhase.WAITING;
    }

    private static UnoColor safeColor(byte ordinal) {
        UnoColor[] values = UnoColor.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UnoColor.NONE;
    }
}
