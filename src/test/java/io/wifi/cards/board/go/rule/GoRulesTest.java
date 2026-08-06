package io.wifi.cards.board.go.rule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 围棋规则引擎测试（纯 Java，不依赖 Minecraft 运行时）。
 */
class GoRulesTest {
    private static final int SIZE = 9;

    private static byte[] empty() {
        return new byte[SIZE * SIZE];
    }

    private static void put(byte[] board, int x, int y, byte v) {
        board[y * SIZE + x] = v;
    }

    private static byte at(byte[] board, int x, int y) {
        return board[y * SIZE + x];
    }

    /** 合法落子：空点可落，占位/越界被拒。 */
    @Test
    void applyMoveBasic() {
        byte[] b = empty();
        assertEquals(0, GoRules.applyMove(b, SIZE, 4, 4, GoRules.BLACK, -1, -1));
        assertEquals(GoRules.BLACK, at(b, 4, 4));
        assertEquals(-1, GoRules.applyMove(b, SIZE, 4, 4, GoRules.WHITE, -1, -1));
        assertEquals(-1, GoRules.applyMove(b, SIZE, -1, 0, GoRules.WHITE, -1, -1));
        assertEquals(-1, GoRules.applyMove(b, SIZE, 9, 0, GoRules.WHITE, -1, -1));
    }

    /** 提子：白 2 子只剩 1 口气，黑落该气提走 2 子。 */
    @Test
    void capturesSurroundedGroup() {
        byte[] b = empty();
        put(b, 2, 2, GoRules.WHITE);
        put(b, 3, 2, GoRules.WHITE);
        // 围三面（留 (4,2) 一口气）
        put(b, 1, 2, GoRules.BLACK);
        put(b, 2, 1, GoRules.BLACK);
        put(b, 3, 1, GoRules.BLACK);
        put(b, 2, 3, GoRules.BLACK);
        put(b, 3, 3, GoRules.BLACK);
        assertEquals(1, GoRules.liberties(b, SIZE, 2, 2));
        assertEquals(2, GoRules.applyMove(b, SIZE, 4, 2, GoRules.BLACK, -1, -1));
        assertEquals(GoRules.EMPTY, at(b, 2, 2));
        assertEquals(GoRules.EMPTY, at(b, 3, 2));
        assertEquals(GoRules.BLACK, at(b, 4, 2));
    }

    /** 禁自杀：黑四子围住的空点，白落入后无气且不提任何子 → 拒绝。 */
    @Test
    void forbidsSuicide() {
        byte[] b = empty();
        put(b, 1, 2, GoRules.BLACK);
        put(b, 3, 2, GoRules.BLACK);
        put(b, 2, 1, GoRules.BLACK);
        put(b, 2, 3, GoRules.BLACK);
        assertEquals(-1, GoRules.applyMove(b, SIZE, 2, 2, GoRules.WHITE, -1, -1));
        assertEquals(GoRules.EMPTY, at(b, 2, 2)); // 落子被回滚
    }

