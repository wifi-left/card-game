package io.wifi.cards.board.othello.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 黑白棋 AI（基础贪心 + 位置权重）：优先角落、其次边，回避角旁危险位；
 * 同分随机打破平局。作为调试假人/托管/超时自动落子用。
 * <p>权重按位置分类生成（任意尺寸适用）：角 100、斜角旁 -50、正角旁 -30、
 * 边 10（沿边距端 ≥3 处降为 5）、内部距边 1 为 2、更深处为 1。
 * 与旧 8×8 手写表逐格一致。</p>
 */
public final class OthelloAi {
    private static final Random RANDOM = new Random();

    private OthelloAi() {
    }

    /**
     * 为 player 选一步；无合法着返回 null。
     * 评分 = 落子后本方子数 × 10 + 位置权重（角/边/角旁），取最高分。
     */
    public static int[] findMove(byte[] board, int size, byte player) {
        List<int[]> moves = OthelloRules.legalMoves(board, size, player);
        if (moves.isEmpty()) {
            return null;
        }
        int bestScore = Integer.MIN_VALUE;
        List<int[]> best = new ArrayList<>();
        for (int[] m : moves) {
            byte[] copy = board.clone();
            OthelloRules.applyMove(copy, size, m[0], m[1], player);
            int score = OthelloRules.count(copy, player) * 10 + weight(m[0], m[1], size);
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

    /** 位置权重（按 x 列、y 行计算，棋盘任意偶数尺寸）。 */
    private static int weight(int x, int y, int size) {
        int last = size - 1;
        boolean xEdge = x == 0 || x == last;
        boolean yEdge = y == 0 || y == last;
        if (xEdge && yEdge) {
            return 100; // 角落
        }
        if (xEdge && (y == 1 || y == last - 1)) {
            return -30; // 正角旁（角的正边上）
        }
        if (yEdge && (x == 1 || x == last - 1)) {
            return -30;
        }
        if ((x == 1 || x == last - 1) && (y == 1 || y == last - 1)) {
            return -50; // 斜角旁（角的斜对角）
        }
        if (xEdge || yEdge) {
            // 边（非角旁）：离端点 2 格处价值高（可进角），更远降为 5
            int toEnd = xEdge ? Math.min(x, last - x) : Math.min(y, last - y);
            return toEnd == 2 ? 10 : 5;
        }
        int dist = Math.min(Math.min(x, last - x), Math.min(y, last - y));
        return dist == 1 ? 2 : 1; // 距边 1 格次优，中心最弱
    }
}
