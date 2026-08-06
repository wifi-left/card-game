package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 牌型识别引擎测试（纯 Java，不依赖 Minecraft 运行时）。
 * id 约定：0~51 常规牌（每 4 张一组对应一个牌值 3~2），52 小王，53 大王，54 花牌。
 */
class DdzCardTypeRecognizerTest {

    private static List<DdzCard> c(int... ids) {
        List<DdzCard> list = new ArrayList<>();
        for (int id : ids) {
            list.add(DdzCard.byId(id));
        }
        return list;
    }

    /** 直接用牌对象构造（r() 返回的牌）。 */
    private static List<DdzCard> cards(DdzCard... cs) {
        return new ArrayList<>(java.util.Arrays.asList(cs));
    }

    /** 牌值 v（3~17）对应的一张牌；16/17 返回小王/大王。 */
    private static DdzCard r(int v) {
        if (v == 16) {
            return DdzCard.smallJoker();
        }
        if (v == 17) {
            return DdzCard.bigJoker();
        }
        return DdzCard.byId((v - 3) * 4);
    }

    private static DdzPlayResult recognize(List<DdzCard> cards) {
        List<DdzPlayResult> results = DdzCardTypeRecognizer.recognize(cards);
        return results.isEmpty() ? null : results.get(0);
    }

    // ---------- 经典牌型 ----------

    @Test
    void rocket() {
        DdzPlayResult p = recognize(c(52, 53));
        assertNotNull(p);
        assertEquals(DdzCardType.ROCKET, p.type);
    }

    @Test
    void bomb() {
        DdzPlayResult p = recognize(c(20, 21, 22, 23)); // 4×8
        assertNotNull(p);
        assertEquals(DdzCardType.BOMB, p.type);
        assertEquals(8, p.key);
    }

    @Test
    void singlePairTriple() {
        assertEquals(DdzCardType.SINGLE, recognize(c(0)).type);
        assertEquals(DdzCardType.PAIR, recognize(c(4, 5)).type);
        assertEquals(DdzCardType.TRIPLE, recognize(c(8, 9, 10)).type);
        assertEquals(5, recognize(c(8, 9, 10)).key);
    }

    @Test
    void tripleWithOne() {
        DdzPlayResult p = recognize(c(8, 9, 10, 0)); // 5553
        assertNotNull(p);
        assertEquals(DdzCardType.TRIPLE_WITH_ONE, p.type);
        assertEquals(5, p.key);
    }

    @Test
    void tripleWithPair() {
        DdzPlayResult p = recognize(c(8, 9, 10, 0, 1)); // 55533
        assertNotNull(p);
        assertEquals(DdzCardType.TRIPLE_WITH_PAIR, p.type);
        assertEquals(5, p.key);
    }

    @Test
    void straight() {
        DdzPlayResult p = recognize(c(0, 4, 8, 12, 16)); // 34567
        assertNotNull(p);
        assertEquals(DdzCardType.STRAIGHT, p.type);
        assertEquals(7, p.key);
        // 10JQKA 合法（key 14）
        DdzPlayResult p2 = recognize(c(30, 32, 36, 40, 44));
        assertNotNull(p2);
        assertEquals(14, p2.key);
    }

    @Test
    void straightInvalid() {
        assertNull(recognize(c(48, 32, 36, 40, 44))); // 2JQKA（2 不能进顺子）
        assertNull(recognize(c(0, 4, 8, 12)));         // 3456（不足 5 张）
        assertNull(recognize(c(0, 4, 8, 12, 20)));     // 34568（断张）
        assertNull(recognize(c(0, 4, 8, 12, 13)));     // 34566（重复）
    }

    @Test
    void doubleStraight() {
        DdzPlayResult p = recognize(c(0, 1, 4, 5, 8, 9)); // 334455
        assertNotNull(p);
        assertEquals(DdzCardType.DOUBLE_STRAIGHT, p.type);
        assertEquals(5, p.key);
        assertNull(recognize(c(0, 1, 4, 5)));          // 3344（不足 3 对）
    }

    @Test
    void plane() {
        DdzPlayResult p = recognize(c(0, 1, 2, 4, 5, 6)); // 333444
        assertNotNull(p);
        assertEquals(DdzCardType.PLANE, p.type);
        assertEquals(4, p.key);
    }

