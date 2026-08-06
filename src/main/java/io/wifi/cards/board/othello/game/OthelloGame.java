package io.wifi.cards.board.othello.game;

import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.GameStartS2C;
import io.wifi.cards.board.network.BoardPackets.MoveBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.PassBroadcastS2C;
import io.wifi.cards.board.othello.rule.OthelloAi;
import io.wifi.cards.board.othello.rule.OthelloRules;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;

/**
 * 黑白棋对局状态机（6/8/10 等偶数尺寸，服务端权威）。
 * <p>黑方（座位 0）先手；落子后若对方无合法着则自动停一手并继续本方回合
 * （无手动停一手按钮，标准规则）；双方均无合法着时终局数子定胜负。
 * 托管/超时由 {@link OthelloAi} 自动落子。</p>
 */
public class OthelloGame extends BoardGame {
    public OthelloGame(BoardRoom room) {
        super(room);
    }

    @Override
    public void start() {
        byte[] init = OthelloRules.initialBoard(size);
        System.arraycopy(init, 0, board, 0, init.length);
        over = false;
        winSeat = -1;
        Arrays.fill(trusted, false); // 新局重置托管：上局托管状态不得残留到下一局
        lastAction = "游戏开始，黑方先手";
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
            reject(seat, "还没轮到你落子");
            return;
        }
        if (!OthelloRules.applyMove(board, size, x, y, colorOf(seat))) {
            reject(seat, "该位置不能落子");
            return;
        }
        lastAction = room.seatName(seat) + " 落子 (" + x + "," + y + ")";
        room.broadcast(new MoveBroadcastS2C((byte) seat, (byte) x, (byte) y, board));
        advanceAfterMove(seat);
    }

    @Override
    public void onPass(ServerPlayer player) {
        // 标准规则：有棋可下时必须落子，停一手由服务端自动处理（无合法着时换边）
        reject(seatOf(player), "有棋可下时不能停一手");
    }

    /** 落子后推进：对方无合法着 → 自动停一手，本方继续；双方均无着 → 终局数子。 */
    private void advanceAfterMove(int seat) {
        int next = 1 - seat;
        List<int[]> nextMoves = OthelloRules.legalMoves(board, size, colorOf(next));
        if (!nextMoves.isEmpty()) {
            turn(next);
            return;
        }
        room.broadcast(new PassBroadcastS2C((byte) next, room.seatName(next)));
        lastAction = room.seatName(next) + " 无棋可下，停一手";
        if (OthelloRules.legalMoves(board, size, colorOf(seat)).isEmpty()) {
            finish();
        } else {
            turn(seat); // 本方继续（若本方是托管会再次自动行动）
        }
    }

    /** 终局数子：子多者胜，同数平局。 */
    private void finish() {
        int black = OthelloRules.count(board, OthelloRules.BLACK);
        int white = OthelloRules.count(board, OthelloRules.WHITE);
        lastAction = "终局：黑 " + black + " · 白 " + white;
        if (black > white) {
            settle((byte) 0, black, white, "终局");
        } else if (white > black) {
            settle((byte) 1, black, white, "终局");
        } else {
            settle((byte) -1, black, white, "终局");
        }
    }

    @Override
    protected void autoAct(int seat) {
        int[] move = OthelloAi.findMove(board, size, colorOf(seat));
        if (move == null) {
            // 托管方无合法着：自动停一手；对方也无着则终局
            room.broadcast(new PassBroadcastS2C((byte) seat, room.seatName(seat)));
            lastAction = room.seatName(seat) + " 无棋可下，停一手";
            if (OthelloRules.legalMoves(board, size, colorOf(1 - seat)).isEmpty()) {
                finish();
            } else {
                turn(1 - seat);
            }
            return;
        }
        onMove(null, move[0], move[1]);
    }
}
