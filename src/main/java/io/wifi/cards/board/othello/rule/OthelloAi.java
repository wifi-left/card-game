package io.wifi.cards.board.othello.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 黑白棋 AI（基础贪心 + 位置权重）：优先角落、其次边，回避角旁危险位；
 * 同分随机打破平局。作为调试假人/托管/超时自动落子用。
 */
public final class OthelloAi {
    private static final Random RANDOM = new Random();

    /** 位置权重（按 y 行、x 列）：角落最高、边次之、角旁扣分。 */
    private static final int[][] WEIGHT = {
            {100, -30, 10, 5, 5, 10, -30, 100},
            {-30, -50, 2, 2, 2, 2, -50, -30},
            {10, 2, 1, 1, 1, 1, 2, 10},
            {5, 2, 1, 1, 1, 1, 2, 5},
            {5, 2, 1, 1, 1, 1, 2, 5},
            {10, 2, 1, 1, 1, 1, 2, 10},
            {-30, -50, 2, 2, 2, 2, -50, -30},
            {100, -30, 10, 5, 5, 10, -30, 100}
    };

    private OthelloAi() {
    }

    /**
     * 为 player 选一步；无合法着返回 null。
     * 评分 = 落子后本方子数 × 10 + 位置权重（角/边/角旁），取最高分。
     */
    public static int[] findMove(byte[] board, byte player) {
        List<int[]> moves = OthelloRules.legalMoves(board, player);
        if (moves.isEmpty()) {
            return null;
        }
        int bestScore = Integer.MIN_VALUE;
        List<int[]> best = new ArrayList<>();
        for (int[] m : moves) {
            byte[] copy = board.clone();
            OthelloRules.applyMove(copy, m[0], m[1], player);
            int score = OthelloRules.count(copy, player) * 10 + WEIGHT[m[1]][m[0]];
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(m);
            } else if (score == bestScore) {
                best.add(m);
            }
        }
        return best.get(RANDOM.nextInt(best.size()));
    }
}
