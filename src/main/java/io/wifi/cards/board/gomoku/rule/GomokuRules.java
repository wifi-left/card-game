package io.wifi.cards.board.gomoku.rule;

/**
 * 五子棋规则引擎（15×15，纯 Java 无 MC 依赖，可单测）。
 * <p>棋盘为 byte[size*size]，行优先（idx = y*size+x）；0=空 1=黑 2=白。
 * 先五连者胜；无禁手规则。</p>
 */
public final class GomokuRules {
    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    /** 四个方向（dx, dy）：横/竖/两斜。 */
    private static final int[][] DIRS = {
            {1, 0}, {0, 1}, {1, 1}, {1, -1}
    };

    private GomokuRules() {
    }

    /** 尝试落子：点在盘内且为空则落子，返回 true。 */
    public static boolean applyMove(byte[] board, int size, int x, int y, byte player) {
        if (x < 0 || x >= size || y < 0 || y >= size || board[y * size + x] != EMPTY) {
            return false;
        }
        board[y * size + x] = player;
        return true;
    }

    /** 落子后是否形成五连（以落点为中心向四个方向各延伸 4 格计数）。 */
    public static boolean checkWin(byte[] board, int size, int x, int y, byte player) {
        for (int[] d : DIRS) {
            int count = 1;
            for (int s = 1; s < 5; s++) {
                int nx = x + d[0] * s;
                int ny = y + d[1] * s;
                if (in(size, nx, ny) && board[ny * size + nx] == player) {
                    count++;
                } else {
                    break;
                }
            }
            for (int s = 1; s < 5; s++) {
                int nx = x - d[0] * s;
                int ny = y - d[1] * s;
                if (in(size, nx, ny) && board[ny * size + nx] == player) {
                    count++;
                } else {
                    break;
                }
            }
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    /** 棋盘是否已满（平局条件）。 */
    public static boolean isFull(byte[] board, int size) {
        for (byte b : board) {
            if (b == EMPTY) {
                return false;
            }
        }
        return true;
    }

    private static boolean in(int size, int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }
}
