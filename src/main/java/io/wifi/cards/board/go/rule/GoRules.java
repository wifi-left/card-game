package io.wifi.cards.board.go.rule;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 围棋规则引擎（9/19 路可变，纯 Java 无 MC 依赖，可单测）。
 * <p>棋盘为 byte[size*size]，行优先（idx = y*size+x）；0=空 1=黑 2=白。
 * 落子在交叉点上（坐标 0..size-1）。</p>
 * <ul>
 *   <li>提子：落子后无气的相邻对方棋块被提出</li>
 *   <li>禁自杀：落子后自身棋块无气（且未提掉对方）为非法</li>
 *   <li>简单劫：上一步恰提 1 子时，禁止对方立即在原提子点落子（恢复局面）</li>
 *   <li>终局数子（中国规则）：双方停手后清死子，空点按区域归属（邻接纯单方棋子），
 *       黑贴 3.75 子判定胜负。复杂劫/双劫不处理（休闲规则）。</li>
 * </ul>
 */
public final class GoRules {
    public static final byte EMPTY = 0;
    public static final byte BLACK = 1;
    public static final byte WHITE = 2;

    /** 贴目（子）：黑须超过总交叉点一半 + KOMI 子获胜。 */
    public static final double KOMI = 3.75;

    /** 四个方向（dx, dy）。 */
    private static final int[][] DIRS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1}
    };

    private GoRules() {
    }

    /**
     * 尝试落子。
     *
     * @param lastCapturedX 上一步提 1 子时被提子的位置 x（无则 -1；提多子无劫，同样为 -1）
     * @param lastCapturedY 上一步被提子的位置 y（无则 -1）
     * @return 提子数（≥0）；-1 表示非法（占位/禁自杀/劫）
     */
    public static int applyMove(byte[] board, int size, int x, int y, byte player,
                                int lastCapturedX, int lastCapturedY) {
        if (x < 0 || x >= size || y < 0 || y >= size || board[y * size + x] != EMPTY) {
            return -1;
        }
        byte other = (byte) (3 - player);
        byte[] snapshot = board.clone(); // 拒绝（禁自杀/劫）时整体回滚：提子已执行，仅恢复落子点会残留
        board[y * size + x] = player;
        int captured = 0;
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (in(size, nx, ny) && board[ny * size + nx] == other && liberties(board, size, nx, ny) == 0) {
                captured += removeGroup(board, size, nx, ny);
            }
        }
        if (captured == 0 && liberties(board, size, x, y) == 0) {
            // 禁自杀
            System.arraycopy(snapshot, 0, board, 0, board.length);
            return -1;
        }
        if (captured == 1 && x == lastCapturedX && y == lastCapturedY) {
            // 简单劫：上一步恰提 1 子，立即在被提子位置落子会提回并恢复局面，禁止
            System.arraycopy(snapshot, 0, board, 0, board.length);
            return -1;
        }
        return captured;
    }

    /** 坐标 (x, y) 所在棋块的气数（空邻接点数）。 */
    public static int liberties(byte[] board, int size, int x, int y) {
        byte color = board[y * size + x];
        if (color == EMPTY) {
            return 0;
        }
        boolean[] visited = new boolean[size * size];
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{x, y});
        visited[y * size + x] = true;
        int libs = 0;
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            for (int[] d : DIRS) {
                int nx = p[0] + d[0];
                int ny = p[1] + d[1];
                if (!in(size, nx, ny) || visited[ny * size + nx]) {
                    continue;
                }
                byte v = board[ny * size + nx];
                if (v == EMPTY) {
                    libs++;
                    visited[ny * size + nx] = true; // 同块内一个气只计一次
                } else if (v == color) {
                    visited[ny * size + nx] = true;
                    stack.push(new int[]{nx, ny});
                }
            }
        }
        return libs;
    }

    /** 提出 (x, y) 所在的无气棋块，返回提子数。 */
    private static int removeGroup(byte[] board, int size, int x, int y) {
        byte color = board[y * size + x];
        if (color == EMPTY) {
            return 0;
        }
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{x, y});
        board[y * size + x] = EMPTY;
        int removed = 1;
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            for (int[] d : DIRS) {
                int nx = p[0] + d[0];
                int ny = p[1] + d[1];
                if (in(size, nx, ny) && board[ny * size + nx] == color) {
                    board[ny * size + nx] = EMPTY;
                    removed++;
                    stack.push(new int[]{nx, ny});
                }
            }
        }
        return removed;
    }

    /**
     * 终局数子（中国规则）：先提清无气死子，再按区域归属计分。
     *
     * @return {黑子+黑领地, 白子+白领地}
     */
    public static int[] countScore(byte[] board, int size) {
        removeDead(board, size);
        boolean[] visited = new boolean[size * size];
        int black = 0;
        int white = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (board[y * size + x] == EMPTY && !visited[y * size + x]) {
                    // flood fill 空点区域：统计邻接棋子颜色集合。
                    // 中国规则数子：区域邻接纯单方棋子 = 该方领地（含贴边/角部被围的目）；
                    // 邻接双方（外海/单官交界）或未邻接任何棋子（全空盘）不计任何一方。
                    int area = 0;
                    boolean touchBlack = false;
                    boolean touchWhite = false;
                    Deque<int[]> stack = new ArrayDeque<>();
                    stack.push(new int[]{x, y});
                    visited[y * size + x] = true;
                    while (!stack.isEmpty()) {
                        int[] p = stack.pop();
                        area++;
                        for (int[] d : DIRS) {
                            int nx = p[0] + d[0];
                            int ny = p[1] + d[1];
                            if (!in(size, nx, ny)) {
                                continue; // 棋盘边界不产生敞口：贴边区域由"邻接棋子纯单色"判定归属
                            }
                            byte v = board[ny * size + nx];
                            if (v == EMPTY && !visited[ny * size + nx]) {
                                visited[ny * size + nx] = true;
                                stack.push(new int[]{nx, ny});
                            } else if (v == BLACK) {
                                touchBlack = true;
                            } else if (v == WHITE) {
                                touchWhite = true;
                            }
                        }
                    }
                    if (touchBlack && !touchWhite) {
                        black += area;
                    } else if (touchWhite && !touchBlack) {
                        white += area;
                    }
                    // 双方交界区域（眼位/单官争议）不计
                }
            }
        }
        for (byte b : board) {
            if (b == BLACK) {
                black++;
            } else if (b == WHITE) {
                white++;
            }
        }
        return new int[]{black, white};
    }

    /**
     * 判定胜负（黑贴 {@value #KOMI} 子，总交叉点为奇数时必有胜负）。
     *
     * @return 1=黑胜 2=白胜（0 为防御性平局）
     */
    public static int winner(int size, int blackScore, int whiteScore) {
        int total = size * size;
        // 黑胜：black > total/2 + 3.75 ⇔ 2*black >= total + 8
        if (blackScore * 2 >= total + 8) {
            return BLACK;
        }
        return WHITE;
    }

    /** 终局防御：提出所有无气棋块（双方停手后理论不存在，防御性清理）。 */
    private static void removeDead(byte[] board, int size) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (board[y * size + x] != EMPTY && liberties(board, size, x, y) == 0) {
                    removeGroup(board, size, x, y);
                }
            }
        }
    }

    private static boolean in(int size, int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }
}
