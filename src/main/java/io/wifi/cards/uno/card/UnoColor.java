package io.wifi.cards.uno.card;

/**
 * UNO 颜色。NONE 用于万能牌（尚未选择颜色）。
 * ordinal 即网络传输值：0 红 / 1 黄 / 2 绿 / 3 蓝 / 4 无（选色时只允许 0~3）。
 */
public enum UnoColor {
    RED("红"),
    YELLOW("黄"),
    GREEN("绿"),
    BLUE("蓝"),
    NONE("");

    private final String symbol;

    UnoColor(String symbol) {
        this.symbol = symbol;
    }

    /** 牌面左上角颜色字（万能牌为空）。 */
    public String symbol() {
        return symbol;
    }

    /** 是否为四色之一（可用于选色）。 */
    public boolean isColored() {
        return this != NONE;
    }

    /** 选色显示名（万能牌选色弹层）。 */
    public String displayName() {
        return switch (this) {
            case RED -> "红";
            case YELLOW -> "黄";
            case GREEN -> "绿";
            case BLUE -> "蓝";
            default -> "";
        };
    }
}
