package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;

import java.util.List;

/**
 * 一手牌的识别结果：牌型 + 比较关键值（顺子取最大牌、炸弹取牌点、三带X取三张的牌点）。
 * 含花牌时同手牌可能有多个合法解读，压制判定采用"存在任一解读能压过"的语义。
 */
public final class DdzPlayResult {
    public final DdzCardType type;
    public final int key;
    public final List<DdzCard> cards;

    public DdzPlayResult(DdzCardType type, int key, List<DdzCard> cards) {
        this.type = type;
        this.key = key;
        this.cards = cards;
    }

    /** 本手牌能否压过 other（other 为 null 表示自由出牌，任何合法牌型都可出）。 */
    public boolean canBeat(DdzPlayResult other) {
        if (other == null) {
            return true;
        }
        int myLevel = this.type.level();
        int otherLevel = other.type.level();
        if (myLevel > otherLevel) {
            return true;
        }
        if (myLevel < otherLevel) {
            return false;
        }
        switch (myLevel) {
            case 2 -> {
                // 炸弹/软炸弹互压：比牌点
                return this.key > other.key;
            }
            case 3 -> {
                // 王炸 vs 王炸：等值不可压
                return false;
            }
            default -> {
                // 一般牌型：四带二不可互压
                if (this.type == DdzCardType.FOUR_WITH_TWO_SINGLES || this.type == DdzCardType.FOUR_WITH_TWO_PAIRS) {
                    return false;
                }
                return this.type == other.type
                        && this.cards.size() == other.cards.size()
                        && this.key > other.key;
            }
        }
    }

    @Override
    public String toString() {
        return type.displayName() + "(" + key + ")";
    }
}
