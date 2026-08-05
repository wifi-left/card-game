package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 托管 / 提示 的出牌策略（服务端托管与客户端"提示"按钮共用）。
 * <p>需要压过上家时按优先级找第一手能出的牌：
 * 王炸 → 炸弹 → 含花牌炸弹 → 同牌型最小可压组合（顺子/连对/飞机可用花牌补位）。
 * 自由出牌时出最小的单牌。所有候选都会经过识别引擎校验（含规则过滤）。</p>
 */
public final class DdzAutoPlay {
    private DdzAutoPlay() {
    }

    /**
     * 从手牌中找第一手能出（能压过 target）的牌。
     *
     * @param target     上一手（null 表示自由出牌）
     * @param flowerMode 花牌模式（恒禁三带二）
     * @param ruleSet    规则集（标准/民间）
     * @return 要出的牌；找不到返回 null（应 Pass）
     */
    public static List<DdzCard> findPlay(List<DdzCard> hand, DdzPlayResult target, boolean flowerMode, DdzRuleSet ruleSet) {
        if (hand.isEmpty()) {
            return null;
        }
        List<DdzCard> flower = flowerOf(hand);
        boolean hasFlower = !flower.isEmpty();
        TreeMap<Integer, List<DdzCard>> byRank = groupByRank(hand);

        // 自由出牌：出最小的单牌
        if (target == null) {
            for (List<DdzCard> cards : byRank.values()) {
                return listOf(cards.get(0));
            }
            return hasFlower ? listOf(flower.get(0)) : null;
        }

        // ① 王炸
        if (byRank.containsKey(16) && byRank.containsKey(17)) {
            List<DdzCard> picked = listOf(byRank.get(16).get(0), byRank.get(17).get(0));
            if (playable(picked, target, flowerMode, ruleSet)) {
                return picked;
            }
        }
        // ② 炸弹（从小到大）
        for (List<DdzCard> cards : byRank.values()) {
            if (cards.size() >= 4) {
                List<DdzCard> picked = cards.subList(0, 4);
                if (playable(picked, target, flowerMode, ruleSet)) {
                    return picked;
                }
            }
        }
        // ③ 含花牌炸弹：花牌 + 三张同值（从小到大）
        if (hasFlower) {
            for (List<DdzCard> cards : byRank.values()) {
                if (cards.size() >= 3) {
                    List<DdzCard> picked = listOf(flower.get(0), cards.get(0), cards.get(1), cards.get(2));
                    if (playable(picked, target, flowerMode, ruleSet)) {
                        return picked;
                    }
                }
            }
        }
        // ④ 对面是炸弹级牌型且已无更小的炸弹可压
        if (target.type.isBombLike()) {
            return null;
        }
        // ⑤ 同牌型最小可压组合
        return sameType(byRank, flower, target, flowerMode, ruleSet);
    }