    @Test
    void planeWithSingles() {
        DdzPlayResult p = recognize(c(0, 1, 2, 4, 5, 6, 8, 12)); // 33344456
        assertNotNull(p);
        assertEquals(DdzCardType.PLANE_WITH_SINGLES, p.type);
        assertEquals(4, p.key);
    }

    @Test
    void planeWithPairs() {
        DdzPlayResult p = recognize(c(0, 1, 2, 4, 5, 6, 8, 9, 12, 13)); // 3334445566
        assertNotNull(p);
        assertEquals(DdzCardType.PLANE_WITH_PAIRS, p.type);
        assertEquals(4, p.key);
    }

    @Test
    void fourWithTwo() {
        DdzPlayResult p1 = recognize(c(20, 21, 22, 23, 0, 4)); // 888834
        assertNotNull(p1);
        assertEquals(DdzCardType.FOUR_WITH_TWO_SINGLES, p1.type);
        assertEquals(8, p1.key);
        // 888833（6 张，四带一对）：不是四带两对（带牌必须恰好两对），非法
        assertNull(recognize(c(20, 21, 22, 23, 0, 1)));
        DdzPlayResult p3 = recognize(c(20, 21, 22, 23, 0, 1, 4, 5)); // 88883344
        assertNotNull(p3);
        assertEquals(DdzCardType.FOUR_WITH_TWO_PAIRS, p3.type);
    }

    @Test
    void invalidPlays() {
        assertNull(recognize(c(0, 4, 8)));          // 345 顺子不足
        assertNull(recognize(c(0, 4, 8, 12, 13)));  // 34566
        assertNull(recognize(c(0, 4, 8, 12)));      // 3456（四张不同值）
        assertNull(recognize(c()));
    }

    // ---------- 大小比较 ----------

    @Test
    void compareBasic() {
        assertTrue(recognize(c(4, 8, 12, 16, 20)).canBeat(recognize(c(0, 4, 8, 12, 16)))); // 45678 > 34567
        assertFalse(recognize(c(0, 4, 8, 12, 16)).canBeat(recognize(c(0, 4, 8, 12, 16)))); // 等值不可压
        assertTrue(recognize(c(20, 21, 22, 23)).canBeat(recognize(c(0, 4, 8, 12, 16))));   // 炸弹压顺子
        assertFalse(recognize(c(4, 8, 12, 16, 20)).canBeat(recognize(c(0, 4, 8, 12, 16, 20)))); // 顺子长度不同
        assertFalse(recognize(c(0, 1, 2)).canBeat(recognize(c(0, 1, 2))));                 // 333 vs 333 等值不可压
        assertTrue(recognize(c(4, 5, 6)).canBeat(recognize(c(0, 1, 2))));                  // 444 > 333
        assertFalse(recognize(c(0, 1, 2, 4)).canBeat(recognize(c(4, 5, 6))));             // 三带一 vs 三张（牌型不同）
    }

    @Test
    void compareBombs() {
        assertTrue(recognize(c(20, 21, 22, 23)).canBeat(recognize(c(0, 1, 2, 3)))); // 8 炸 > 3 炸
        assertFalse(recognize(c(20, 21, 22, 23)).canBeat(recognize(c(52, 53))));   // 炸弹 < 王炸
        assertTrue(recognize(c(52, 53)).canBeat(recognize(c(20, 21, 22, 23))));    // 王炸 > 炸弹
    }

    @Test
    void fourWithTwoCannotBeatEachOther() {
        DdzPlayResult a = recognize(c(20, 21, 22, 23, 0, 4));
        DdzPlayResult b = recognize(c(4, 5, 6, 7, 12, 16));
        assertNotNull(a);
        assertNotNull(b);
        assertFalse(a.canBeat(b));
        assertFalse(b.canBeat(a));
        assertTrue(recognize(c(20, 21, 22, 23)).canBeat(a)); // 炸弹可压四带二
    }

    // ---------- 花牌（万能牌） ----------

    @Test
    void flowerWithBothJokersIsInvalid() {
        // 不存在三王炸：花牌 + 大小王无法组成任何合法牌型
        assertNull(recognize(c(54, 52, 53)));
    }

