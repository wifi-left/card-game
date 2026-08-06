package io.wifi.cards.board.model;

/**
 * 棋类房间阶段：等待中 → 对局中 → 已结束。
 */
public enum BoardPhase {
    WAITING,
    PLAYING,
    SETTLED
}
