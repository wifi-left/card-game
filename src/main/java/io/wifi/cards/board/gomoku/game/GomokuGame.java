package io.wifi.cards.board.gomoku.game;

import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.gomoku.rule.GomokuAi;
import io.wifi.cards.board.gomoku.rule.GomokuRules;
import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.GameStartS2C;
import io.wifi.cards.board.network.BoardPackets.MoveBroadcastS2C;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * 五子棋对局状态机（15×15，服务端权威）。
 * <p>黑方（座位 0）先手；先形成五连者胜，棋盘下满平局；无停一手/禁手规则。
 * 托管/超时由 {@link GomokuAi} 自动落子。</p>
 */
public class GomokuGame extends BoardGame {
    public GomokuGame(BoardRoom room) {
        super(room);
    }

    @Override
    public void start() {
        Arrays.fill(board, GomokuRules.EMPTY);
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
        if (!GomokuRules.applyMove(board, size, x, y, colorOf(seat))) {
            reject(seat, "该位置已有棋子");
            return;
        }
        lastAction = room.seatName(seat) + " 落子 (" + x + "," + y + ")";
        room.broadcast(new MoveBroadcastS2C((byte) seat, (byte) x, (byte) y, board));
        if (GomokuRules.checkWin(board, size, x, y, colorOf(seat))) {
            lastAction = room.seatName(seat) + " 五连获胜";
            settle((byte) seat, boardScore(colorOf(seat)), boardScore((byte) (3 - colorOf(seat))), "五连");
            return;
        }
        if (GomokuRules.isFull(board, size)) {
            lastAction = "棋盘已满，平局";
            settle((byte) -1, boardScore((byte) 1), boardScore((byte) 2), "棋盘已满");
            return;
        }
        turn(1 - seat);
    }

    @Override
    public void onPass(ServerPlayer player) {
        reject(seatOf(player), "五子棋没有停一手");
    }

    @Override
    protected void autoAct(int seat) {
        int[] move = GomokuAi.findMove(board, size, colorOf(seat));
        if (move == null) {
            lastAction = "棋盘已满，平局";
            settle((byte) -1, boardScore((byte) 1), boardScore((byte) 2), "棋盘已满");
            return;
        }
        onMove(null, move[0], move[1]);
    }
}
