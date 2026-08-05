package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 牌型识别引擎。
 * <p>无花牌：频次统计 + 连续性校验（经典规则）。</p>
 * <p>含花牌（万能牌）：</p>
 * <ol>
 *   <li>特殊牌型优先：花牌+三张同值=含花牌炸弹（等于炸弹）</li>
 *   <li>花牌单出=单牌（大王值）</li>
 *   <li>枚举花牌替换值（3~大王）套用经典识别，记录全部合法解读</li>
 *   <li>解读按优先级排序：王炸 &gt; 炸弹/含花牌炸弹 &gt; 一般牌型（同优先级按关键值从大到小）</li>
 * </ol>
 * <p>不存在"三王炸"（花牌 + 大小王无法组成任何合法牌型）。
 * 是否允许三带二 / 飞机带对子 / 四带两对由 {@link DdzRuleSet} 决定（花牌模式恒禁三带二）。</p>
 */
public final class DdzCardTypeRecognizer {
    private DdzCardTypeRecognizer() {
    }

    /** 识别一手牌的全部合法解读（无花牌时至多 1 个），不做规则过滤。非法返回空列表。 */
    public static List<DdzPlayResult> recognize(List<DdzCard> cards) {
        return recognize(cards, null, null);
    }

    /**
     * 识别一手牌并按房间规则过滤解读（禁用的牌型解读直接剔除）。
     *
     * @param flowerMode 花牌模式（恒禁三带二）；null 表示不限制
     * @param ruleSet    规则集（标准/民间）；null 表示不限制
     */
    public static List<DdzPlayResult> recognize(List<DdzCard> cards, Boolean flowerMode, DdzRuleSet ruleSet) {
        List<DdzPlayResult> results = new ArrayList<>();
        List<DdzCard> plain = new ArrayList<>();
        int flowerCount = 0;
        for (DdzCard c : cards) {
            if (c.isFlower()) {
                flowerCount++;
            } else {
                plain.add(c);
            }
        }
        if (flowerCount == 0) {
            DdzPlayResult r = recognizeClassic(cards);
            if (r != null) {
                results.add(r);
            }
            return filter(results, flowerMode, ruleSet);
        }
        if (flowerCount > 1) {
            return results; // 一副牌只有一张花牌
        }

        // ① 含花牌炸弹：花牌 + 三张同值
        if (cards.size() == 4) {
            int tripleRank = tripleRank(plain);
            if (tripleRank > 0) {
                results.add(new DdzPlayResult(DdzCardType.SOFT_BOMB, tripleRank, cards));
            }
        }
        // ② 花牌单出：当作最大的单牌（大王）
        if (cards.size() == 1) {
            results.add(new DdzPlayResult(DdzCardType.SINGLE, 17, cards));
        }
        // ③ 枚举花牌替换值，套用经典识别
        List<Integer> plainRanks = new ArrayList<>(plain.size());
        for (DdzCard c : plain) {
            plainRanks.add(c.rankValue());
        }
        for (int v = 3; v <= 17; v++) {
            List<Integer> virtual = new ArrayList<>(plainRanks);
            virtual.add(v);
            DdzPlayResult r = recognizeByRanks(virtual);
            if (r != null) {
                results.add(new DdzPlayResult(r.type, r.key, cards));
            }
        }
        // 去重（同一牌型+同一关键值的多个替换只保留一个）
        LinkedHashMap<String, DdzPlayResult> unique = new LinkedHashMap<>();
        for (DdzPlayResult r : results) {
            unique.putIfAbsent(r.type.name() + ":" + r.key, r);
        }
        results = new ArrayList<>(unique.values());
        // 按优先级排序：等级高的在前，同等级关键值大的在前（稳定排序保证含花牌炸弹先于炸弹）
        results.sort(Comparator.comparingInt((DdzPlayResult r) -> -r.type.level())
                .thenComparingInt(r -> -r.key));
        return filter(results, flowerMode, ruleSet);
    }

    private static List<DdzPlayResult> filter(List<DdzPlayResult> results, Boolean flowerMode, DdzRuleSet ruleSet) {
        if (ruleSet != null) {
            boolean flower = Boolean.TRUE.equals(flowerMode);
            results.removeIf(r -> !ruleSet.allows(r.type, flower));
        }
        return results;
    }

    /**
     * 从一手牌的全部解读中选出"能压过 target 的最优解读"。
     *
     * @param target 上一手（null 表示自由出牌）
     * @return 最优解读；无法出牌返回 null
     */
    public static DdzPlayResult bestAgainst(List<DdzCard> cards, DdzPlayResult target) {
        for (DdzPlayResult r : recognize(cards)) {
            if (r.canBeat(target)) {
                return r;
            }
        }
        return null;
    }

    private static DdzPlayResult recognizeClassic(List<DdzCard> cards) {
        List<Integer> ranks = new ArrayList<>(cards.size());
        for (DdzCard c : cards) {
            ranks.add(c.rankValue());
        }
        DdzPlayResult r = recognizeByRanks(ranks);
        return r == null ? null : new DdzPlayResult(r.type, r.key, cards);
    }

