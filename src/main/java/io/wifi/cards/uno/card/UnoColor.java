package io.wifi.cards.uno.card;

/**
 * UNO 颜色。NONE 用于万能牌（尚未选择颜色）。
 * ordinal 即网络传输值：0 红 / 1 黄 / 2 绿 / 3 蓝 / 4 无（选色时只允许 0~3）。
 */
public enum UnoColor {
    RED("wifi_card_games.uno.color.red"),
    YELLOW("wifi_card_games.uno.color.yellow"),
    GREEN("wifi_card_games.uno.color.green"),
    BLUE("wifi_card_games.uno.color.blue"),
    NONE("");

    private final String symbolKey;

    UnoColor(String symbolKey) {
        this.symbolKey = symbolKey;
    }

    /** 牌面左上角颜色字翻译键（万能牌为空）。 */
    public String symbol() {
        return symbolKey;
    }

    /** 是否为四色之一（可用于选色）。 */
    public boolean isColored() {
        return this != NONE;
    }

    /** 选色显示名翻译键（万能牌选色弹层）。 */
    public String displayName() {
        return symbolKey;
    }

    /** 卡片翻译键短名（wifi_card_games.uno.card.<color>_<value> 用）。 */
    public String shortKey() {
        return switch (this) {
            case RED -> "r";
            case YELLOW -> "y";
            case GREEN -> "g";
            case BLUE -> "b";
            default -> "";
        };
    }
}