    /**
     * 简单劫：黑提白 1 子形成劫，白立即在被提子位置落子被拒；
     * 白先在他处落子（劫解除）后，再落原位置可提回。
     * 局面（9 路局部，B=黑 W=白 . = 空）：
     * <pre>
     * y=2:  . . B . . . . . .
     * y=3:  . B W . W . . . .
     * y=4:  . . B . . . . . .
     * </pre>
     * 另有白 (3,2)、(3,4)（黑落点 (3,3) 的上/下方，保证落点仅 1 气）。
     * 白 (2,3) 仅 1 气 (3,3)；黑落 (3,3) 提白 1 子；黑 (3,3) 亦仅 1 气 (2,3) → 白立即提回即劫。
     */
    @Test
    void simpleKo() {
        byte[] b = empty();
        put(b, 1, 3, GoRules.BLACK);   // 白 W 上方
        put(b, 2, 2, GoRules.BLACK);   // 白 W 左侧
        put(b, 2, 4, GoRules.BLACK);   // 白 W 下方
        put(b, 2, 3, GoRules.WHITE);   // 白单子 W，唯一气 (3,3)
        put(b, 4, 3, GoRules.WHITE);   // 黑落点 (3,3) 右侧（白）
        put(b, 3, 2, GoRules.WHITE);   // 黑落点 (3,3) 上方（白）
        put(b, 3, 4, GoRules.WHITE);   // 黑落点 (3,3) 下方（白）
        assertEquals(1, GoRules.liberties(b, SIZE, 2, 3));
        // 黑提劫：落 (3,3) 提白 (2,3)，被提子位置 (2,3)
        assertEquals(1, GoRules.applyMove(b, SIZE, 3, 3, GoRules.BLACK, -1, -1));
        assertEquals(GoRules.EMPTY, at(b, 2, 3));
        assertEquals(GoRules.BLACK, at(b, 3, 3));
        // 白立即落被提子位置提回 → 劫，拒绝
        assertEquals(-1, GoRules.applyMove(b, SIZE, 2, 3, GoRules.WHITE, 2, 3));
        assertEquals(GoRules.BLACK, at(b, 3, 3));
        // 白先落他处（劫解除：上一步未单提）
        assertEquals(0, GoRules.applyMove(b, SIZE, 0, 8, GoRules.WHITE, 2, 3));
        // 白再落原位置 → 提回黑 1 子，合法
        assertEquals(1, GoRules.applyMove(b, SIZE, 2, 3, GoRules.WHITE, -1, -1));
        assertEquals(GoRules.EMPTY, at(b, 3, 3));
        assertEquals(GoRules.WHITE, at(b, 2, 3));
    }

    /** 数子：黑 8 子围住单眼（1 空点），黑子 8 + 领地 1 = 9，白 0；
     *  远处白 1 子使外海邻接双方 → 外海不计任何一方。 */
    @Test
    void countScoreTerritory() {
        byte[] b = empty();
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                if (x == 2 && y == 2) {
                    continue; // 中心眼
                }
                put(b, x, y, GoRules.BLACK);
            }
        }
        put(b, 7, 7, GoRules.WHITE); // 外海邻接双方 → 公共
        int[] score = GoRules.countScore(b, SIZE);
        assertEquals(9, score[0]);
        assertEquals(1, score[1]);
    }

    /** 数子：贴边被单方围住的空点（角部 1 目）计入领地（中国规则）。 */
    @Test
    void countScoreEdgeTerritory() {
        byte[] b = empty();
        // 黑沿左上角围出 (0,0) 1 目
        put(b, 1, 0, GoRules.BLACK);
        put(b, 0, 1, GoRules.BLACK);
        put(b, 7, 7, GoRules.WHITE); // 外海邻接双方 → 公共
        int[] score = GoRules.countScore(b, SIZE);
        assertEquals(3, score[0]); // 黑 2 子 + 角部领地 1
        assertEquals(1, score[1]);
    }

    /** 数子：无气块在终局时被提清（死子不参与计分），眼位计入领地；远处白子使外海为公共。 */
    @Test
    void countScoreRemovesDead() {
        byte[] b = empty();
        // 白单子被黑完全围死（0 气）
        put(b, 2, 2, GoRules.WHITE);
        put(b, 1, 2, GoRules.BLACK);
        put(b, 3, 2, GoRules.BLACK);
        put(b, 2, 1, GoRules.BLACK);
        put(b, 2, 3, GoRules.BLACK);
        put(b, 7, 7, GoRules.WHITE); // 外海邻接双方 → 公共
        int[] score = GoRules.countScore(b, SIZE);
        // 黑 4 子 + 提掉死白后的眼位 1 空点 = 5；白 1 子
        assertEquals(5, score[0]);
        assertEquals(1, score[1]);
    }

    /** 贴目胜负：9 路黑须 > 40.5 + 3.75 = 44.25，即黑 ≥ 45 胜；19 路黑 ≥ 185 胜。 */
    @Test
    void winnerWithKomi() {
        assertEquals(GoRules.BLACK, GoRules.winner(9, 45, 36));
        assertEquals(GoRules.WHITE, GoRules.winner(9, 44, 37));
        assertEquals(GoRules.BLACK, GoRules.winner(19, 185, 176));
        assertEquals(GoRules.WHITE, GoRules.winner(19, 184, 177));
    }
}
