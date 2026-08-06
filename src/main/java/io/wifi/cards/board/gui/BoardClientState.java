package io.wifi.cards.board.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.GameClientSession;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.DebugUiS2C;
import io.wifi.cards.board.network.BoardPackets.GameResultS2C;
import io.wifi.cards.board.network.BoardPackets.GameStartS2C;
import io.wifi.cards.board.network.BoardPackets.MoveBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.PassBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.ReconnectS2C;
import io.wifi.cards.board.network.BoardPackets.RoomListS2C;
import io.wifi.cards.board.network.BoardPackets.RoomStateS2C;
import io.wifi.cards.board.network.BoardPackets.SurrenderS2C;
import io.wifi.cards.board.network.BoardPackets.TurnS2C;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 客户端游戏状态（单一数据源）：由 S2C 包驱动，GUI 直接读取。
 * <p>座位约定：0 = 黑方，1 = 白方；旁观者 mySeat=-1。
 * 棋盘统一 byte[]（行优先，0=空 1=黑 2=白），由各 S2C 包整体覆盖。</p>
 */
public final class BoardClientState implements GameClientSession {
    public static final BoardClientState INSTANCE = new BoardClientState();

    // ---- 房间/大厅 ----
    public String roomCode;
    public BoardGameType gameType = BoardGameType.OTHELLO;
    public int size = 8;
    public BoardPhase phase = BoardPhase.WAITING;
    public final String[] names = new String[2];
    public final String[] playerUuids = new String[2];
    public final boolean[] connected = new boolean[2];
    public int mySeat = -1;

    // ---- 对局 ----
    public byte[] board = new byte[0];
    public int currentSeat = -1;
    public long turnEndGameTime;
    /** 最近动作描述（顶部信息条显示，服务端快照/本地拼接）。 */
    public String lastAction = "";
    /** 最后一手落点（渲染高亮标记用），-1=尚无。 */
    public int lastMoveX = -1;
    public int lastMoveY = -1;

    // ---- 结算 ----
    public int winSeat = -1;
    public String winName = "";
    public int blackScore;
    public int whiteScore;
    public byte resultReason;

    // ---- 大厅房间列表 ----
    /** 一条大厅房间条目。status：0=等待中可加入 1=对局中可旁观 2=已结束。 */
    public record RoomEntry(String code, String line, byte status) {
    }
    public final List<RoomEntry> roomList = new ArrayList<>();

    /** 调试旁观模式（/board debug ui）：无真实房间的随机虚拟对局，仅供 UI 检查；
     *  任何真实服务端状态包到达时清除（见各 onXxx 入口）。 */
    public boolean debugMode;

    private BoardClientState() {
    }

    public boolean inRoom() {
        return roomCode != null;
    }

    public boolean inGame() {
        return roomCode != null && phase != BoardPhase.WAITING;
    }

    public boolean isMyTurn() {
        return currentSeat == mySeat;
    }

    public String nameOf(int seat) {
        return seat >= 0 && seat < 2 ? names[seat] : "";
    }

    /** 座位 0/1 是否是黑/白方显示名（旁观者视角固定座位 0=黑 1=白）。 */
    public String sideName(int seat) {
        return seat == 0 ? "黑" : "白";
    }

