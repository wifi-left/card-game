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
    PASS(0, "wifi_card_games.ddz.card_type.pass"),
    SINGLE(1, "wifi_card_games.ddz.card_type.single"),
    PAIR(1, "wifi_card_games.ddz.card_type.pair"),
    TRIPLE(1, "wifi_card_games.ddz.card_type.triple"),
    TRIPLE_WITH_ONE(1, "wifi_card_games.ddz.card_type.triple_with_one"),
    TRIPLE_WITH_PAIR(1, "wifi_card_games.ddz.card_type.triple_with_pair"),
    STRAIGHT(1, "wifi_card_games.ddz.card_type.straight"),
    DOUBLE_STRAIGHT(1, "wifi_card_games.ddz.card_type.double_straight"),
    PLANE(1, "wifi_card_games.ddz.card_type.plane"),
    PLANE_WITH_SINGLES(1, "wifi_card_games.ddz.card_type.plane_with_singles"),
    PLANE_WITH_PAIRS(1, "wifi_card_games.ddz.card_type.plane_with_pairs"),
    FOUR_WITH_TWO_SINGLES(1, "wifi_card_games.ddz.card_type.four_with_two_singles"),
    FOUR_WITH_TWO_PAIRS(1, "wifi_card_games.ddz.card_type.four_with_two_pairs"),
    SOFT_BOMB(2, "wifi_card_games.ddz.card_type.soft_bomb"),
    BOMB(2, "wifi_card_games.ddz.card_type.bomb"),
    ROCKET(3, "wifi_card_games.ddz.card_type.rocket");

    private final int level;
    private final String displayNameKey;

    DdzCardType(int level, String displayNameKey) {
        this.level = level;
        this.displayNameKey = displayNameKey;
    }

    public int level() {
        return level;
    }

    public boolean isBombLike() {
        return level >= 2;
    }

    /** 牌型显示名翻译键（展示时经 Component.translatable 解析）。 */
    public String displayName() {
        return displayNameKey;
    }
}
