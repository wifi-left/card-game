package io.wifi.cards.board.game;

import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.GameResultS2C;
import io.wifi.cards.board.network.BoardPackets.NoticeS2C;
import io.wifi.cards.board.network.BoardPackets.ReconnectS2C;
import io.wifi.cards.board.network.BoardPackets.SurrenderS2C;
import io.wifi.cards.board.network.BoardPackets.TurnS2C;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 棋类对局状态机骨架（服务端权威，纯内存；黑白棋/五子棋/围棋共用）。
 * <p>统一棋盘编码：byte[size*size] 行优先（idx = y*size+x），0=空 1=黑(座位 0) 2=白(座位 1)。</p>
 * <p>托管：玩家退出/断线 → {@link #onPlayerQuit(int)} 标记托管，轮到该座位时由
 * {@link #autoAct(int)} 自动行动（五子棋/黑白棋 AI 落子，围棋自动停一手）；
 * 真人超时（{@value #TURN_SECONDS} 秒）同样走自动行动。重连时 {@link #onPlayerReconnect(int)} 恢复手动。</p>
 * <p>结算：{@link #settle(byte, int, int, String)} 广播 GameResultS2C 并标记房间结算时刻
 * （超时后由 BoardMemoryManager 自动销毁空闲房间）。</p>
 */
public abstract class BoardGame {
    /** 每回合行动时限（秒）。 */
    public static final int TURN_SECONDS = 60;

    /** 托管/机器人在当前回合的自动行动延迟（tick，1 秒，让玩家看清上一手）。 */
    private static final int AUTO_ACT_DELAY_TICKS = 20;

    protected final BoardRoom room;
    /** 棋盘边长（黑白棋 8 / 五子棋 15 / 围棋 9 或 19）。 */
    protected final int size;
    /** 棋盘：0=空 1=黑 2=白。 */
    protected final byte[] board;
    protected BoardPhase phase = BoardPhase.PLAYING;
    protected int currentSeat;
    /** 行动截止游戏刻（两端共用 level.getGameTime() 基准，不受帧率/延迟影响）。 */
    protected long turnEndGameTime;
    /** 真人退出/断线后的托管标记（false=手动控制）。 */
    protected final boolean[] trusted = new boolean[2];
    /** 服务端世界引用（用于游戏刻计时；全假人房间为 null）。 */
    protected final ServerLevel level;

    /** 托管行动延迟状态。 */
    private boolean pendingAutoAct;
    private int pendingAutoActSeat = -1;
    private long autoActDueGameTime;
    private int autoActDelayCounter;
    /** 全假人房间（无世界引用）退化的本地 tick 计数器。 */
    private int tickCounter;

    /** 是否已结算（结算后拒绝一切操作）。 */
    protected boolean over;
    /** 最近动作描述（重连/旁观快照显示，如 "张三 落子 (3,4)"）。 */
    protected String lastAction = "";
    /** 结算缓存（重发/重开结算界面用）。 */
    protected byte winSeat = -1;
    protected int blackScore;
    protected int whiteScore;
    /** 结算原因序号：0=终局 1=认输 2=对方退出（见 settle 的 reason 映射）。 */
    protected byte resultReason;

    protected BoardGame(BoardRoom room) {
        this.room = room;
        this.size = room.size;
        this.board = new byte[size * size];
        ServerLevel found = null;
        for (int i = 0; i < 2; i++) {
            if (found == null && room.members[i] != null) {
                found = room.members[i].serverLevel();
            }
        }
        this.level = found;
    }

    public BoardPhase phase() {
        return phase;
    }

    public byte[] boardState() {
        return board;
    }

    /** 当前行动座位（调试命令用）。 */
    public int currentSeat() {
        return currentSeat;
    }

    /** 最近动作描述（调试命令/重连快照用）。 */
    public String lastAction() {
        return lastAction;
    }

    public abstract void start();

    /** 开始新一局（满 2 人开局 / 再来一局）：统一重置阶段后交给子类初始化。
     *  {@link #settle(byte, int, int, String)} 会把 phase 置为 SETTLED，
     *  子类 start() 若不复位 phase，再来一局后 onMove 的 phase 校验会拒绝所有落子——
     *  这里兜底重置，防止子类遗漏。 */
    public final void begin() {
        phase = BoardPhase.PLAYING;
        start();
    }

    public abstract void onMove(ServerPlayer player, int x, int y);

    public abstract void onPass(ServerPlayer player);

    /** 托管/超时自动行动（子类实现：AI 落子或自动停一手）。 */
    protected abstract void autoAct(int seat);

    /** 认输（三棋通用）：任意对局时刻均可，对方直接获胜。 */
    public void onSurrender(ServerPlayer player) {
        if (over || phase != BoardPhase.PLAYING) {
            return;
        }
        int seat = seatOf(player);
        if (seat < 0 || seat >= 2) {
            return;
        }
        byte winner = (byte) (1 - seat);
        winSeat = winner;
        lastAction = "wifi_card_games.board.lastaction.surrender|" + room.seatName(seat);
        room.broadcast(new SurrenderS2C(winner, room.seatName(winner)));
        settle(winner, boardScore((byte) 1), boardScore((byte) 2), "wifi_card_games.board.reason.surrender");
    }

    /** 玩家退出/断线：座位转托管；正轮到该座位时安排自动行动（延迟 1 秒）。 */
    public void onPlayerQuit(int seat) {
        if (seat < 0 || seat >= 2) {
            return;
        }
        trusted[seat] = true;
        if (seat == currentSeat && !over) {
            scheduleAutoAct(seat);
        }
    }

    /** 玩家重连：恢复手动控制（对局状态由 ReconnectS2C 快照同步）。 */
    public void onPlayerReconnect(int seat) {
        if (seat < 0 || seat >= 2) {
            return;
        }
        trusted[seat] = false;
        // 重连恢复手动：撤销该座位已安排的自动行动
        if (pendingAutoAct && pendingAutoActSeat == seat) {
            pendingAutoAct = false;
            pendingAutoActSeat = -1;
        }
    }

    /** 推进回合：广播 TurnS2C（含截止游戏刻）；托管/机器人座位自动安排行动。 */
    protected void turn(int seat) {
        if (over) {
            return;
        }
        currentSeat = seat;
        turnEndGameTime = (level != null ? level.getGameTime() : 0) + TURN_SECONDS * 20L;
        tickCounter = 0;
        autoActDelayCounter = 0;
        pendingAutoAct = false; // 回合推进时清除待行动状态
        pendingAutoActSeat = -1;
        room.broadcast(new TurnS2C((byte) seat, turnEndGameTime));
        if (trusted[seat] || room.isBot(seat)) {
            scheduleAutoAct(seat);
        }
    }

    private void scheduleAutoAct(int seat) {
        pendingAutoAct = true;
        pendingAutoActSeat = seat;
        autoActDueGameTime = (level != null ? level.getGameTime() : 0) + AUTO_ACT_DELAY_TICKS;
    }

    /** 服务端每 tick：托管延迟行动到点判断 + 超时判断（与客户端共用 level.getGameTime() 基准）。 */
    public void tick() {
        if (over || phase != BoardPhase.PLAYING) {
            return;
        }
        // 托管/机器人延迟 1 秒后自动行动（让玩家看清上一手）
        if (pendingAutoAct && currentSeat == pendingAutoActSeat) {
            boolean due = level != null
                    ? level.getGameTime() >= autoActDueGameTime
                    : ++autoActDelayCounter >= AUTO_ACT_DELAY_TICKS;
            if (due) {
                pendingAutoAct = false;
                autoAct(currentSeat);
                return;
            }
        }
        if (level != null) {
            if (level.getGameTime() >= turnEndGameTime) {
                autoAct(currentSeat);
            }
        } else {
            // 全假人房间（无世界引用）：退化为本地 tick 计数
            tickCounter++;
            if (tickCounter >= TURN_SECONDS * 20) {
                tickCounter = 0;
                autoAct(currentSeat);
            }
        }
    }

    /** 结算：广播 GameResultS2C 并标记房间结算时刻（空闲房间由管理器自动销毁）。
     *  reason 映射：认输=1（对方获胜）、退出=2（对方退出游戏）、其余终局=0。 */
    protected void settle(byte winnerSeat, int bScore, int wScore, String reason) {
        if (over) {
            return;
        }
        over = true;
        phase = BoardPhase.SETTLED;
        winSeat = winnerSeat;
        blackScore = bScore;
        whiteScore = wScore;
        resultReason = switch (reason) {
            case "wifi_card_games.board.reason.surrender" -> (byte) 1;
            case "wifi_card_games.board.reason.quit" -> (byte) 2;
            default -> (byte) 0;
        };
        room.broadcast(new GameResultS2C(winnerSeat, winnerSeat >= 0 ? room.seatName(winnerSeat) : "",
                bScore, wScore, resultReason));
        room.settledAtMillis = System.currentTimeMillis();
    }

    /** 棋盘上某色棋子数。 */
    protected int boardScore(byte color) {
        int n = 0;
        for (byte b : board) {
            if (b == color) {
                n++;
            }
        }
        return n;
    }

    /** 发送当前对局完整快照给指定座位（断线重连用）。 */
    public void syncTo(int seat) {
        room.sendToSeat(seat, new ReconnectS2C(board, (byte) phase.ordinal(), (byte) currentSeat,
                turnEndGameTime, lastAction));
        // 结算中：重发结算结果，客户端打开结算横幅（而非结束的对局界面）
        resendResult(seat);
    }

    /** 向旁观者发送当前对局完整快照（只读视角，棋盘公开）。 */
    public void syncToSpectator(ServerPlayer spectator) {
        room.sendToSpectator(spectator, new ReconnectS2C(board, (byte) phase.ordinal(), (byte) currentSeat,
                turnEndGameTime, lastAction));
        if (phase == BoardPhase.SETTLED) {
            room.sendToSpectator(spectator, new GameResultS2C(winSeat,
                    winSeat >= 0 ? room.seatName(winSeat) : "", blackScore, whiteScore, resultReason));
        }
    }

    /** 结算中重新下发结算结果（客户端重开界面 / 重连补发用）。 */
    private void resendResult(int seat) {
        if (phase == BoardPhase.SETTLED && seat >= 0 && seat < 2) {
            room.sendToSeat(seat, new GameResultS2C(winSeat,
                    winSeat >= 0 ? room.seatName(winSeat) : "", blackScore, whiteScore, resultReason));
        }
    }

    protected int seatOf(ServerPlayer player) {
        if (player == null) {
            return currentSeat; // 托管/断线自动行动
        }
        return room.seatOf(player);
    }

    protected void reject(int seat, Component message) {
        room.sendToSeat(seat, new NoticeS2C(message));
    }

    /** 座位对应的棋子色（座位 0=黑 1=白）。 */
    protected byte colorOf(int seat) {
        return (byte) (seat + 1);
    }
}
