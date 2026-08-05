package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.CallBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameResultS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameStartS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.LandlordS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PassBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.ReconnectS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RobBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomStateS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TurnS2C;
import io.wifi.cards.doudizhu.rule.DdzCardType;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.minecraft.client.Minecraft;
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
        } else if (!(mc.screen instanceof DdzGameScreen)) {
            mc.setScreen(new DdzGameScreen());
        }
    }

    public void onGameStart(GameStartS2C payload) {
        this.mySeat = payload.mySeat();
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
        this.remaining = toIntArray(payload.remainingCounts());
        Minecraft mc = Minecraft.getInstance();
        if (phase == DdzGamePhase.WAITING) {
            if (!(mc.screen instanceof DdzLobbyScreen)) {
                mc.setScreen(new DdzLobbyScreen());
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
        if (payload.seat() == mySeat) {
            Set<Integer> played = new HashSet<>();
            for (int id : payload.cardIds()) {
                played.add(id);
            }
            hand.removeIf(c -> played.contains(c.id()));
        }
    }

    public void onPass(PassBroadcastS2C payload) {
        this.lastPassName = payload.playerName();
        this.remaining = toIntArray(payload.remainingCounts());
    }

    public void onTurn(TurnS2C payload) {
        this.currentSeat = payload.seat();
        this.turnEndGameTime = payload.endGameTime();
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
