package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.CallBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameResultS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameStartS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.HistoryS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.LandlordS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PassBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.ReconnectS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RevealS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RobBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomStateS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TurnS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TrustStateS2C;
import io.wifi.cards.doudizhu.rule.DdzCardType;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端游戏状态（单一数据源）：由 S2C 包驱动，GUI 直接读取。
 * 座位约定：0~2 为绝对座位；对局中座位不变。
 */
public final class DdzClientState {
    public static final DdzClientState INSTANCE = new DdzClientState();

    // ---- 房间/大厅 ----
    public String roomCode;
    public boolean flowerMode;
    public DdzRuleSet ruleSet = DdzRuleSet.STANDARD;
    public final String[] names = new String[3];
    public final String[] playerUuids = new String[3];
    public final boolean[] connected = new boolean[3];
    public int mySeat = -1;
    public DdzGamePhase phase = DdzGamePhase.WAITING;

    // ---- 牌局 ----
    public final List<DdzCard> hand = new ArrayList<>();
    public int[] remaining = new int[]{0, 0, 0};
    public int currentSeat = -1;
    public long turnEndGameTime;
    public int multiplier = 1;
    public int consecutivePasses;
    public int baseScore = 1;
    public int landlordSeat = -1;
    public String landlordName = "";
    public List<DdzCard> bottomCards = new ArrayList<>();
    public String lastPlayName = "";
    public List<DdzCard> lastPlayCards = new ArrayList<>();
    public DdzCardType lastPlayType;
    public int lastPlayKey;
    public int lastPlaySeat = -1;
    public String lastPassName = "";
    public String lastCallName = "";
    public byte lastCallScore = -1;
    public byte callMaxScore;
    public String lastRobName = "";
    public boolean lastRob;
    public boolean myTrust;
    /** 服务端拒绝了最近一次出牌（GameScreen 消费后清空选中）。 */
    public boolean playRejected;
    /** 本局是否已明牌（地主公开手牌）。 */
    public boolean revealed;
    /** 明牌显示的地主手牌（出牌时同步移除）。 */
    public final List<DdzCard> revealedCards = new ArrayList<>();
    /** 一手出牌/跳过记录（主界面渲染最近两手历史用；pass=true 表示不出）。 */
    public record PlayEntry(int seat, String name, List<DdzCard> cards, DdzCardType type, int key, boolean pass) {
    }
    /** 本局最近两手出牌（最新在前，主界面中央渲染）。 */
    public final List<PlayEntry> lastPlays = new ArrayList<>();
    /** 一条出牌历史（历史界面文本行；不出时 typeName="不出"、cardsText 为空）。 */
    public record HistoryLine(String name, String typeName, String cardsText, boolean pass) {
    }
    /** 本局完整出牌历史（历史界面，由 HistoryS2C 下发填充）。 */
    public final List<HistoryLine> historyLines = new ArrayList<>();

    // ---- 结算 ----
    public String resultLandlordName = "";
    public boolean resultLandlordWin;
    public int resultBaseScore = 1;
    public int resultMultiplier = 1;
    public int[] resultDeltas = new int[3];

    private DdzClientState() {
    }

    public boolean inRoom() {
        return roomCode != null;
    }

    public boolean inGame() {
        return roomCode != null && phase != DdzGamePhase.WAITING;
    }

    public boolean isMyTurn() {
        return currentSeat == mySeat;
    }

    public String nameOf(int seat) {
        return seat >= 0 && seat < 3 ? names[seat] : "";
    }

