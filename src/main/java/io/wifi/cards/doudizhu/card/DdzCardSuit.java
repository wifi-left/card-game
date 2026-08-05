package io.wifi.cards.doudizhu.card;

/** 花色（大小王与花牌无花色）。 */
public enum DdzCardSuit {
    SPADE("♠"),
    HEART("♥"),
    CLUB("♣"),
    DIAMOND("♦"),
    NONE("");

    private final String symbol;

    DdzCardSuit(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
