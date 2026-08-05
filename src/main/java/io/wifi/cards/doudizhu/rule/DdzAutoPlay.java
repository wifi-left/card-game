package io.wifi.cards.doudizhu.rule;

import io.wifi.cards.doudizhu.card.DdzCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 托管 / 提示 的出牌策略（服务端托管与客户端"提示"按钮共用）。
 * <p>需要压过上家时优先同牌型最小可压，压不住才用炸弹/王炸兜底（不浪费炸弹）；
 * 自由出牌时整牌型优先（飞机→连对→顺子→三带→三张→对子→单牌），单牌优先孤牌避免拆对。
 * 所有候选都会经过识别引擎校验（含规则过滤）。</p>
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
        TreeMap<Integer, List<DdzCard>> byRank = groupByRank(hand);

        // 自由出牌：整牌型优先
        if (target == null) {
            return findFreePlay(byRank, flower, flowerMode, ruleSet);
        }

        // ① 同牌型最小可压组合（先同型，压不住再用炸弹级，避免浪费炸弹）
        List<DdzCard> same = sameType(byRank, flower, target, flowerMode, ruleSet);
        if (same != null) {
            return same;
        }
        // ② 炸弹级兜底：对面是炸弹级牌型时只能更大炸弹/王炸互压；一般牌型压不住时也炸
        return findBomb(byRank, flower, target, flowerMode, ruleSet);
    }

    /** 炸弹级兜底：王炸 → 炸弹 → 软炸弹（从小到大）。 */
    private static List<DdzCard> findBomb(TreeMap<Integer, List<DdzCard>> byRank, List<DdzCard> flower,
                                          DdzPlayResult target, boolean flowerMode, DdzRuleSet ruleSet) {
        // 王炸
        if (byRank.containsKey(16) && byRank.containsKey(17)) {
            List<DdzCard> picked = listOf(byRank.get(16).get(0), byRank.get(17).get(0));
            if (playable(picked, target, flowerMode, ruleSet)) {
                return picked;
            }
        }
        // 炸弹（从小到大）
        for (List<DdzCard> cards : byRank.values()) {
            if (cards.size() >= 4) {
                List<DdzCard> picked = cards.subList(0, 4);
                if (playable(picked, target, flowerMode, ruleSet)) {
                    return picked;
                }
            }
        }
        // 软炸弹：花牌 + 三张同值（从小到大；走到这里说明花牌未被同型候选占用）
        if (!flower.isEmpty()) {
            for (List<DdzCard> cards : byRank.values()) {
                if (cards.size() >= 3) {
                    List<DdzCard> picked = listOf(flower.get(0), cards.get(0), cards.get(1), cards.get(2));
                    if (playable(picked, target, flowerMode, ruleSet)) {
                        return picked;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 自由出牌：整牌型优先（消耗多张牌快速清手牌），单牌优先孤牌不拆对。
     * 顺序：飞机带翅 → 裸飞机 → 连对 → 顺子 → 三带一 → 三带二 → 三张 → 对子 → 单牌。
     */
    private static List<DdzCard> findFreePlay(TreeMap<Integer, List<DdzCard>> byRank, List<DdzCard> flower,
                                              boolean flowerMode, DdzRuleSet ruleSet) {
        List<DdzCard> cand;
        cand = freePlaneWinged(byRank, flowerMode, ruleSet);
        if (cand != null) {
            return cand;
        }
        cand = freePlane(byRank);
        if (cand != null) {
            return cand;
        }
        cand = freeDoubleStraight(byRank);
        if (cand != null) {
            return cand;
        }
        cand = freeStraight(byRank);
        if (cand != null) {
            return cand;
        }
        cand = freeTripleWithOne(byRank);
        if (cand != null) {
            return cand;
        }
        cand = freeTripleWithPair(byRank, flowerMode, ruleSet);
        if (cand != null) {
            return cand;
        }
        cand = freeTriple(byRank);
        if (cand != null) {
            return cand;
        }
        cand = freePair(byRank);
        if (cand != null) {
            return cand;
        }
        return freeSingle(byRank, flower);
    }

    /** 最小 5 张顺子（起点最低优先）。 */
    private static List<DdzCard> freeStraight(TreeMap<Integer, List<DdzCard>> byRank) {
        for (int start = 3; start <= 14 - 5 + 1; start++) {
            List<DdzCard> cand = new ArrayList<>(5);
            boolean ok = true;
            for (int r = start; r < start + 5; r++) {
                List<DdzCard> cs = byRank.get(r);
                if (cs == null) {
                    ok = false;
                    break;
                }
                cand.add(cs.get(0));
            }
            if (ok) {
                return cand;
            }
        }
        return null;
    }

    /** 最小 3 连对（起点最低优先）。 */
    private static List<DdzCard> freeDoubleStraight(TreeMap<Integer, List<DdzCard>> byRank) {
        for (int start = 3; start <= 14 - 3 + 1; start++) {
            List<DdzCard> cand = new ArrayList<>(6);
            boolean ok = true;
            for (int r = start; r < start + 3; r++) {
                List<DdzCard> cs = byRank.get(r);
                if (cs == null || cs.size() < 2) {
                    ok = false;
                    break;
                }
                cand.addAll(cs.subList(0, 2));
            }
            if (ok) {
                return cand;
            }
        }
        return null;
    }

    /** 最小 2 组裸飞机（起点最低优先）。 */
    private static List<DdzCard> freePlane(TreeMap<Integer, List<DdzCard>> byRank) {
        for (int start = 3; start <= 14 - 2 + 1; start++) {
            List<DdzCard> cand = new ArrayList<>(6);
            boolean ok = true;
            for (int r = start; r < start + 2; r++) {
                List<DdzCard> cs = byRank.get(r);
                if (cs == null || cs.size() < 3) {
                    ok = false;
                    break;
                }
                cand.addAll(cs.subList(0, 3));
            }
            if (ok) {
                return cand;
            }
        }
        return null;
    }

    /** 飞机带翅膀：最小 2 组机身 + 同数量单牌（或对子，标准规则）。 */
    private static List<DdzCard> freePlaneWinged(TreeMap<Integer, List<DdzCard>> byRank,
                                                 boolean flowerMode, DdzRuleSet ruleSet) {
        // 带单牌翅膀（所有规则允许）
        for (int start = 3; start <= 14 - 2 + 1; start++) {
            List<DdzCard> base = new ArrayList<>(6);
            boolean ok = true;
            for (int r = start; r < start + 2; r++) {
                List<DdzCard> cs = byRank.get(r);
                if (cs == null || cs.size() < 3) {
                    ok = false;
                    break;
                }
                base.addAll(cs.subList(0, 3));
            }
            if (!ok) {
                continue;
            }
            List<DdzCard> wings = smallestSingles(byRank, start, start + 1, 2, null);
            if (wings != null) {
                return concat(base, wings);
            }
        }
        // 带对子翅膀（仅标准规则）
        if (ruleSet.allows(DdzCardType.PLANE_WITH_PAIRS, flowerMode)) {
            for (int start = 3; start <= 14 - 2 + 1; start++) {
                List<DdzCard> base = new ArrayList<>(6);
                boolean ok = true;
                for (int r = start; r < start + 2; r++) {
                    List<DdzCard> cs = byRank.get(r);
                    if (cs == null || cs.size() < 3) {
                        ok = false;
                        break;
                    }
                    base.addAll(cs.subList(0, 3));
                }
                if (!ok) {
                    continue;
                }
                List<DdzCard> wings = smallestPairs(byRank, start, start + 1, 2, null);
                if (wings != null) {
                    return concat(base, wings);
                }
            }
        }
        return null;
    }

    /** 三带一：最小三张 + 最小单牌踢脚。 */
    private static List<DdzCard> freeTripleWithOne(TreeMap<Integer, List<DdzCard>> byRank) {
        for (var e : byRank.entrySet()) {
            if (e.getValue().size() >= 3) {
                List<DdzCard> kicker = smallestKicker(byRank, e.getKey(), null);
                if (kicker != null) {
                    return concat(e.getValue().subList(0, 3), kicker);
                }
            }
        }
        return null;
    }

    /** 三带二：最小三张 + 最小对子（仅标准规则）。 */
    private static List<DdzCard> freeTripleWithPair(TreeMap<Integer, List<DdzCard>> byRank,
                                                    boolean flowerMode, DdzRuleSet ruleSet) {
        if (!ruleSet.allows(DdzCardType.TRIPLE_WITH_PAIR, flowerMode)) {
            return null;
        }
        for (var e : byRank.entrySet()) {
            if (e.getValue().size() >= 3) {
                List<DdzCard> pair = smallestPair(byRank, e.getKey());
                if (pair != null) {
                    return concat(e.getValue().subList(0, 3), pair);
                }
            }
        }
        return null;
    }

    /** 最小三张。 */
    private static List<DdzCard> freeTriple(TreeMap<Integer, List<DdzCard>> byRank) {
        for (var e : byRank.entrySet()) {
            if (e.getValue().size() >= 3) {
                return e.getValue().subList(0, 3);
            }
        }
        return null;
    }

    /** 最小对子。 */
    private static List<DdzCard> freePair(TreeMap<Integer, List<DdzCard>> byRank) {
        for (var e : byRank.entrySet()) {
            if (e.getValue().size() >= 2) {
                return e.getValue().subList(0, 2);
            }
        }
        return null;
    }

    /** 最小单牌：孤牌优先（不拆对子/三张/炸弹），无孤牌才拆最小牌组，最后花牌。 */
    private static List<DdzCard> freeSingle(TreeMap<Integer, List<DdzCard>> byRank, List<DdzCard> flower) {
        for (var e : byRank.entrySet()) {
            if (e.getValue().size() == 1) {
                return listOf(e.getValue().get(0));
            }
        }
        for (var e : byRank.entrySet()) {
            return listOf(e.getValue().get(0));
        }
        return flower.isEmpty() ? null : listOf(flower.get(0));
    }

    private static List<DdzCard> sameType(TreeMap<Integer, List<DdzCard>> byRank, List<DdzCard> flower, DdzPlayResult target,
                                          boolean flowerMode, DdzRuleSet ruleSet) {
        boolean hasFlower = !flower.isEmpty();
        int len;
        int lo;
        int hi;
        switch (target.type) {
            case SINGLE -> {
                // 优先孤牌（不拆对子/三张/炸弹），无孤牌才拆最小的
                for (var e : byRank.entrySet()) {
                    if (e.getKey() > target.key && e.getValue().size() == 1) {
                        return listOf(e.getValue().get(0));
                    }
                }
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
                    if (wings != null) {
                        List<DdzCard> full = concat(cand, wings);
                        if (playable(full, target, flowerMode, ruleSet)) {
                            return full;
                        }
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
                    if (wings != null) {
                        List<DdzCard> full = concat(cand, wings);
                        if (playable(full, target, flowerMode, ruleSet)) {
                            return full;
                        }
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

    /** 最小的单牌作为三带一的"带牌"；excludeRank 为三张的牌值。优先孤牌不拆对。flower 可为 null。 */
    private static List<DdzCard> smallestKicker(TreeMap<Integer, List<DdzCard>> byRank, int excludeRank, DdzCard flower) {
        for (var e : byRank.entrySet()) {
            if (e.getKey() != excludeRank && e.getValue().size() == 1) {
                return listOf(e.getValue().get(0));
            }
        }
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
     * 优先孤牌（不拆对子），不足再拆；flower 非 null 时可用花牌补最后一张。
     */
    private static List<DdzCard> smallestSingles(TreeMap<Integer, List<DdzCard>> byRank, int excludeLo, int excludeHi,
                                              int need, DdzCard flower) {
        List<DdzCard> result = new ArrayList<>(need);
        Set<Integer> used = new HashSet<>();
        // 第一遍：孤牌优先
        for (var e : byRank.entrySet()) {
            if (e.getKey() >= excludeLo && e.getKey() <= excludeHi) {
                continue;
            }
            if (result.size() >= need) {
                break;
            }
            if (e.getValue().size() == 1) {
                result.add(e.getValue().get(0));
                used.add(e.getKey());
            }
        }
        // 第二遍：任意牌组补足（不重复取已用牌组，可拆对子）
        for (var e : byRank.entrySet()) {
            if (e.getKey() >= excludeLo && e.getKey() <= excludeHi) {
                continue;
            }
            if (result.size() >= need) {
                break;
            }
            if (!used.contains(e.getKey())) {
                result.add(e.getValue().get(0));
            }
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