    /** 当前房间成员数（按座位名统计）。 */
    public int roomSize() {
        int n = 0;
        for (String name : names) {
            if (name != null && !name.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    // ---------------- 小游戏菜单会话（跨游戏恢复界面） ----------------

    @Override
    public String gameId() {
        return GameRegistry.GAME_BOARD;
    }

    @Override
    public boolean hasSession() {
        // 调试旁观（debugMode）也是占用中的"会话"：菜单关闭时回到棋盘界面
        return inRoom() || debugMode;
    }

    /** 按当前会话状态重开对应界面（菜单/其它大厅关闭后回到棋盘/大厅）。 */
    @Override
    public void restoreScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (phase == BoardPhase.WAITING) {
            mc.setScreen(new BoardLobbyScreen());
        } else {
            mc.setScreen(new BoardGameScreen());
        }
    }

    // ---------------- S2C 处理 ----------------

    public void onRoomState(RoomStateS2C payload) {
        this.debugMode = false; // 真实房间状态到达：退出调试旁观模式
        boolean wasInRoom = this.roomCode != null;
        int prevSeat = this.mySeat;
        this.roomCode = payload.roomCode();
        this.gameType = safeType(payload.gameType());
        this.size = payload.size() > 0 ? payload.size() : gameType.defaultSize;
        this.phase = safePhase(payload.phaseOrdinal());
        this.mySeat = payload.mySeat();
        // 防御性拷贝：源数组长度不足时其余座位填空（防版本不匹配崩溃）
        copyInto(payload.names(), names);
        copyInto(payload.uuids(), playerUuids);
        copyBooleans(payload.connected(), connected);
        Minecraft mc = Minecraft.getInstance();
        if (phase == BoardPhase.WAITING) {
            // 刚进入房间（或座位变化）时强制重建大厅，刷新创建/加入/离开等组件
            boolean stateChanged = !wasInRoom || prevSeat != this.mySeat;
            if (!(mc.screen instanceof BoardLobbyScreen) || stateChanged) {
                mc.setScreen(new BoardLobbyScreen());
            }
        } else if (!(mc.screen instanceof BoardGameScreen)) {
            // 对局中/已结束均停留在棋盘界面（结算以横幅展示）
            mc.setScreen(new BoardGameScreen());
        }
    }

    public void onGameStart(GameStartS2C payload) {
        this.debugMode = false;
        this.board = payload.board();
        this.size = boardSize(); // 棋盘边长从长度反推（防御：异常长度回退游戏默认）
        this.phase = BoardPhase.PLAYING;
        this.currentSeat = payload.firstSeat();
        this.turnEndGameTime = 0; // 等待 TurnS2C 下发截止刻
        this.lastAction = "游戏开始，黑方先手";
        this.lastMoveX = -1;
        this.lastMoveY = -1;
        this.winSeat = -1;
        this.winName = "";
        this.blackScore = 0;
        this.whiteScore = 0;
        this.resultReason = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BoardGameScreen)) {
            mc.setScreen(new BoardGameScreen());
        }
    }

