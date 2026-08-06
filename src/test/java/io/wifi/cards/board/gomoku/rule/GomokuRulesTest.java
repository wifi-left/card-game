package io.wifi.cards.board.gomoku.rule;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五子棋规则引擎测试（纯 Java，不依赖 Minecraft 运行时）。
 */
class GomokuRulesTest {
    private static final int SIZE = 15;

    private static byte[] empty() {
        return new byte[SIZE * SIZE];
    }

    private static void put(byte[] board, int x, int y, byte v) {
        board[y * SIZE + x] = v;
    }

    @Test
    void applyMove() {
        byte[] b = empty();
        assertTrue(GomokuRules.applyMove(b, SIZE, 7, 7, GomokuRules.BLACK));
        assertEquals(GomokuRules.BLACK, b[7 * SIZE + 7]);
        assertFalse(GomokuRules.applyMove(b, SIZE, 7, 7, GomokuRules.WHITE)); // 占位
        assertFalse(GomokuRules.applyMove(b, SIZE, -1, 0, GomokuRules.WHITE)); // 越界
        assertFalse(GomokuRules.applyMove(b, SIZE, 15, 0, GomokuRules.WHITE));
    }

    /** 横向五连。 */
    @Test
    void winHorizontal() {
        byte[] b = empty();
        for (int i = 3; i <= 6; i++) {
            put(b, i, 5, GomokuRules.BLACK);
        }
        assertFalse(GomokuRules.checkWin(b, SIZE, 6, 5, GomokuRules.BLACK)); // 四连不赢
        put(b, 7, 5, GomokuRules.BLACK);
        assertTrue(GomokuRules.checkWin(b, SIZE, 5, 5, GomokuRules.BLACK));
    }

    /** 竖向与两条斜向五连。 */
    @Test
    void winVerticalAndDiagonals() {
        byte[] b = empty();
        for (int i = 0; i < 5; i++) {
            put(b, 3, 2 + i, GomokuRules.BLACK);    // 竖：x=3, y=2..6
            put(b, 5 + i, 7 + i, GomokuRules.WHITE); // 主斜 \：x=5..9, y=7..11
            put(b, 9 - i, 10 + i, GomokuRules.BLACK); // 反斜 /：x=9..5, y=10..14
        }
        assertTrue(GomokuRules.checkWin(b, SIZE, 3, 4, GomokuRules.BLACK));
        assertTrue(GomokuRules.checkWin(b, SIZE, 7, 9, GomokuRules.WHITE));
        assertTrue(GomokuRules.checkWin(b, SIZE, 7, 12, GomokuRules.BLACK));
    }

    /** 满盘判定。 */
    @Test
    void isFull() {
        byte[] b = empty();
        assertFalse(GomokuRules.isFull(b, SIZE));
        for (int i = 0; i < b.length; i++) {
            b[i] = GomokuRules.BLACK;
        }
        assertTrue(GomokuRules.isFull(b, SIZE));
    }

    /** AI：空盘落中心。 */
    @Test
    void aiEmptyBoardCenter() {
        int[] move = GomokuAi.findMove(empty(), SIZE, GomokuRules.BLACK);
        assertNotNull(move);
        assertEquals(SIZE / 2, move[0]);
        assertEquals(SIZE / 2, move[1]);
    }

    /** AI：本方四连时优先补成五连（进攻）。 */
    @Test
    void aiCompletesFive() {
        byte[] b = empty();
        for (int i = 5; i <= 8; i++) {
            put(b, i, 5, GomokuRules.BLACK);
        }
        int[] move = GomokuAi.findMove(b, SIZE, GomokuRules.BLACK);
        assertNotNull(move);
        assertEquals(5, move[1]);
        assertTrue(move[0] == 4 || move[0] == 9);
    }

    /** AI：对方活三时优先堵截（防守权重）。 */
    @Test
    void aiBlocksOpponent() {
        byte[] b = empty();
        for (int i = 5; i <= 7; i++) {
            put(b, i, 5, GomokuRules.WHITE); // 白活三（两端空）
        }
        int[] move = GomokuAi.findMove(b, SIZE, GomokuRules.BLACK);
        assertNotNull(move);
        assertEquals(5, move[1]);
        assertTrue(move[0] == 4 || move[0] == 8);
    }

    /** AI：对方四连（两端空，下一手即五连）时必须封堵（回归：防守估值溢出导致不堵的 bug）。 */
    @Test
    void aiBlocksOpponentFour() {
        byte[] b = empty();
        for (int i = 5; i <= 8; i++) {
            put(b, i, 5, GomokuRules.WHITE); // 白四连，两端空
        }
        int[] move = GomokuAi.findMove(b, SIZE, GomokuRules.BLACK);
        assertNotNull(move);
        assertEquals(5, move[1]);
        assertTrue(move[0] == 4 || move[0] == 9);
    }

    /** AI：满盘返回 null。 */
    @Test
    void aiFullBoard() {
        byte[] b = new byte[SIZE * SIZE];
        Arrays.fill(b, GomokuRules.BLACK);
        assertNull(GomokuAi.findMove(b, SIZE, GomokuRules.WHITE));
    }
}
