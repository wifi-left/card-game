package io.wifi.cards.doudizhu.card;

/**
 * 牌面等级。value 用于比较大小：3 < 4 < ... < 2 < 小王 < 大王。
 * 花牌（FLOWER）不参与大小比较（由替换算法决定其代表的牌值）。
 */
public enum DdzCardRank {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2"),
    SMALL_JOKER(16, "小王"),
    BIG_JOKER(17, "大王"),
    FLOWER(18, "花");

    private final int value;
    private final String symbol;

    DdzCardRank(int value, String symbol) {
        this.value = value;
        this.symbol = symbol;
    }

    public int value() {
        return value;
    }

    public String symbol() {
        return symbol;
    }
}