    @Test
    void flowerSoftBomb() {
        DdzPlayResult p = recognize(c(54, 20, 21, 22)); // F+888
        assertNotNull(p);
        assertEquals(DdzCardType.SOFT_BOMB, p.type);
        assertEquals(8, p.key);
        // 含花牌炸弹可压一般牌型与更小的炸弹，等于普通炸弹
        assertTrue(p.canBeat(recognize(c(0, 1, 2, 4, 5)))); // 压三带二
        assertTrue(p.canBeat(recognize(c(0, 1, 2, 3))));    // 压 3 炸
        assertFalse(p.canBeat(recognize(c(20, 21, 22, 23)))); // 同值 8 炸不可压
        assertNull(DdzCardTypeRecognizer.bestAgainst(c(54, 20, 21, 22), recognize(c(24, 25, 26, 27)))); // 压不过 9 炸
    }

    @Test
    void flowerTriple() {
        DdzPlayResult p = recognize(c(54, 0, 1)); // F+33 → 333
        assertNotNull(p);
        assertEquals(DdzCardType.TRIPLE, p.type);
        assertEquals(3, p.key);
    }

    @Test
    void flowerStraightSubstitution() {
        // F+4567：可解读为 34567（F=3）或 45678（F=8）
        List<DdzCard> cards = c(54, 4, 8, 12, 16);
        List<DdzPlayResult> results = DdzCardTypeRecognizer.recognize(cards);
        assertFalse(results.isEmpty());
        boolean hasKey7 = false;
        boolean hasKey8 = false;
        for (DdzPlayResult r : results) {
            if (r.type == DdzCardType.STRAIGHT && r.key == 7) {
                hasKey7 = true;
            }
            if (r.type == DdzCardType.STRAIGHT && r.key == 8) {
                hasKey8 = true;
            }
        }
        assertTrue(hasKey7, "应可解读为 34567");
        assertTrue(hasKey8, "应可解读为 45678");
        // 压 34567 时应取 45678
        DdzPlayResult target = recognize(c(0, 4, 8, 12, 16));
        DdzPlayResult best = DdzCardTypeRecognizer.bestAgainst(cards, target);
        assertNotNull(best);
        assertEquals(DdzCardType.STRAIGHT, best.type);
        assertEquals(8, best.key);
    }

    @Test
    void flowerSingle() {
        // 不允许出单张花牌：花牌必须与其他牌组合，单独打出无任何合法解读
        assertTrue(DdzCardTypeRecognizer.recognize(c(54)).isEmpty());
        // 组合使用仍正常：花牌 + 一对 4 = 三张 4
        assertEquals(DdzCardType.TRIPLE, recognize(c(54, 4, 5)).type);
    }

    @Test
    void flowerWithJoker() {
        // F+小王：可解读为王炸（F=大王）或对王（F=小王）
        List<DdzPlayResult> results = DdzCardTypeRecognizer.recognize(c(54, 52));
        assertNotNull(results.get(0));
        assertEquals(DdzCardType.ROCKET, results.get(0).type); // 优先级：王炸 > 对王
        assertTrue(results.get(0).canBeat(recognize(c(20, 21, 22, 23))));
    }

    @Test
    void flowerBeatsAnyNonBomb() {
        // F+888 压 三带一(6)：含花牌炸弹解读直接压
        DdzPlayResult target = recognize(c(12, 13, 14, 8));
        assertNotNull(target);
        assertEquals(DdzCardType.TRIPLE_WITH_ONE, target.type);
        DdzPlayResult best = DdzCardTypeRecognizer.bestAgainst(c(54, 20, 21, 22), target);
        assertNotNull(best);
        assertEquals(DdzCardType.SOFT_BOMB, best.type);
    }

    @Test
    void flowerCannotBeatRocket() {
        assertNull(DdzCardTypeRecognizer.bestAgainst(c(54, 20, 21, 22), recognize(c(52, 53))));
    }

    @Test
    void flowerFreePlayPicksHighestPriority() {
        // 自由出牌 F+888 → 含花牌炸弹（优先级最高的解读）
        DdzPlayResult p = DdzCardTypeRecognizer.bestAgainst(c(54, 20, 21, 22), null);
        assertNotNull(p);
        assertEquals(DdzCardType.SOFT_BOMB, p.type);
    }

    // ---------- 规则集过滤（标准/民间 + 花牌模式） ----------

