package io.wifi.cards.uno.model;

/**
 * UNO 对局阶段。发牌瞬间完成（服务端直接进入 PLAYING），无需单独的 DEALING 阶段。
 * WAITING → PLAYING → SETTLED；SETTLED 后"再来一局"回到 PLAYING。
 */
public enum UnoGamePhase {
    WAITING,
    PLAYING,
    SETTLED
}
