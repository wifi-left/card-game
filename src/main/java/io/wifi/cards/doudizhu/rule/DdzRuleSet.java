package io.wifi.cards.doudizhu.rule;

/**
 * 牌型规则集（创建房间时由房主选择）：
 * <ul>
 *   <li>STANDARD 标准规则（平台打法）：三带二、飞机带对子、四带两对均允许</li>
 *   <li>FOLK 民间规则：无三带二、无飞机带对子、无四带两对（四带二仅限"四张 + 两张单牌"）</li>
 * </ul>
 * 另：花牌（万能牌）模式恒不允许三带二。
 */
public enum DdzRuleSet {
    STANDARD("标准规则"),
    FOLK("民间规则");

    private final String displayName;

    DdzRuleSet(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 该规则集下是否允许某牌型（flowerMode=是否花牌模式）。 */
    public boolean allows(DdzCardType type, boolean flowerMode) {
        if (type == DdzCardType.TRIPLE_WITH_PAIR) {
            // 花牌模式恒禁三带二；民间规则也禁
            return !flowerMode && this == STANDARD;
        }
        if (type == DdzCardType.PLANE_WITH_PAIRS || type == DdzCardType.FOUR_WITH_TWO_PAIRS) {
            // 民间规则无飞机带对子、四带两对
            return this == STANDARD;
        }
        return true;
    }
}
