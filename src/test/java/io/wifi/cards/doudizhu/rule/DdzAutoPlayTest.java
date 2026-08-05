package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 托管出牌策略测试（默认 经典模式 + 标准规则）。 */
class DdzAutoPlayTest {

    private static List<DdzCard> c(int... ids) {
        List<DdzCard> list = new ArrayList<>();
        for (int id : ids) {
            list.add(DdzCard.byId(id));
        }
        return list;
    }

    private static DdzPlayResult target(int... ids) {
        List<DdzCard> cards = c(ids);
        return DdzCardTypeRecognizer.recognize(cards).get(0);
    }

    private static List<Integer> idsOf(List<DdzCard> cards) {
        return cards.stream().map(DdzCard::id).toList();
    }

    private static List<DdzCard> find(List<DdzCard> hand, DdzPlayResult target) {
        return DdzAutoPlay.findPlay(hand, target, false, DdzRuleSet.STANDARD);
    }

    @Test
    void freePlayPlaysSmallestSingle() {
        List<DdzCard> play = find(c(8, 0, 4), null); // 5,3,4
        assertNotNull(play);
        assertEquals(1, play.size());
        assertEquals(3, play.get(0).rankValue());
    }

    @Test
    void beatSingle() {
        // 手牌 {3,6,9} 压 单5 → 出最小的 6
        List<DdzCard> play = find(c(0, 12, 20), target(4));
        assertNotNull(play);
        assertEquals(1, play.size());
        assertEquals(6, play.get(0).rankValue());
    }

    @Test
    void sameTypeBeforeBomb() {
        // 手牌 {3, 8×4}，目标 单4：先同型压制（拆单 8），不浪费炸弹
        List<DdzCard> play = find(c(0, 20, 21, 22, 23), target(4));
        assertNotNull(play);
        assertEquals(1, play.size());
        assertEquals(8, play.get(0).rankValue());
    }

    @Test
    void bombAsLastResort() {
        // 手牌 {3, 8×4}，目标 单2：同型压不住（3/8 < 2），炸弹兜底
        List<DdzCard> play = find(c(0, 20, 21, 22, 23), target(52));
        assertNotNull(play);
        assertEquals(4, play.size());
        assertEquals(8, play.get(0).rankValue());
    }

    @Test
    void freePlayPrefersStraight() {
        // 手牌 {3,4,5,6,7,9}：自由出牌优先出 5 张顺子
        List<DdzCard> play = find(c(0, 4, 8, 12, 16, 32), null);
        assertNotNull(play);
        assertEquals(5, play.size());
        assertEquals(3, play.get(0).rankValue());
    }

    @Test
    void freePlayPrefersPairOverSplitting() {
        // 手牌 {3,3,4}：自由出牌先出对 3，不拆对出单牌
        List<DdzCard> play = find(c(0, 1, 4), null);
        assertNotNull(play);
        assertEquals(2, play.size());
        assertEquals(3, play.get(0).rankValue());
    }

    @Test
    void freePlayUsesSingleOnly() {
        // 手牌 {3,5}：无整牌型，出最小孤牌 3
        List<DdzCard> play = find(c(0, 8), null);
        assertNotNull(play);
        assertEquals(1, play.size());
        assertEquals(3, play.get(0).rankValue());
    }

    @Test
    void noPlayReturnsNull() {
        assertNull(find(c(0, 4), target(12))); // 3,4 压不过 6
    }

    @Test
    void straightBeats() {
        // 手牌 3~8，目标 34567 → 出 45678
        List<DdzCard> play = find(c(0, 4, 8, 12, 16, 20), target(0, 4, 8, 12, 16));
        assertNotNull(play);
        assertEquals(5, play.size());
        assertEquals(8, DdzCardTypeRecognizer.recognize(play).get(0).key);
    }

    @Test
    void pairBeats() {
        // 等值不可压
        assertNull(find(c(0, 1), target(0, 1)));
        // 44 > 33
        List<DdzCard> play = find(c(4, 5, 8, 9), target(0, 1));
        assertNotNull(play);
        assertEquals(4, play.get(0).rankValue());
    }

    @Test
    void rocketBeatsBomb() {
        List<DdzCard> play = find(c(52, 53), target(20, 21, 22, 23)); // 王炸压 8 炸
        assertNotNull(play);
        assertTrue(idsOf(play).contains(52) && idsOf(play).contains(53));
    }

    @Test
    void cannotBeatRocket() {
        assertNull(find(c(20, 21, 22, 23), target(52, 53))); // 炸弹压不过王炸
    }

    @Test
    void flowerAssistsPair() {
        // 手牌 F + 9，目标 对8 → 花牌补成对9
        List<DdzCard> play = find(c(54, 24), target(20, 21));
        assertNotNull(play);
        assertEquals(2, play.size());
        assertEquals(9, DdzCardTypeRecognizer.recognize(play).get(0).key);
    }

    @Test
    void flowerAssistsStraight() {
        // 手牌 F + 4,5,6,7，目标 34567 → F 补 8 成 45678
        List<DdzCard> play = find(c(54, 4, 8, 12, 16), target(0, 4, 8, 12, 16));
        assertNotNull(play);
        DdzPlayResult r = DdzCardTypeRecognizer.recognize(play).get(0);
        assertEquals(DdzCardType.STRAIGHT, r.type);
        assertEquals(8, r.key);
    }

    @Test
    void tripleWithPairBeatsInStandardMode() {
        // 经典 + 标准：44455 可压 33344（三带二）
        DdzPlayResult target = target(0, 1, 2, 4, 5); // 33344 → 三带二(3)
        List<DdzCard> play = find(c(4, 5, 6, 8, 9), target); // 44455 → 三带二(4)
        assertNotNull(play);
        assertEquals(DdzCardType.TRIPLE_WITH_PAIR, DdzCardTypeRecognizer.recognize(play).get(0).type);
    }

    @Test
    void tripleWithPairDisallowedInFlowerOrFolk() {
        DdzPlayResult target = target(0, 1, 2, 4, 5); // 33344 → 三带二(3)
        List<DdzCard> hand = c(4, 5, 6, 8, 9);       // 44455（只能作三带二）
        // 花牌模式：禁三带二 → 无牌可出
        assertNull(DdzAutoPlay.findPlay(hand, target, true, DdzRuleSet.STANDARD));
        // 民间规则：禁三带二 → 无牌可出
        assertNull(DdzAutoPlay.findPlay(hand, target, false, DdzRuleSet.FOLK));
    }

    @Test
    void planeWithSinglesNoWingsNoCrash() {
        // 手牌仅 333444（无翅膀可带），目标 55566678（飞机带两单）：
        // 翅膀不足 → 返回 null（不得 NPE，回归 concat(null) 崩溃）
        DdzPlayResult target = target(8, 9, 10, 12, 13, 14, 16, 20); // 55566678
        assertNull(find(c(0, 1, 2, 4, 5, 6), target));
    }

    @Test
    void planeWithPairsNoWingsNoCrash() {
        // 手牌仅 333444（无对子翅膀），目标 3334445566（飞机带对子）：
        // 翅膀不足 → 返回 null（不得 NPE，回归 concat(null) 崩溃）
        DdzPlayResult target = target(0, 1, 2, 4, 5, 6, 8, 9, 12, 13); // 3334445566
        assertNull(find(c(0, 1, 2, 4, 5, 6), target));
    }
}
