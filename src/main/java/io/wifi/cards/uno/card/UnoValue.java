package io.wifi.cards.uno.card;

/**
 * UNO 牌面值：数字 0~9、功能牌（跳过/反转/+2）、万能牌（万能/万能+4）。
 * ordinal 即网络传输值。
 */
public enum UnoValue {
    ZERO("0"),
    ONE("1"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    SKIP("跳过"),
    REVERSE("反转"),
    DRAW2("+2"),
    WILD("万能"),
    WILD4("+4");

    private final String name;

    UnoValue(String name) {
        this.name = name;
    }

    /** 牌面中央显示文字。 */
    public String displayName() {
        return name;
    }

    /** 是否为数字牌（0~9）。 */
    public boolean isNumber() {
        return this.ordinal() <= NINE.ordinal();
    }

    /** 是否为万能牌（万能 / 万能+4）。 */
    public boolean isWild() {
        return this == WILD || this == WILD4;
    }

    /** 是否为功能牌（跳过/反转/+2）。 */
    public boolean isAction() {
        return this == SKIP || this == REVERSE || this == DRAW2;
    }
}
