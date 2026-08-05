package io.wifi.cards.doudizhu.card;

import io.wifi.cards.doudizhu.model.DdzGameMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 牌堆：经典 54 张（含大小王） / 花牌 55 张（54 + 1 张花牌）。 */
public final class DdzDeck {
    private DdzDeck() {
    }

    public static List<DdzCard> create(DdzGameMode mode) {
        List<DdzCard> deck = new ArrayList<>(55);
        for (int id = 0; id < 54; id++) {
            deck.add(DdzCard.byId(id));
        }
        if (mode == DdzGameMode.FLOWER) {
            deck.add(DdzCard.flower());
        }
        return deck;
    }

    public static List<DdzCard> shuffled(DdzGameMode mode, Random random) {
        List<DdzCard> deck = create(mode);
        Collections.shuffle(deck, random);
        return deck;
    }
}
