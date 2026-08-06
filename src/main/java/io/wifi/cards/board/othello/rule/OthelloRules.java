package io.wifi.cards.board.othello.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 黑白棋（Reversi/Othello）规则引擎（6/8/10 等任意偶数尺寸，纯 Java 无 MC 依赖，可单测）。
 * <p>棋盘为 byte[size*size]，行优先（idx = y*size+x）；0=空 1=黑 2=白。
 * 初始四子位于棋盘正中（尺寸须为偶数，黑白棋标准变体均为偶数）。</p>
 */
public final class OthelloRules {
    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    /** 八个方向（dx, dy）。 */
    private static final int[][] DIRS = {
            {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    private OthelloRules() {
    }

    /** 初始棋盘：中心四子（黑斜对角、白斜对角）。 */
    public static byte[] initialBoard(int size) {
        byte[] b = new byte[size * size];
        int m = size / 2;
        b[(m - 1) * size + (m - 1)] = WHITE;
        b[(m - 1) * size + m] = BLACK;
        b[m * size + (m - 1)] = BLACK;
        b[m * size + m] = WHITE;
        return b;
    }

    /** 某方所有合法着（{x, y} 列表），空列表 = 无棋可下。 */
    public static List<int[]> legalMoves(byte[] board, int size, byte player) {
        List<int[]> list = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (flippedCount(board, size, x, y, player) > 0) {
                    list.add(new int[]{x, y});
                }
            }
        }
        return list;
    }

    /** 在 (x, y) 落子可翻转的棋子数；0 表示该点不可落（占位/无法夹住）。 */
    public static int flippedCount(byte[] board, int size, int x, int y, byte player) {
        if (x < 0 || x >= size || y < 0 || y >= size || board[y * size + x] != EMPTY) {
            return 0;
        }
        byte other = (byte) (3 - player);
        int total = 0;
        for (int[] d : DIRS) {
            int cx = x + d[0];
            int cy = y + d[1];
            int count = 0;
            while (in(size, cx, cy) && board[cy * size + cx] == other) {
                count++;
                cx += d[0];
                cy += d[1];
            }
            // 该方向末端必须是本方棋子（否则不翻转）
            if (count > 0 && in(size, cx, cy) && board[cy * size + cx] == player) {
                total += count;
            }
        }
        return total;
    }

    /** 尝试落子：合法则落子并翻转，返回 true。 */
    public static boolean applyMove(byte[] board, int size, int x, int y, byte player) {
        int flipped = flippedCount(board, size, x, y, player);
        if (flipped <= 0) {
            return false;
        }
        board[y * size + x] = player;
        byte other = (byte) (3 - player);
        for (int[] d : DIRS) {
            int cx = x + d[0];
            int cy = y + d[1];
            int count = 0;
            while (in(size, cx, cy) && board[cy * size + cx] == other) {
                count++;
                cx += d[0];
                cy += d[1];
            }
            if (count > 0 && in(size, cx, cy) && board[cy * size + cx] == player) {
                // 沿反方向逐格翻回
                for (int i = 0; i < count; i++) {
                    cx -= d[0];
                    cy -= d[1];
                    board[cy * size + cx] = player;
                }
            }
        }
        return true;
    }

    /** 某方棋子总数。 */
    public static int count(byte[] board, byte player) {
        int n = 0;
        for (byte b : board) {
            if (b == player) {
                n++;
            }
        }
        return n;
    }

    private static boolean in(int size, int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }
}
