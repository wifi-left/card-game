package io.wifi.cards.board.go.game;

import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.go.rule.GoRules;
import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.GameStartS2C;
import io.wifi.cards.board.network.BoardPackets.MoveBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.NoticeS2C;
import io.wifi.cards.board.network.BoardPackets.PassBroadcastS2C;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * 围棋对局状态机（9/19 路，服务端权威）。
 * <p>黑方（座位 0）先手，落子于交叉点；规则：气/提子/禁自杀/简单劫
 * （见 {@link GoRules}）。双方连续停一手即终局，按中国规则数子、黑贴 3.75 子定胜负。</p>
 * <p><b>无托管（与五子棋/黑白棋不同）</b>：</p>
 * <ul>
 *   <li>超时（{@value BoardGame#TURN_SECONDS} 秒）未落子 → 直接跳过，轮到对方（超时不算停一手，不影响劫/数子）</li>
 *   <li>双方连续 {@value #MAX_SKIP} 手无人落子（超时/挂机座位）→ 按当前局面数子终局</li>
 *   <li>一方退出/断线 → 直接结束本局（对方获胜），不托管续玩</li>
 *   <li>调试机器人（围棋不提供创建，仅 debug 命令残留）座位每轮自动跳过，直到上述终局条件</li>
 * </ul>
 */
public class GoGame extends BoardGame {
    /** 连续超时跳过上限：双方"连续几轮没下棋"即数子终局（4 手 = 双方各 2 次超时）。 */
    private static final int MAX_SKIP = 4;

    /** 上一步提 1 子时被提子的位置（简单劫判定用；-1=上一步未单提）。 */
    private int lastCapturedX = -1;
    private int lastCapturedY = -1;
    /** 连续停手计数（>=2 终局，标准围棋规则）。 */
    private int passCount;
    /** 连续超时跳过计数（仅落子清零；>=MAX_SKIP 终局，手动停一手不重置）。 */
    private int skipCount;

    public GoGame(BoardRoom room) {
        super(room);
    }

    @Override
    public void start() {
        Arrays.fill(board, GoRules.EMPTY);
        over = false;
        winSeat = -1;
        Arrays.fill(trusted, false); // 围棋无托管，防御性重置（防残留）
        lastCapturedX = -1;
        lastCapturedY = -1;
        passCount = 0;
        skipCount = 0;
        lastAction = "wifi_card_games.board.lastaction.game_start";
        room.broadcast(new GameStartS2C(board, (byte) 0));
        turn(0);
    }

    @Override
    public void onMove(ServerPlayer player, int x, int y) {
        if (over || phase != BoardPhase.PLAYING) {
            return;
        }
        int seat = seatOf(player);
        if (seat != currentSeat) {
            reject(seat, Component.translatable("wifi_card_games.board.error.not_your_move"));
            return;
        }
        // 落子前快照：单提时据此定位被提子位置（劫判定用）
        byte[] snapshot = board.clone();
        int captured = GoRules.applyMove(board, size, x, y, colorOf(seat),
                lastCapturedX, lastCapturedY);
        if (captured < 0) {
            reject(seat, Component.translatable("wifi_card_games.board.error.go_invalid_move"));
            return;
        }
        if (captured == 1) {
            byte opp = (byte) (3 - colorOf(seat));
            for (int i = 0; i < board.length; i++) {
                if (snapshot[i] == opp && board[i] == GoRules.EMPTY) {
                    lastCapturedX = i % size;
                    lastCapturedY = i / size;
                    break;
                }
            }
        } else {
            lastCapturedX = -1; // 提多子/未提：无劫
            lastCapturedY = -1;
        }
        passCount = 0; // 落子重置连续停手
        skipCount = 0; // 落子重置连续跳过
        lastAction = "wifi_card_games.board.lastaction.go_moved|" + room.seatName(seat) + "|" + x + "|" + y
                + (captured > 0 ? "|" + captured : "|0");
        room.broadcast(new MoveBroadcastS2C((byte) seat, (byte) x, (byte) y, board));
        turn(1 - seat);
    }

    @Override
    public void onPass(ServerPlayer player) {
        if (over || phase != BoardPhase.PLAYING) {
            return;
        }
        int seat = seatOf(player);
        if (seat != currentSeat) {
            reject(seat, Component.translatable("wifi_card_games.board.error.not_your_turn"));
            return;
        }
        passCount++;
        lastAction = "wifi_card_games.board.lastaction.passed|" + room.seatName(seat);
        room.broadcast(new PassBroadcastS2C((byte) seat, room.seatName(seat)));
        if (passCount >= 2) {
            // 双方连续停手：终局数子
            finishByScore("wifi_card_games.board.reason.double_pass");
            return;
        }
        turn(1 - seat);
    }

    /** 退出/断线：直接结束本局（围棋无托管），对方获胜。 */
    @Override
    public void onPlayerQuit(int seat) {
        if (over || phase != BoardPhase.PLAYING || seat < 0 || seat >= 2) {
            return;
        }
        byte winner = (byte) (1 - seat);
        lastAction = "wifi_card_games.board.lastaction.quit|" + room.seatName(seat);
        settle(winner, boardScore((byte) 1), boardScore((byte) 2), "wifi_card_games.board.reason.quit");
    }

    /** 超时未落子/挂机座位：直接跳过轮到对方；连续 {@value #MAX_SKIP} 手无人落子则数子终局。 */
    @Override
    protected void autoAct(int seat) {
        skipCount++;
        if (skipCount >= MAX_SKIP) {
            finishByScore("wifi_card_games.board.reason.timeout_end");
            return;
        }
        String name = room.seatName(seat);
        lastAction = "wifi_card_games.board.lastaction.timeout|" + name;
        room.broadcast(new NoticeS2C(Component.translatable(
                    "wifi_card_games.board.info.timeout_skip", name)));
        turn(1 - seat);
    }

    /** 终局数子（中国规则，黑贴 {@value GoRules#KOMI} 子）。
     *  在棋盘副本上计算：countScore 内部会提清死子，直接修改对局棋盘会导致
     *  玩家客户端（结算时广播的终局局面）与事后旁观者快照显示不一致。 */
    private void finishByScore(String reason) {
        int[] score = GoRules.countScore(board.clone(), size);
        byte win = (byte) GoRules.winner(size, score[0], score[1]);
        lastAction = "wifi_card_games.board.lastaction.go_end|" + score[0] + "|" + score[1] + "|" + GoRules.KOMI;
        settle(win, score[0], score[1], reason);
    }
}