    /** 断线重连：用服务端快照恢复当前对局完整状态（房间信息已由 RoomStateS2C 先行同步）。 */
    public void onReconnect(ReconnectS2C payload) {
        this.debugMode = false;
        this.board = payload.board();
        this.size = boardSize();
        this.phase = safePhase(payload.phaseOrdinal());
        this.currentSeat = payload.currentSeat();
        this.turnEndGameTime = payload.endGameTime();
        this.lastAction = payload.lastAction();
        this.lastMoveX = -1;
        this.lastMoveY = -1;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BoardGameScreen)) {
            mc.setScreen(new BoardGameScreen());
        }
    }

    public void onMove(MoveBroadcastS2C payload) {
        this.board = payload.board();
        this.lastMoveX = payload.x();
        this.lastMoveY = payload.y();
        this.lastAction = nameOf(payload.seat()) + " 落子 (" + payload.x() + "," + payload.y() + ")";
    }

    public void onPass(PassBroadcastS2C payload) {
        this.lastAction = payload.name() + " 停一手";
    }

    public void onSurrender(SurrenderS2C payload) {
        this.lastAction = payload.winnerName() + " 获胜（对方认输）";
    }

    public void onTurn(TurnS2C payload) {
        this.currentSeat = payload.seat();
        this.turnEndGameTime = payload.endGameTime();
        // 轮到本人：播放原版提示音效提醒操作（旁观者 mySeat=-1 不会匹配）
        if (payload.seat() == this.mySeat && this.mySeat >= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.0F);
            }
        }
    }

    public void onResult(GameResultS2C payload) {
        this.debugMode = false;
        this.phase = BoardPhase.SETTLED;
        this.winSeat = payload.winSeat();
        this.winName = payload.winName();
        this.blackScore = payload.blackScore();
        this.whiteScore = payload.whiteScore();
        this.resultReason = payload.reason();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BoardGameScreen)) {
            mc.setScreen(new BoardGameScreen());
        }
    }

    public void onRoomClosed(String reason) {
        reset();
        if (reason != null && !reason.isEmpty()) {
            chat(reason);
        }
        // 无条件重建大厅：离开/解散后必须回到"未在房间"的创建/加入布局
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new BoardLobbyScreen());
    }

    public void onNotice(String message) {
        chat(message);
        // 状态自愈：服务端查无本玩家的房间/旁观记录（如断线重进后本地残留旁观 UI，
        // 而服务端已清理旁观关系或房间已销毁）→ 强制回大厅，避免卡死在棋盘界面
        if (inRoom() && (message.contains("你不在任何房间里") || message.contains("你不在旁观任何房间"))) {
            reset();
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new BoardLobbyScreen());
        }
    }

    /** 大厅房间列表下发：更新缓存并通知大厅界面刷新（内容变化时才重建控件）。 */
    public void onRoomList(RoomListS2C payload) {
        this.debugMode = false; // 打开大厅即退出调试旁观模式（防残留标记）
        roomList.clear();
        String[] codes = payload.codes();
        String[] lines = payload.lines();
        byte[] statuses = payload.statuses();
        int n = Math.min(codes.length, Math.min(lines.length, statuses.length));
        for (int i = 0; i < n; i++) {
            roomList.add(new RoomEntry(codes[i], lines[i], statuses[i]));
        }
        if (Minecraft.getInstance().screen instanceof BoardLobbyScreen lobby) {
            lobby.onRoomListChanged();
        }
    }

    /** 调试旁观界面（/board debug ui）：随机虚拟对局填充本地状态并打开棋盘界面（旁观视角）。 */
    public void onDebugUi(DebugUiS2C payload) {
        this.debugMode = true;
        this.roomCode = null; // 无真实房间
        this.gameType = safeType(payload.gameType());
        this.size = payload.size() > 0 ? payload.size() : gameType.defaultSize;
        this.board = payload.board();
        this.phase = BoardPhase.PLAYING;
        this.mySeat = -1; // 旁观视角
        this.currentSeat = payload.currentSeat();
        this.turnEndGameTime = 0;
        this.lastAction = "调试数据（无真实对局）";
        this.lastMoveX = -1;
        this.lastMoveY = -1;
        copyInto(payload.names(), names);
        Arrays.fill(playerUuids, "");
        Arrays.fill(connected, false);
        this.winSeat = -1;
        this.winName = "";
        this.blackScore = 0;
        this.whiteScore = 0;
        this.resultReason = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BoardGameScreen)) {
            mc.setScreen(new BoardGameScreen());
        }
    }

    /** 显示一条消息到聊天栏。 */
    public static void chat(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("[棋牌] " + message));
        }
    }

    /**
     * 关闭界面提示：输入命令或点击可点击文本重新打开。
     */
    public static void chatReopenHint(String closedDesc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.gui.getChat().addMessage(Component.literal("[棋牌] 已" + closedDesc + "，输入 /board 或 ")
                .append(Component.literal("[点击此处]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/board"))))
                .append(Component.literal(" 重新打开")));
    }

    /** 清空全部本地状态（离开服务器/世界时调用，避免房间缓存残留影响下次进入）。 */
    public void clearAll() {
        reset();
    }

    private void reset() {
        roomCode = null;
        gameType = BoardGameType.OTHELLO;
        size = 8;
        phase = BoardPhase.WAITING;
        Arrays.fill(names, "");
        Arrays.fill(playerUuids, "");
        Arrays.fill(connected, false);
        mySeat = -1;
        board = new byte[0];
        currentSeat = -1;
        turnEndGameTime = 0;
        lastAction = "";
        lastMoveX = -1;
        lastMoveY = -1;
        winSeat = -1;
        winName = "";
        blackScore = 0;
        whiteScore = 0;
        resultReason = 0;
        roomList.clear();
        debugMode = false;
    }

    // ---- 防御性解析：S2C 序号越界时回退默认值（服务端可信，但防版本不匹配） ----

    /** 从棋盘长度反推边长；异常长度（非平方数/为零）回退游戏默认尺寸，防渲染除零。 */
    private int boardSize() {
        int s = (int) Math.round(Math.sqrt(board.length));
        return s * s == board.length && s > 0 ? s : gameType.defaultSize;
    }

    /** 字符串数组防御拷贝：源不足时目标其余位置填空。 */
    private static void copyInto(String[] src, String[] dst) {
        Arrays.fill(dst, "");
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
    }

    /** boolean 数组防御拷贝。 */
    private static void copyBooleans(boolean[] src, boolean[] dst) {
        Arrays.fill(dst, false);
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
    }

    private static BoardGameType safeType(byte ordinal) {
        BoardGameType[] values = BoardGameType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BoardGameType.OTHELLO;
    }

    private static BoardPhase safePhase(byte ordinal) {
        BoardPhase[] values = BoardPhase.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BoardPhase.WAITING;
    }
}
