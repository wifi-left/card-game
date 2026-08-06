package io.wifi.cards.board.gomoku.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.wifi.cards.board.gomoku.rule.GomokuRules.EMPTY;

/**
 * 五子棋 AI（评分表攻防）：对每个候选空点（已有子 2 格范围内）分别评估
 * 进攻（本方落子后连子价值）与防守（对方在此落子的威胁，权重略高），
 * 总分最高者落子；同分随机打破平局。
 * <p>作为调试假人/托管/超时自动落子用。</p>
 */
public final class GomokuAi {
    private static final Random RANDOM = new Random();

    /** 连子评分表 [len][open]：len=连子数(1~5)，open=两端开放数(0~2)。 */
    private static final int[][] SCORE = {
            {0, 1, 2},        // len 1
            {5, 10, 20},      // len 2
            {50, 200, 500},   // len 3
            {1000, 5000, 10000}, // len 4
            {1000000, 1000000, 1000000} // len 5
    };

    /** 防守权重（对方威胁比本方进攻略优先）。 */
    private static final int DEFENSE_WEIGHT = 3;

    /** 四个方向（dx, dy）：横/竖/两斜。 */
    private static final int[][] DIRS = {
            {1, 0}, {0, 1}, {1, 1}, {1, -1}
    };

    private GomokuAi() {
    }

    /**
     * 为 player 选一步落点；棋盘已满返回 null。
     */
    public static int[] findMove(byte[] board, int size, byte player) {
        List<int[]> candidates = candidates(board, size);
        if (candidates.isEmpty()) {
            return null;
        }
        byte other = (byte) (3 - player);
        long best = Long.MIN_VALUE;
        List<int[]> bestMoves = new ArrayList<>();
        for (int[] c : candidates) {
            long attack = evaluateAt(board, size, c[0], c[1], player);
            if (attack == Long.MAX_VALUE) {
                return c; // 己方直接五连：立即落子（避免 MAX 参与加法溢出为负数）
            }
            long defense = evaluateAt(board, size, c[0], c[1], other);
            if (defense == Long.MAX_VALUE) {
                return c; // 对方在此落子即五连：必须封堵（防守与进攻同权，先手封堵）
            }
            long score = attack + defense * DEFENSE_WEIGHT;
            if (score > best) {
                best = score;
                bestMoves.clear();
                bestMoves.add(c);
            } else if (score == best) {
                bestMoves.add(c);
            }
        }
        return bestMoves.get(RANDOM.nextInt(bestMoves.size()));
    }

    /** 候选点：空盘取中心；否则取距任一棋子 ≤2 的空点（减少计算量且更聪明）。 */
    private static List<int[]> candidates(byte[] board, int size) {
        List<int[]> list = new ArrayList<>();
        boolean anyStone = false;
        for (byte b : board) {
            if (b != EMPTY) {
                anyStone = true;
                break;
            }
        }
        if (!anyStone) {
            int c = size / 2;
            list.add(new int[]{c, c});
            return list;
        }
        boolean[][] used = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (board[y * size + x] == EMPTY) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < size && ny >= 0 && ny < size
                                && board[ny * size + nx] == EMPTY && !used[ny][nx]) {
                            used[ny][nx] = true;
                            list.add(new int[]{nx, ny});
                        }
                    }
                }
            }
        }
        return list;
    }

    /**
     * 假设 player 在 (x, y) 落子，四个方向连子价值之和。
     * 返回 Long.MAX_VALUE 表示直接五连。
     */
    private static long evaluateAt(byte[] board, int size, int x, int y, byte player) {
        long total = 0;
        for (int[] d : DIRS) {
            int len = 1;
            int open = 0;
            // 正方向
            int nx = x + d[0];
            int ny = y + d[1];
            while (in(size, nx, ny) && board[ny * size + nx] == player) {
                len++;
                nx += d[0];
                ny += d[1];
            }
            if (in(size, nx, ny) && board[ny * size + nx] == EMPTY) {
                open++;
            }
            // 负方向
            nx = x - d[0];
            ny = y - d[1];
            while (in(size, nx, ny) && board[ny * size + nx] == player) {
                len++;
                nx -= d[0];
                ny -= d[1];
            }
            if (in(size, nx, ny) && board[ny * size + nx] == EMPTY) {
                open++;
            }
            if (len >= 5) {
                return Long.MAX_VALUE;
            }
            total += SCORE[len - 1][open];
        }
        return total;
    }

    private static boolean in(int size, int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }
}
