package io.wifi.cards.uno.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 牌堆：标准 108 张（四色 100 张 + 万能 4 张 + 万能+4 4 张）。 */
public final class UnoDeck {
    private UnoDeck() {
    }

    public static List<UnoCard> create() {
        List<UnoCard> deck = new ArrayList<>(UnoCard.TOTAL_COUNT);
        for (int id = 0; id < UnoCard.TOTAL_COUNT; id++) {
            deck.add(UnoCard.byId(id));
        }
        return deck;
    }

    public static List<UnoCard> shuffled(Random random) {
        List<UnoCard> deck = create();
        Collections.shuffle(deck, random);
        return deck;
    }
}
