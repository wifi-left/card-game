package io.wifi.cards.board.othello.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 黑白棋规则引擎测试（纯 Java，不依赖 Minecraft 运行时）。
 */
class OthelloRulesTest {

    private static byte at(byte[] board, int x, int y) {
        return board[y * 8 + x];
    }

    @Test
    void initialBoardHasCenterFour() {
        byte[] b = OthelloRules.initialBoard();
        assertEquals(OthelloRules.WHITE, at(b, 3, 3));
        assertEquals(OthelloRules.BLACK, at(b, 4, 3));
        assertEquals(OthelloRules.BLACK, at(b, 3, 4));
        assertEquals(OthelloRules.WHITE, at(b, 4, 4));
        assertEquals(2, OthelloRules.count(b, OthelloRules.BLACK));
        assertEquals(2, OthelloRules.count(b, OthelloRules.WHITE));
    }

    /** 标准开局：黑方恰好 4 个合法着（对角线方向）。 */
    @Test
    void initialLegalMoves() {
        byte[] b = OthelloRules.initialBoard();
        List<int[]> moves = OthelloRules.legalMoves(b, OthelloRules.BLACK);
        assertEquals(4, moves.size());
        for (int[] m : moves) {
            assertTrue(m[0] == 2 && m[1] == 3 || m[0] == 3 && m[1] == 2
                    || m[0] == 4 && m[1] == 5 || m[0] == 5 && m[1] == 4,
                    "非法着点: (" + m[0] + "," + m[1] + ")");
        }
    }

    /** 落子 (2,3)：沿对角翻转 (3,3) 的白子，白子翻成黑子。 */
    @Test
    void applyMoveFlipsDiagonal() {
        byte[] b = OthelloRules.initialBoard();
        assertTrue(OthelloRules.applyMove(b, 2, 3, OthelloRules.BLACK));
        assertEquals(OthelloRules.BLACK, at(b, 2, 3));
        assertEquals(OthelloRules.BLACK, at(b, 3, 3)); // 被翻转
        assertEquals(OthelloRules.BLACK, at(b, 4, 3)); // 保持
        assertEquals(4, OthelloRules.count(b, OthelloRules.BLACK));
        assertEquals(1, OthelloRules.count(b, OthelloRules.WHITE));
    }

    /** 占位点与无法夹住的点均不可落。 */
    @Test
    void applyMoveRejectsInvalid() {
        byte[] b = OthelloRules.initialBoard();
        assertFalse(OthelloRules.applyMove(b, 3, 3, OthelloRules.BLACK)); // 占位
        assertFalse(OthelloRules.applyMove(b, 0, 0, OthelloRules.BLACK)); // 无翻转
        assertFalse(OthelloRules.applyMove(b, -1, 0, OthelloRules.BLACK)); // 越界
    }

    /** 水平翻转：黑落 (3,3)，右侧 白白黑 → 翻转两白为黑。 */
    @Test
    void applyMoveFlipsHorizontalRow() {
        byte[] b = new byte[64];
        b[3 * 8 + 3] = OthelloRules.EMPTY; // 落点
        b[3 * 8 + 4] = OthelloRules.WHITE;
        b[3 * 8 + 5] = OthelloRules.WHITE;
        b[3 * 8 + 6] = OthelloRules.BLACK; // 末端
        assertEquals(2, OthelloRules.flippedCount(b, 3, 3, OthelloRules.BLACK));
        assertTrue(OthelloRules.applyMove(b, 3, 3, OthelloRules.BLACK));
        assertEquals(OthelloRules.BLACK, b[3 * 8 + 3]);
        assertEquals(OthelloRules.BLACK, b[3 * 8 + 4]);
        assertEquals(OthelloRules.BLACK, b[3 * 8 + 5]);
        assertEquals(OthelloRules.BLACK, b[3 * 8 + 6]);
    }

    /** 无合法着：全黑棋盘白方无法落子。 */
    @Test
    void noLegalMoves() {
        byte[] b = new byte[64];
        java.util.Arrays.fill(b, OthelloRules.BLACK);
        assertTrue(OthelloRules.legalMoves(b, OthelloRules.WHITE).isEmpty());
    }

    /** AI：无合法着返回 null；初始局面返回合法着之一。 */
    @Test
    void aiFindMove() {
        assertNull(OthelloAi.findMove(allBlackBoard(), OthelloRules.WHITE));
        byte[] init = OthelloRules.initialBoard();
        int[] move = OthelloAi.findMove(init, OthelloRules.BLACK);
        assertNotNull(move);
        assertTrue(OthelloRules.flippedCount(init, move[0], move[1], OthelloRules.BLACK) > 0);
    }

    private static byte[] allBlackBoard() {
        byte[] b = new byte[64];
        java.util.Arrays.fill(b, OthelloRules.BLACK);
        return b;
    }
}