    @Test
    void ruleSetFiltersDisallowedTypes() {
        // 三带二 33344：标准允许；民间禁用；花牌模式禁用
        List<DdzCard> twp = c(0, 1, 2, 4, 5);
        assertEquals(DdzCardType.TRIPLE_WITH_PAIR, recognize(twp).type);
        assertFalse(DdzCardTypeRecognizer.recognize(twp, false, DdzRuleSet.STANDARD).isEmpty());
        assertTrue(DdzCardTypeRecognizer.recognize(twp, false, DdzRuleSet.FOLK).isEmpty());
        assertTrue(DdzCardTypeRecognizer.recognize(twp, true, DdzRuleSet.STANDARD).isEmpty());

        // 飞机带对 3334445566：标准允许（含花牌模式）；民间禁用
        List<DdzCard> pwp = c(0, 1, 2, 4, 5, 6, 8, 9, 12, 13);
        assertEquals(DdzCardType.PLANE_WITH_PAIRS, recognize(pwp).type);
        assertFalse(DdzCardTypeRecognizer.recognize(pwp, true, DdzRuleSet.STANDARD).isEmpty());
        assertTrue(DdzCardTypeRecognizer.recognize(pwp, false, DdzRuleSet.FOLK).isEmpty());

        // 四带两对 88883344：标准允许；民间禁用
        List<DdzCard> fw2p = c(20, 21, 22, 23, 0, 1, 4, 5);
        assertEquals(DdzCardType.FOUR_WITH_TWO_PAIRS, recognize(fw2p).type);
        assertTrue(DdzCardTypeRecognizer.recognize(fw2p, false, DdzRuleSet.FOLK).isEmpty());

        // 四带二（两单）888834：所有规则都允许
        List<DdzCard> fw2s = c(20, 21, 22, 23, 0, 4);
        assertEquals(DdzCardType.FOUR_WITH_TWO_SINGLES, recognize(fw2s).type);
        assertFalse(DdzCardTypeRecognizer.recognize(fw2s, true, DdzRuleSet.FOLK).isEmpty());

        // 裸飞机 333444：所有规则都允许
        List<DdzCard> plane = c(0, 1, 2, 4, 5, 6);
        assertEquals(DdzCardType.PLANE, recognize(plane).type);
        assertFalse(DdzCardTypeRecognizer.recognize(plane, true, DdzRuleSet.FOLK).isEmpty());

        // 飞机带单 33344456：所有规则都允许
        List<DdzCard> pws = c(0, 1, 2, 4, 5, 6, 8, 12);
        assertEquals(DdzCardType.PLANE_WITH_SINGLES, recognize(pws).type);
        assertFalse(DdzCardTypeRecognizer.recognize(pws, true, DdzRuleSet.FOLK).isEmpty());
    }

    // ---------- 连对 / 飞机（用户反馈：667788、66778899、666777888 等） ----------

    @Test
    void doubleStraightThreePairs() {
        DdzPlayResult p = recognize(cards(
                r(6), r(6), r(7), r(7), r(8), r(8))); // 667788
        assertNotNull(p);
        assertEquals(DdzCardType.DOUBLE_STRAIGHT, p.type);
        assertEquals(8, p.key);
    }

    @Test
    void doubleStraightFourPairs() {
        DdzPlayResult p = recognize(cards(
                r(6), r(6), r(7), r(7), r(8), r(8), r(9), r(9))); // 66778899
        assertNotNull(p);
        assertEquals(DdzCardType.DOUBLE_STRAIGHT, p.type);
        assertEquals(9, p.key);
    }

    @Test
    void doubleStraightFivePairs() {
        DdzPlayResult p = recognize(cards(
                r(6), r(6), r(7), r(7), r(8), r(8), r(9), r(9), r(10), r(10))); // 66778899TT
        assertNotNull(p);
        assertEquals(DdzCardType.DOUBLE_STRAIGHT, p.type);
        assertEquals(10, p.key);
    }

    @Test
    void planeBareThreeGroups() {
        DdzPlayResult p = recognize(cards(
                r(6), r(6), r(6), r(7), r(7), r(7), r(8), r(8), r(8))); // 666777888
        assertNotNull(p);
        assertEquals(DdzCardType.PLANE, p.type);
        assertEquals(8, p.key);
    }