    public int countOf(int seat) {
        return seat >= 0 && seat < 3 ? remaining[seat] : 0;
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

    // ---------------- S2C 处理 ----------------

    public void onRoomState(RoomStateS2C payload) {
        boolean wasInRoom = this.roomCode != null;
        int prevSeat = this.mySeat;
        this.roomCode = payload.roomCode();
        this.flowerMode = payload.flowerMode();
        this.ruleSet = safeRuleSet(payload.ruleSet());
        this.phase = safePhase(payload.phaseOrdinal());
        this.mySeat = payload.mySeat();
        System.arraycopy(payload.names(), 0, names, 0, 3);
        System.arraycopy(payload.uuids(), 0, playerUuids, 0, 3);
        System.arraycopy(payload.connected(), 0, connected, 0, 3);
        Minecraft mc = Minecraft.getInstance();
        if (phase == DdzGamePhase.WAITING) {
            // 刚进入房间（或座位变化）时强制重建大厅，刷新创建/加入/离开等组件
            boolean stateChanged = !wasInRoom || prevSeat != this.mySeat;
            if (!(mc.screen instanceof DdzLobbyScreen) || stateChanged) {
                mc.setScreen(new DdzLobbyScreen());
            }
        } else if (phase == DdzGamePhase.SETTLED) {
            // 本局已结束：打开结算界面（数据由随后的 GameResultS2C 填充），不打开打牌界面
            if (!(mc.screen instanceof DdzResultScreen)) {
                mc.setScreen(new DdzResultScreen());
            }
        } else if (!(mc.screen instanceof DdzGameScreen)) {
            mc.setScreen(new DdzGameScreen());
        }
    }

    public void onGameStart(GameStartS2C payload) {
        this.mySeat = payload.mySeat();
        this.myTrust = false; // 新局重置托管（服务端 start() 同步重置）
        this.hand.clear();
        for (int id : payload.hand()) {
            hand.add(DdzCard.byId(id));
        }
        DdzCard.sortByRank(hand);
        this.phase = DdzGamePhase.CALLING;
        this.currentSeat = payload.starterSeat();
        this.turnEndGameTime = 0; // 等待 TurnS2C 下发截止刻
        this.multiplier = 1;
        this.consecutivePasses = 0;
        this.baseScore = 1;
        this.landlordSeat = -1;
        this.landlordName = "";
        this.bottomCards = new ArrayList<>();
        this.lastPlayName = "";
        this.lastPlayCards = new ArrayList<>();
        this.lastPlayType = null;
        this.lastPlaySeat = -1;
        this.lastPassName = "";
        this.lastCallName = "";
        this.lastCallScore = -1;
        this.callMaxScore = 0;
        this.lastRobName = "";
        this.lastRob = false;
        this.remaining = new int[]{17, 17, 17};
        this.playRejected = false;
        this.revealed = false;
        this.revealedCards.clear();
        this.lastPlays.clear();
        this.historyLines.clear();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof DdzGameScreen)) {
            mc.setScreen(new DdzGameScreen());
        }
    }

    /** 断线重连：用服务端快照恢复当前对局完整状态（房间信息已由 RoomStateS2C 先行同步）。 */
    public void onReconnect(ReconnectS2C payload) {
        this.phase = safePhase(payload.phaseOrdinal());
        this.hand.clear();
        for (int id : payload.hand()) {
            hand.add(DdzCard.byId(id));
        }
        DdzCard.sortByRank(hand);
        this.callMaxScore = payload.callMaxScore();
        this.currentSeat = payload.currentSeat();
        this.turnEndGameTime = payload.endGameTime();
        this.multiplier = payload.multiplier();
        this.consecutivePasses = payload.consecutivePasses();
        this.baseScore = payload.baseScore();
        this.landlordSeat = payload.landlordSeat();
        this.landlordName = payload.landlordName();
        this.bottomCards = DdzCard.byIds(payload.bottomCards());
        this.lastPlaySeat = payload.lastPlaySeat();
        this.lastPlayName = payload.lastPlayName();
        this.lastPlayCards = DdzCard.byIds(payload.lastPlayCards());
        this.lastPlayType = safeType(payload.lastPlayType());
        this.lastPlayKey = payload.lastPlayKey();
        // 历史表态记录以快照为准，不沿用断线前的显示（避免跨局残留）
        this.lastPassName = "";
        this.lastCallName = "";
        this.lastCallScore = -1;
        this.lastRobName = "";
        this.lastRob = false;
        this.playRejected = false;
        this.revealed = false;
        this.revealedCards.clear();
        this.remaining = toIntArray(payload.remainingCounts());
        // 历史出牌：以快照的最近一手重建（更早的历史由历史界面请求完整记录）
        this.lastPlays.clear();
        if (payload.lastPlaySeat() >= 0) {
            this.lastPlays.add(new PlayEntry(payload.lastPlaySeat(), payload.lastPlayName(),
                    DdzCard.byIds(payload.lastPlayCards()), safeType(payload.lastPlayType()), payload.lastPlayKey(), false));
        }
        this.historyLines.clear();
        Minecraft mc = Minecraft.getInstance();
        if (phase == DdzGamePhase.WAITING) {
            if (!(mc.screen instanceof DdzLobbyScreen)) {
                mc.setScreen(new DdzLobbyScreen());
            }
        } else if (phase == DdzGamePhase.SETTLED) {
            // 结算中：打开结算界面（数据由服务端随后重发的 GameResultS2C 填充）
            if (!(mc.screen instanceof DdzResultScreen)) {
                mc.setScreen(new DdzResultScreen());
            }
        } else {
            // 强制重建 GameScreen：倒计时、按钮、选中状态全部重置
            mc.setScreen(new DdzGameScreen());
        }
    }

    public void onCall(CallBroadcastS2C payload) {
        this.lastCallName = payload.playerName();
        this.lastCallScore = payload.score();
        this.callMaxScore = payload.maxScore();
        // 语音：叫 1/2/3 分
        DdzSoundPlayer.playCall(payload.score());
        if (payload.score() == 3) {
            // 叫 3 分触发抢地主阶段
            this.phase = DdzGamePhase.ROBBING;
            this.multiplier = 1;
            this.consecutivePasses = 0;
        }
    }

    public void onRob(RobBroadcastS2C payload) {
        this.phase = DdzGamePhase.ROBBING;
        this.lastRobName = payload.playerName();
        this.lastRob = payload.rob();
        this.multiplier = payload.multiplier();
        this.consecutivePasses = payload.consecutivePasses();
        // 语音：抢地主——仅抢地主阶段中的真实抢（倍数 ≥×2）；
        // 叫 3 分触发的初始广播（×1）不播（已播"叫三分"语音）
        if (payload.rob() && payload.multiplier() > 1) {
            DdzSoundPlayer.playRob();
        }
    }

    public void onLandlord(LandlordS2C payload) {
        this.phase = DdzGamePhase.PLAYING;
        this.landlordSeat = payload.landlordSeat();
        this.landlordName = payload.landlordName();
        this.bottomCards = DdzCard.byIds(payload.bottomCards());
        this.baseScore = payload.baseScore();
        this.multiplier = payload.multiplier();
        this.lastPlayName = "";
        this.lastPlayCards = new ArrayList<>();
        this.lastPlayType = null;
        this.lastPlaySeat = -1;
        this.lastPassName = "";
        this.revealed = false;
        this.revealedCards.clear();
        this.lastPlays.clear();
        this.historyLines.clear();
        if (mySeat == landlordSeat) {
            hand.addAll(bottomCards);
            DdzCard.sortByRank(hand);
        }
        this.remaining = new int[]{17, 17, 17};
        this.remaining[landlordSeat] += bottomCards.size();
    }

    public void onPlay(PlayBroadcastS2C payload) {
        this.lastPlayName = payload.playerName();
        this.lastPlayCards = DdzCard.byIds(payload.cardIds());
        this.lastPlayType = safeType(payload.typeOrdinal());
        this.lastPlayKey = payload.keyValue();
        this.lastPlaySeat = payload.seat();
        this.lastPassName = "";
        this.multiplier = payload.multiplier();
        this.remaining = toIntArray(payload.remainingCounts());
        Set<Integer> played = new HashSet<>();
        for (int id : payload.cardIds()) {
            played.add(id);
        }
        if (payload.seat() == mySeat) {
            hand.removeIf(c -> played.contains(c.id()));
        }
        // 明牌中：地主出牌时同步从明牌列表移除
        if (revealed && payload.seat() == landlordSeat) {
            revealedCards.removeIf(c -> played.contains(c.id()));
        }
        // 历史出牌（主界面渲染最近两手，最新在前）
        lastPlays.add(0, new PlayEntry(payload.seat(), payload.playerName(),
                DdzCard.byIds(payload.cardIds()), safeType(payload.typeOrdinal()), payload.keyValue(), false));
        while (lastPlays.size() > 2) {
            lastPlays.remove(lastPlays.size() - 1);
        }
        // 语音：按牌型/点数播报
        DdzSoundPlayer.playPlay(safeType(payload.typeOrdinal()), lastPlayCards);
    }

    /** 明牌广播：地主公开全部手牌。 */
    public void onReveal(RevealS2C payload) {
        this.revealed = true;
        this.revealedCards.clear();
        for (int id : payload.handIds()) {
            revealedCards.add(DdzCard.byId(id));
        }
        DdzCard.sortByRank(revealedCards);
    }

    /** 出牌历史下发（历史界面）：填充完整本局记录（最新在前）。 */
    public void onHistory(HistoryS2C payload) {
        this.historyLines.clear();
        String[] names = payload.names();
        String[] types = payload.types();
        String[] cards = payload.cards();
        for (int i = 0; i < names.length; i++) {
            historyLines.add(new HistoryLine(names[i], types[i], cards[i], "不出".equals(types[i])));
        }
    }

    public void onPass(PassBroadcastS2C payload) {
        this.lastPassName = payload.playerName();
        this.remaining = toIntArray(payload.remainingCounts());
        // 不出（跳过）也计入历史（主界面渲染最近两手；当前座位即 pass 玩家）
        lastPlays.add(0, new PlayEntry(this.currentSeat, payload.playerName(),
                new ArrayList<>(), null, 0, true));
        while (lastPlays.size() > 2) {
            lastPlays.remove(lastPlays.size() - 1);
        }
        // 语音：不出
        DdzSoundPlayer.playPass();
    }

    public void onTurn(TurnS2C payload) {
        this.currentSeat = payload.seat();
        this.turnEndGameTime = payload.endGameTime();
    }

    /** 托管状态回传：按钮（托管/取消托管）与服务端保持一致（含 debug trust 命令、断线托管、重连）。 */
    public void onTrustState(TrustStateS2C payload) {
        this.myTrust = payload.enabled();
    }

    public void onResult(GameResultS2C payload) {
        this.phase = DdzGamePhase.SETTLED;
        this.landlordSeat = payload.landlordSeat();
        this.landlordName = payload.landlordName();
        this.resultLandlordName = payload.landlordName();
        this.resultLandlordWin = payload.landlordWin();
        this.resultBaseScore = payload.baseScore();
        this.resultMultiplier = payload.multiplier();
        this.resultDeltas = payload.scoreDeltas();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof DdzResultScreen)) {
            mc.setScreen(new DdzResultScreen());
        }
    }

    public void onRoomClosed(String reason) {
        reset();
        if (reason != null && !reason.isEmpty()) {
            chat(reason);
        }
        // 无条件重建大厅：离开/解散后必须回到"未在房间"的创建/加入布局
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new DdzLobbyScreen());
    }

    public void onNotice(String message) {
        if (message.contains("牌型不合法") || message.contains("无法压过") || message.contains("出牌与手牌不符")) {
            playRejected = true;
        }
        chat(message);
    }

    /** 显示一条消息到聊天栏。 */
    public static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("[斗地主] " + message));
        }
    }

    /**
     * 关闭界面提示：输入命令或点击可点击文本重新打开。
     * 例：已关闭大厅，输入 /doudizhu 或点击 [/doudizhu] 重新打开
     */
    public static void chatReopenHint(String closedDesc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.gui.getChat().addMessage(Component.literal("[斗地主] 已" + closedDesc + "，输入 /doudizhu 或 ")
                .append(Component.literal("[点击此处]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/doudizhu"))))
                .append(Component.literal(" 重新打开")));
    }

    /** 清空全部本地状态（离开服务器/世界时调用，避免房间缓存残留影响下次进入）。 */
    public void clearAll() {
        reset();
    }

    private void reset() {
        roomCode = null;
        flowerMode = false;
        ruleSet = DdzRuleSet.STANDARD;
        Arrays.fill(names, "");
        Arrays.fill(playerUuids, "");
        Arrays.fill(connected, false);
        mySeat = -1;
        phase = DdzGamePhase.WAITING;
        hand.clear();
        remaining = new int[]{0, 0, 0};
        currentSeat = -1;
        turnEndGameTime = 0;
        multiplier = 1;
        consecutivePasses = 0;
        baseScore = 1;
        landlordSeat = -1;
        landlordName = "";
        bottomCards = new ArrayList<>();
        lastPlayName = "";
        lastPlayCards = new ArrayList<>();
        lastPlayType = null;
        lastPlayKey = 0;
        lastPlaySeat = -1;
        lastPassName = "";
        lastCallName = "";
        lastCallScore = -1;
        callMaxScore = 0;
        lastRobName = "";
        lastRob = false;
        myTrust = false;
        playRejected = false;
        revealed = false;
        revealedCards.clear();
        lastPlays.clear();
        historyLines.clear();
        resultLandlordName = "";
        resultLandlordWin = false;
        resultBaseScore = 1;
        resultMultiplier = 1;
        resultDeltas = new int[3];
    }

    private static int[] toIntArray(byte[] bytes) {
        int[] result = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            result[i] = bytes[i];
        }
        return result;
    }

    // ---- 防御性解析：S2C 序号越界时回退默认值（服务端可信，但防版本不匹配） ----

    private static DdzGamePhase safePhase(byte ordinal) {
        DdzGamePhase[] values = DdzGamePhase.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DdzGamePhase.WAITING;
    }

    private static DdzRuleSet safeRuleSet(byte ordinal) {
        DdzRuleSet[] values = DdzRuleSet.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : DdzRuleSet.STANDARD;
    }

    private static DdzCardType safeType(byte ordinal) {
        DdzCardType[] values = DdzCardType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }
}