    private static List<DdzCard> sameType(TreeMap<Integer, List<DdzCard>> byRank, List<DdzCard> flower, DdzPlayResult target,
                                          boolean flowerMode, DdzRuleSet ruleSet) {
        boolean hasFlower = !flower.isEmpty();
        int len;
        int lo;
        int hi;
        switch (target.type) {
            case SINGLE -> {
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key) {
                        return listOf(e.getValue().get(0));
                    }
                }
                if (hasFlower) {
                    return listOf(flower.get(0)); // 花牌当作大王
                }
                return null;
            }
            case PAIR -> {
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key && e.getValue().size() >= 2) {
                        return e.getValue().subList(0, 2);
                    }
                }
                if (hasFlower) {
                    for (var e : byRank.entrySet()) {
                        if (e.getKey() > target.key) {
                            return listOf(flower.get(0), e.getValue().get(0));
                        }
                    }
                }
                return null;
            }
            case TRIPLE -> {
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key && e.getValue().size() >= 3) {
                        return e.getValue().subList(0, 3);
                    }
                }
                if (hasFlower) {
                    for (var e : byRank.entrySet()) {
                        if (e.getKey() > target.key && e.getValue().size() >= 2) {
                            return listOf(flower.get(0), e.getValue().get(0), e.getValue().get(1));
                        }
                    }
                }
                return null;
            }
            case TRIPLE_WITH_ONE -> {
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key && e.getValue().size() >= 3) {
                        List<DdzCard> kicker = smallestKicker(byRank, e.getKey(), hasFlower ? flower.get(0) : null);
                        if (kicker != null) {
                            return concat(e.getValue().subList(0, 3), kicker);
                        }
                    }
                }
                if (hasFlower) {
                    for (var e : byRank.entrySet()) {
                        if (e.getKey() > target.key && e.getValue().size() >= 2) {
                            List<DdzCard> kicker = smallestKicker(byRank, e.getKey(), null);
                            if (kicker != null) {
                                return concat(listOf(flower.get(0), e.getValue().get(0), e.getValue().get(1)), kicker);
                            }
                        }
                    }
                }
                return null;
            }
            case TRIPLE_WITH_PAIR -> {
                // 仅当 target 存在时才会走到这里，说明房间允许三带二；候选仍需经过 playable 规则过滤
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key && e.getValue().size() >= 3) {
                        List<DdzCard> pair = smallestPair(byRank, e.getKey());
                        if (pair != null) {
                            List<DdzCard> cand = concat(e.getValue().subList(0, 3), pair);
                            if (playable(cand, target, flowerMode, ruleSet)) {
                                return cand;
                            }
                        }
                    }
                }
                if (hasFlower) {
                    for (var e : byRank.entrySet()) {
                        if (e.getKey() > target.key && e.getValue().size() >= 2) {
                            List<DdzCard> pair = smallestPair(byRank, e.getKey());
                            if (pair != null) {
                                List<DdzCard> cand = concat(listOf(flower.get(0), e.getValue().get(0), e.getValue().get(1)), pair);
                                if (playable(cand, target, flowerMode, ruleSet)) {
                                    return cand;
                                }
                            }
                        }
                    }
                }
                return null;
            }
            case STRAIGHT -> {
                len = target.cards.size();
                lo = Math.max(3, target.key - len + 2);
                hi = 14 - len + 1;
                for (int start = lo; start <= hi; start++) {
                    int missing = 0;
                    List<DdzCard> cand = new ArrayList<>();
                    for (int r = start; r < start + len; r++) {
                        List<DdzCard> cs = byRank.get(r);
                        if (cs == null) {
                            missing++;
                        } else {
                            cand.add(cs.get(0));
                        }
                    }
                    if (missing == 0 && playable(cand, target, flowerMode, ruleSet)) {
                        return cand;
                    }
                    if (missing == 1 && hasFlower) {
                        cand.add(flower.get(0));
                        if (playable(cand, target, flowerMode, ruleSet)) {
                            return cand;
                        }
                    }
                }
                return null;
            }
            case DOUBLE_STRAIGHT -> {
                len = target.cards.size() / 2;
                lo = Math.max(3, target.key - len + 2);
                hi = 14 - len + 1;
                for (int start = lo; start <= hi; start++) {
                    int missing = 0;
                    List<DdzCard> cand = new ArrayList<>();
                    for (int r = start; r < start + len; r++) {
                        List<DdzCard> cs = byRank.get(r);
                        if (cs == null || cs.size() < 2) {
                            missing += cs == null ? 2 : 1;
                        } else {
                            cand.addAll(cs.subList(0, 2));
                        }
                    }
                    if (missing == 0 && playable(cand, target, flowerMode, ruleSet)) {
                        return cand;
                    }
                    if (missing == 1 && hasFlower) {
                        cand.add(flower.get(0));
                        if (playable(cand, target, flowerMode, ruleSet)) {
                            return cand;
                        }
                    }
                }
                return null;
            }
            case PLANE -> {
                len = target.cards.size() / 3;
                lo = Math.max(3, target.key - len + 2);
                hi = 14 - len + 1;
                for (int start = lo; start <= hi; start++) {
                    int missing = 0;
                    List<DdzCard> cand = new ArrayList<>();
                    for (int r = start; r < start + len; r++) {
                        List<DdzCard> cs = byRank.get(r);
                        if (cs == null || cs.size() < 3) {
                            missing += cs == null ? 3 : 3 - cs.size();
                        } else {
                            cand.addAll(cs.subList(0, 3));
                        }
                    }
                    if (missing == 0 && playable(cand, target, flowerMode, ruleSet)) {
                        return cand;
                    }
                    if (missing == 1 && hasFlower) {
                        cand.add(flower.get(0));
                        if (playable(cand, target, flowerMode, ruleSet)) {
                            return cand;
                        }
                    }
                }
                return null;
            }
            case PLANE_WITH_SINGLES -> {
                len = target.cards.size() / 4;
                lo = Math.max(3, target.key - len + 2);
                hi = 14 - len + 1;
                for (int start = lo; start <= hi; start++) {
                    List<DdzCard> base = new ArrayList<>();
                    int missing = 0;
                    for (int r = start; r < start + len; r++) {
                        List<DdzCard> cs = byRank.get(r);
                        if (cs == null || cs.size() < 3) {
                            missing += cs == null ? 3 : 3 - cs.size();
                        } else {
                            base.addAll(cs.subList(0, 3));
                        }
                    }
                    if (missing > 1) {
                        continue;
                    }
                    if (missing == 1 && !hasFlower) {
                        continue;
                    }
                    List<DdzCard> cand = new ArrayList<>(base);
                    boolean flowerUsed = missing == 1;
                    if (flowerUsed) {
                        cand.add(flower.get(0));
                    }
                    // 花牌未被机身占用时，可作最后一张单牌翅膀
                    List<DdzCard> wings = smallestSingles(byRank, start, start + len - 1, len,
                            !flowerUsed && hasFlower ? flower.get(0) : null);
                    List<DdzCard> full = concat(cand, wings);
                    if (wings != null && playable(full, target, flowerMode, ruleSet)) {
                        return full;
                    }
                }
                return null;
            }
            case PLANE_WITH_PAIRS -> {
                len = target.cards.size() / 5;
                lo = Math.max(3, target.key - len + 2);
                hi = 14 - len + 1;
                for (int start = lo; start <= hi; start++) {
                    List<DdzCard> base = new ArrayList<>();
                    int missing = 0;
                    for (int r = start; r < start + len; r++) {
                        List<DdzCard> cs = byRank.get(r);
                        if (cs == null || cs.size() < 3) {
                            missing += cs == null ? 3 : 3 - cs.size();
                        } else {
                            base.addAll(cs.subList(0, 3));
                        }
                    }
                    if (missing > 1) {
                        continue;
                    }
                    if (missing == 1 && !hasFlower) {
                        continue;
                    }
                    List<DdzCard> cand = new ArrayList<>(base);
                    boolean flowerUsed = missing == 1;
                    if (flowerUsed) {
                        cand.add(flower.get(0));
                    }
                    List<DdzCard> wings = smallestPairs(byRank, start, start + len - 1, len,
                            !flowerUsed && hasFlower ? flower.get(0) : null);
                    List<DdzCard> full = concat(cand, wings);
                    if (wings != null && playable(full, target, flowerMode, ruleSet)) {
                        return full;
                    }
                }
                return null;
            }
            default -> {
                // 四带二与四带二不可互压，只需炸弹级（已在前面尝试）
                return null;
            }
        }
    }

    // ---------- 工具 ----------

    /** 候选是否为一手合法牌（按房间规则过滤），且能压过 target。 */
    private static boolean playable(List<DdzCard> cards, DdzPlayResult target, boolean flowerMode, DdzRuleSet ruleSet) {
        for (DdzPlayResult r : DdzCardTypeRecognizer.recognize(cards, flowerMode, ruleSet)) {
            if (r.canBeat(target)) {
                return true;
            }
        }
        return false;
    }

    /** 按牌值分组（不含花牌），升序。 */
    private static TreeMap<Integer, List<DdzCard>> groupByRank(List<DdzCard> hand) {
        TreeMap<Integer, List<DdzCard>> map = new TreeMap<>();
        for (DdzCard c : hand) {
            if (!c.isFlower()) {
                map.computeIfAbsent(c.rankValue(), k -> new ArrayList<>()).add(c);
            }
        }
        return map;
    }

    private static List<DdzCard> flowerOf(List<DdzCard> hand) {
        List<DdzCard> flower = new ArrayList<>();
        for (DdzCard c : hand) {
            if (c.isFlower()) {
                flower.add(c);
            }
        }
        return flower;
    }

    /** 最小的单牌作为三带一的"带牌"；excludeRank 为三张的牌值。flower 可为 null。 */
    private static List<DdzCard> smallestKicker(TreeMap<Integer, List<DdzCard>> byRank, int excludeRank, DdzCard flower) {
        for (var e : byRank.entrySet()) {
            if (e.getKey() != excludeRank) {
                return listOf(e.getValue().get(0));
            }
        }
        return flower != null ? listOf(flower) : null;
    }

    /** 最小的对子作为三带二的"带牌"；excludeRank 为三张的牌值。 */
    private static List<DdzCard> smallestPair(TreeMap<Integer, List<DdzCard>> byRank, int excludeRank) {
        for (var e : byRank.entrySet()) {
            if (e.getKey() != excludeRank && e.getValue().size() >= 2) {
                return e.getValue().subList(0, 2);
            }
        }
        return null;
    }

    /**
     * 取 need 张"单牌翅膀"（牌值不落在 [excludeLo, excludeHi] 区间内），从小到大。
     * flower 非 null 时可用花牌补最后一张。
     */
    private static List<DdzCard> smallestSingles(TreeMap<Integer, List<DdzCard>> byRank, int excludeLo, int excludeHi,
                                              int need, DdzCard flower) {
        List<DdzCard> result = new ArrayList<>(need);
        for (var e : byRank.entrySet()) {
            if (e.getKey() >= excludeLo && e.getKey() <= excludeHi) {
                continue;
            }
            if (result.size() >= need) {
                break;
            }
            result.add(e.getValue().get(0));
        }
        if (result.size() < need && flower != null) {
            result.add(flower);
        }
        return result.size() == need ? result : null;
    }

    /**
     * 取 need 对"对子翅膀"（牌值不落在 [excludeLo, excludeHi] 区间内），从小到大。
     * flower 非 null 时可用花牌与一张单牌补最后一对。
     */
    private static List<DdzCard> smallestPairs(TreeMap<Integer, List<DdzCard>> byRank, int excludeLo, int excludeHi,
                                            int need, DdzCard flower) {
        List<DdzCard> result = new ArrayList<>();
        for (var e : byRank.entrySet()) {
            if (e.getKey() >= excludeLo && e.getKey() <= excludeHi) {
                continue;
            }
            if (e.getValue().size() >= 2) {
                result.addAll(e.getValue().subList(0, 2));
                if (result.size() / 2 >= need) {
                    break;
                }
            }
        }
        if (result.size() / 2 < need && flower != null) {
            for (var e : byRank.entrySet()) {
                if (e.getKey() >= excludeLo && e.getKey() <= excludeHi) {
                    continue;
                }
                if (e.getValue().size() >= 1) {
                    result.add(e.getValue().get(0));
                    result.add(flower);
                    break;
                }
            }
        }
        return result.size() / 2 == need ? result : null;
    }

    private static List<DdzCard> listOf(DdzCard... cards) {
        return new ArrayList<>(List.of(cards));
    }

    private static List<DdzCard> concat(List<DdzCard> a, List<DdzCard> b) {
        List<DdzCard> result = new ArrayList<>(a.size() + b.size());
        result.addAll(a);
        result.addAll(b);
        return result;
    }
}