    /**
     * 经典规则识别（输入为牌值列表，不含花牌）。
     * 返回的 DdzPlayResult.cards 为 null，仅用于提取 (type, key)。
     */
    static DdzPlayResult recognizeByRanks(List<Integer> ranks) {
        int n = ranks.size();
        if (n == 0) {
            return null;
        }
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (int r : ranks) {
            counts.merge(r, 1, Integer::sum);
        }

        // 王炸：大王 + 小王
        if (n == 2 && counts.containsKey(16) && counts.containsKey(17)) {
            return new DdzPlayResult(DdzCardType.ROCKET, 17, null);
        }
        // 炸弹：四张同值
        if (n == 4 && counts.containsValue(4)) {
            return new DdzPlayResult(DdzCardType.BOMB, keyOfCount(counts, 4), null);
        }
        if (n == 1) {
            return new DdzPlayResult(DdzCardType.SINGLE, ranks.get(0), null);
        }
        if (n == 2 && counts.size() == 1) {
            return new DdzPlayResult(DdzCardType.PAIR, ranks.get(0), null);
        }
        if (n == 3 && counts.size() == 1) {
            return new DdzPlayResult(DdzCardType.TRIPLE, ranks.get(0), null);
        }
        if (n == 4) {
            // 三带一
            if (counts.size() == 2 && counts.containsValue(3)) {
                return new DdzPlayResult(DdzCardType.TRIPLE_WITH_ONE, keyOfCount(counts, 3), null);
            }
            return null;
        }
        if (n == 5) {
            if (counts.containsValue(3)) {
                if (counts.size() == 2) {
                    // 三带二
                    return new DdzPlayResult(DdzCardType.TRIPLE_WITH_PAIR, keyOfCount(counts, 3), null);
                }
                if (counts.size() == 3) {
                    // 三带一
                    return new DdzPlayResult(DdzCardType.TRIPLE_WITH_ONE, keyOfCount(counts, 3), null);
                }
            }
            // 无三张则继续走顺子判断
        }

        // 单顺：5 张以上连续单牌（不含 2 与王）
        if (n >= 5 && counts.size() == n && isConsecutive(new ArrayList<>(counts.keySet()), n) && counts.lastKey() <= 14) {
            return new DdzPlayResult(DdzCardType.STRAIGHT, counts.lastKey(), null);
        }
        if (n >= 6) {
            // 连对：3 对以上连续对子
            if (allCount(counts, 2) && isConsecutive(new ArrayList<>(counts.keySet()), n / 2) && counts.lastKey() <= 14) {
                return new DdzPlayResult(DdzCardType.DOUBLE_STRAIGHT, counts.lastKey(), null);
            }
            // 飞机：2 组以上连续三张
            if (allCount(counts, 3) && isConsecutive(new ArrayList<>(counts.keySet()), n / 3) && counts.lastKey() <= 14) {
                return new DdzPlayResult(DdzCardType.PLANE, counts.lastKey(), null);
            }
            // 四带二：四张 + 两张单牌（两单须不同牌点），或四张 + 一对/两对
            if (counts.containsValue(4)) {
                int quad = keyOfCount(counts, 4);
                int others = n - 4;
                boolean onlySingles = true;
                boolean onlyPairs = true;
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    if (e.getKey() == quad) {
                        continue;
                    }
                    if (e.getValue() != 1) {
                        onlySingles = false;
                    }
                    if (e.getValue() != 2) {
                        onlyPairs = false;
                    }
                }
                if (others == 2 && onlySingles) {
                    return new DdzPlayResult(DdzCardType.FOUR_WITH_TWO_SINGLES, quad, null);
                }
                if (onlyPairs) {
                    return new DdzPlayResult(DdzCardType.FOUR_WITH_TWO_PAIRS, quad, null);
                }
            }
            // 飞机带翅膀：三顺 + 同数量单牌（或同数量对子）
            int triples = 0;
            List<Integer> tripleRanks = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                if (e.getValue() == 3) {
                    triples++;
                    tripleRanks.add(e.getKey());
                }
            }
            if (triples >= 2 && isConsecutive(tripleRanks, triples) && tripleRanks.get(tripleRanks.size() - 1) <= 14) {
                int wings = 0;
                boolean wingPairs = true;
                boolean wingSingles = true;
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    if (e.getValue() == 3) {
                        continue;
                    }
                    if (e.getValue() == 2) {
                        wings++;
                        wingSingles = false;
                    } else if (e.getValue() == 1) {
                        wings++;
                        wingPairs = false;
                    } else {
                        wingPairs = false;
                        wingSingles = false;
                    }
                }
                int planeKey = tripleRanks.get(tripleRanks.size() - 1);
                if (wings == triples && wingSingles) {
                    return new DdzPlayResult(DdzCardType.PLANE_WITH_SINGLES, planeKey, null);
                }
                if (wings == triples && wingPairs) {
                    return new DdzPlayResult(DdzCardType.PLANE_WITH_PAIRS, planeKey, null);
                }
            }
        }
        return null;
    }

    /** 若恰有三张同值牌（且没有其他牌），返回该牌值；否则返回 -1。 */
    private static int tripleRank(List<DdzCard> cards) {
        if (cards.size() != 3) {
            return -1;
        }
        int r = cards.get(0).rankValue();
        return cards.get(1).rankValue() == r && cards.get(2).rankValue() == r ? r : -1;
    }

    private static int keyOfCount(TreeMap<Integer, Integer> counts, int count) {
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() == count) {
                return e.getKey();
            }
        }
        return -1;
    }

    private static boolean allCount(TreeMap<Integer, Integer> counts, int count) {
        for (int v : counts.values()) {
            if (v != count) {
                return false;
            }
        }
        return true;
    }

    /** sorted 需已按升序排列，且不含重复元素。 */
    private static boolean isConsecutive(List<Integer> sorted, int expected) {
        if (sorted.size() != expected) {
            return false;
        }
        int prev = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i) != prev + 1) {
                return false;
            }
            prev = sorted.get(i);
        }
        return true;
    }
}
