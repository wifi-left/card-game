package io.wifi.cards.doudizhu.rule;

/**
 * 牌型。level 表示压制等级：
 * <ul>
 *   <li>3 王炸 &gt; 2 炸弹/含花牌炸弹 &gt; 1 一般牌型</li>
 *   <li>同等级内：炸弹类按牌点比较；一般牌型需牌型、张数相同且关键值更大</li>
 * </ul>
 * <p>不存在"三王炸"（花牌 + 大小王不构成合法牌型）。是否允许三带二 / 飞机带对子 / 四带两对
 * 由房间规则集（标准/民间）与花牌模式决定，见 {@link DdzRuleSet#allows}。</p>
 */
public enum DdzCardType {
    PASS(0, "不出"),
    SINGLE(1, "单张"),
    PAIR(1, "对子"),
    TRIPLE(1, "三张"),
    TRIPLE_WITH_ONE(1, "三带一"),
    TRIPLE_WITH_PAIR(1, "三带二"),
    STRAIGHT(1, "顺子"),
    DOUBLE_STRAIGHT(1, "连对"),
    PLANE(1, "飞机"),
    PLANE_WITH_SINGLES(1, "飞机带翅膀"),
    PLANE_WITH_PAIRS(1, "飞机带对"),
    FOUR_WITH_TWO_SINGLES(1, "四带二"),
    FOUR_WITH_TWO_PAIRS(1, "四带两对"),
    SOFT_BOMB(2, "含花牌炸弹"),
    BOMB(2, "炸弹"),
    ROCKET(3, "王炸");

    private final int level;
    private final String displayName;

    DdzCardType(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int level() {
        return level;
    }

    public boolean isBombLike() {
        return level >= 2;
    }

    public String displayName() {
        return displayName;
    }
}
