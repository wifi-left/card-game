package io.wifi.cards.uno.card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一张 UNO 牌。全牌堆共 108 张，id 固定且双方通用：
 * <ul>
 *   <li>0~99：四色牌（每色 25 张：0 一张、1~9 各两张、跳过/反转/+2 各两张）</li>
 *   <li>100~103：万能牌（4 张）</li>
 *   <li>104~107：万能+4（4 张）</li>
 * </ul>
 * 网络传输只传 id，客户端用 byId 反查。
 */
public final class UnoCard {
    /** 全牌堆牌数（0~107 共 108 张）。 */
    public static final int TOTAL_COUNT = 108;

    private static final UnoCard[] ALL = new UnoCard[TOTAL_COUNT];

    static {
        int id = 0;
        for (UnoColor color : new UnoColor[]{UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE}) {
            ALL[id] = new UnoCard(id, color, UnoValue.ZERO);
            id++;
            for (UnoValue value : UnoValue.values()) {
                if (value == UnoValue.ZERO || value == UnoValue.WILD || value == UnoValue.WILD4) {
                    continue;
                }
                for (int i = 0; i < 2; i++) {
                    ALL[id] = new UnoCard(id, color, value);
                    id++;
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            ALL[id] = new UnoCard(id, UnoColor.NONE, UnoValue.WILD);
            id++;
        }
        for (int i = 0; i < 4; i++) {
            ALL[id] = new UnoCard(id, UnoColor.NONE, UnoValue.WILD4);
            id++;
        }
    }

    private final int id;
    private final UnoColor color;
    private final UnoValue value;

    private UnoCard(int id, UnoColor color, UnoValue value) {
        this.id = id;
        this.color = color;
        this.value = value;
    }

    public static UnoCard byId(int id) {
        return ALL[id];
    }

    public static List<UnoCard> byIds(int[] ids) {
        List<UnoCard> list = new ArrayList<>(ids.length);
        for (int id : ids) {
            list.add(byId(id));
        }
        return list;
    }

    /** 按颜色（红黄绿蓝，万能牌最后）再按牌面值排序手牌。 */
    public static void sortByValue(List<UnoCard> hand) {
        hand.sort(Comparator.comparingInt((UnoCard c) -> c.color().ordinal()).thenComparingInt(c -> c.value().ordinal()));
    }

    public int id() {
        return id;
    }

    public UnoColor color() {
        return color;
    }

    public UnoValue value() {
        return value;
    }

    public boolean isWild() {
        return value.isWild();
    }

    /**
     * 牌面显示翻译键：每张牌一个键（如 wifi_card_games.uno.card.r_5 / y_skip / wild / wild4），
     * 展示时经 Component.translatable 解析。
     */
    public String display() {
        if (isWild()) {
            return "wifi_card_games.uno.card." + (value == UnoValue.WILD4 ? "wild4" : "wild");
        }
        return "wifi_card_games.uno.card." + color.shortKey() + "_" + value.shortKey();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof UnoCard other && other.id == id);
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
