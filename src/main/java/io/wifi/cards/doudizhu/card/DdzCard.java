package io.wifi.cards.doudizhu.card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一张牌。全牌堆共 55 张，id 固定且双方通用：
 * <ul>
 *   <li>0~51：常规牌（3~2 × 四种花色）</li>
 *   <li>52：小王</li>
 *   <li>53：大王</li>
 *   <li>54：花牌（万能牌，仅花牌模式）</li>
 * </ul>
 * 网络传输只传 id，客户端用 byId 反查。
 */
public final class DdzCard {
    private static final DdzCard[] ALL = new DdzCard[55];

    static {
        int id = 0;
        for (DdzCardRank rank : DdzCardRank.values()) {
            if (rank == DdzCardRank.SMALL_JOKER || rank == DdzCardRank.BIG_JOKER || rank == DdzCardRank.FLOWER) {
                continue;
            }
            for (DdzCardSuit suit : new DdzCardSuit[]{DdzCardSuit.SPADE, DdzCardSuit.HEART, DdzCardSuit.CLUB, DdzCardSuit.DIAMOND}) {
                ALL[id] = new DdzCard(id, rank, suit);
                id++;
            }
        }
        ALL[52] = new DdzCard(52, DdzCardRank.SMALL_JOKER, DdzCardSuit.NONE);
        ALL[53] = new DdzCard(53, DdzCardRank.BIG_JOKER, DdzCardSuit.NONE);
        ALL[54] = new DdzCard(54, DdzCardRank.FLOWER, DdzCardSuit.NONE);
    }

    private final int id;
    private final DdzCardRank rank;
    private final DdzCardSuit suit;

    private DdzCard(int id, DdzCardRank rank, DdzCardSuit suit) {
        this.id = id;
        this.rank = rank;
        this.suit = suit;
    }

    public static DdzCard byId(int id) {
        return ALL[id];
    }

    public static List<DdzCard> byIds(int[] ids) {
        List<DdzCard> list = new ArrayList<>(ids.length);
        for (int id : ids) {
            list.add(byId(id));
        }
        return list;
    }

    public static DdzCard flower() {
        return ALL[54];
    }

    public static DdzCard smallJoker() {
        return ALL[52];
    }

    public static DdzCard bigJoker() {
        return ALL[53];
    }

    /** 按牌值（花牌排最后）排序手牌。 */
    public static void sortByRank(List<DdzCard> hand) {
        hand.sort(Comparator.comparingInt(DdzCard::rankValue).thenComparingInt(c -> c.suit().ordinal()));
    }

    public int id() {
        return id;
    }

    public DdzCardRank rank() {
        return rank;
    }

    public DdzCardSuit suit() {
        return suit;
    }

    public boolean isFlower() {
        return rank == DdzCardRank.FLOWER;
    }

    public boolean isJoker() {
        return rank == DdzCardRank.SMALL_JOKER || rank == DdzCardRank.BIG_JOKER;
    }

    /** 用于大小比较的牌值；花牌本身为 18（不参与直接比较）。 */
    public int rankValue() {
        return rank.value();
    }

    /** 红牌：红心/方块/大王（UI 用红色文字）。 */
    public boolean isRed() {
        return suit == DdzCardSuit.HEART || suit == DdzCardSuit.DIAMOND || rank == DdzCardRank.BIG_JOKER;
    }

    public String display() {
        if (isFlower()) {
            return "⭐花";
        }
        if (rank == DdzCardRank.SMALL_JOKER) {
            return "小王";
        }
        if (rank == DdzCardRank.BIG_JOKER) {
            return "大王";
        }
        return rank.symbol() + suit.symbol();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DdzCard other && other.id == id);
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return display();
    }
}