    @Test
    void planeBareFourGroups() {
        DdzPlayResult p = recognize(cards(
                r(6), r(6), r(6), r(7), r(7), r(7), r(8), r(8), r(8), r(9), r(9), r(9))); // 666777888999
        assertNotNull(p);
        assertEquals(DdzCardType.PLANE, p.type);
        assertEquals(9, p.key);
    }

    // ---------- 连对 / 飞机压制（canBeat） ----------

    @Test
    void doubleStraightBeats() {
        // 667788 压 556677
        DdzPlayResult a = recognize(cards(r(6), r(6), r(7), r(7), r(8), r(8)));
        DdzPlayResult b = recognize(cards(r(5), r(5), r(6), r(6), r(7), r(7)));
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.canBeat(b));
        assertFalse(b.canBeat(a));
    }

    @Test
    void planeBeats() {
        // 666777888 压 555666777
        DdzPlayResult a = recognize(cards(r(6), r(6), r(6), r(7), r(7), r(7), r(8), r(8), r(8)));
        DdzPlayResult b = recognize(cards(r(5), r(5), r(5), r(6), r(6), r(6), r(7), r(7), r(7)));
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.canBeat(b));
        assertFalse(b.canBeat(a));
    }

    @Test
    void doubleStraightLengthMismatch() {
        // 66778899（4 对）不能压 556677（3 对）：张数不同
        DdzPlayResult a = recognize(cards(r(6), r(6), r(7), r(7), r(8), r(8), r(9), r(9)));
        DdzPlayResult b = recognize(cards(r(5), r(5), r(6), r(6), r(7), r(7)));
        assertNotNull(a);
        assertNotNull(b);
        assertFalse(a.canBeat(b));
        assertFalse(b.canBeat(a));
    }

    @Test
    void tripleWithTwoSinglesIsNotValid() {
        // JJJ45（三张 J + 4 + 5，3+1+1 共 5 张）：不是三带一（三带一只能带 1 张单牌）
        assertNull(recognize(cards(r(11), r(11), r(11), r(4), r(5))));
        // 4+1（5 张）同样不是合法牌型
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4))));
        // 3+2 仍是合法三带二
        assertEquals(DdzCardType.TRIPLE_WITH_PAIR,
                recognize(cards(r(11), r(11), r(11), r(4), r(4))).type);
    }

    @Test
    void flowerTripleWithTwoSinglesIsNotValid() {
        // 花牌 + JJJ + 4（5 张）：不得识别为三带一（花牌模式也禁三带二）
        assertTrue(DdzCardTypeRecognizer.recognize(cards(DdzCard.flower(), r(11), r(11), r(11), r(4)),
                true, DdzRuleSet.STANDARD).isEmpty());
        // 经典 + 民间：花牌 + JJJ + 4 → 枚举替换后 3+1+1 非法；替换成 4 时三带二被民间规则禁
        assertTrue(DdzCardTypeRecognizer.recognize(cards(DdzCard.flower(), r(11), r(11), r(11), r(4)),
                false, DdzRuleSet.FOLK).isEmpty());
    }

    @Test
    void fourWithOnePairIsNotValid() {
        // 8888+44（6 张，四带一对）：不是四带两对（带牌必须恰好两对 = 8 张）
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(4))));
        // 8888+444（7 张 4+3）：非法
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(4), r(4))));
        // 8888+44+55+66（10 张，四带三对）：非法
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(4), r(5), r(5), r(6), r(6))));
        // 8888+44+55（8 张，四带两对）：合法
        assertEquals(DdzCardType.FOUR_WITH_TWO_PAIRS,
                recognize(cards(r(8), r(8), r(8), r(8), r(4), r(4), r(5), r(5))).type);
        // 8888+45（6 张，四带两单）：合法
        assertEquals(DdzCardType.FOUR_WITH_TWO_SINGLES,
                recognize(cards(r(8), r(8), r(8), r(8), r(4), r(5))).type);
        // 8888+4444（8 张，双炸弹）：非法（四带两对必须是 4+2+2）
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(4), r(4), r(4))));
    }

    @Test
    void fourWithMixedKickersIsNotValid() {
        // 8888+4+55（7 张，单牌+对子混带）：非法
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(5), r(5))));
        // 8888+4+5+6（7 张，三张单牌）：非法（四带二只能带两张单）
        assertNull(recognize(cards(r(8), r(8), r(8), r(8), r(4), r(5), r(6))));
    }
}
