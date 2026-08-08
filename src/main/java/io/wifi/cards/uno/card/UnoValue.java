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
    SKIP("wifi_card_games.uno.value.skip"),
    REVERSE("wifi_card_games.uno.value.reverse"),
    DRAW2("+2"),
    WILD("wifi_card_games.uno.value.wild"),
    WILD4("wifi_card_games.uno.value.wild4");

    private final String nameKey;

    UnoValue(String nameKey) {
        this.nameKey = nameKey;
    }

    /** 牌面中央显示文字翻译键（数字为字面，展示时经 Component.translatable 解析）。 */
    public String displayName() {
        return nameKey;
    }

    /** 卡片翻译键短名（wifi_card_games.uno.card.<color>_<value> 用）。 */
    public String shortKey() {
        return switch (this) {
            case ZERO -> "0";
            case ONE -> "1";
            case TWO -> "2";
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case SKIP -> "skip";
            case REVERSE -> "reverse";
            case DRAW2 -> "draw2";
            case WILD -> "wild";
            case WILD4 -> "wild4";
        };
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
