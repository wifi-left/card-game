package io.wifi.cards.uno.rule;

import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoColor;
import io.wifi.cards.uno.card.UnoDeck;
import io.wifi.cards.uno.card.UnoValue;
import io.wifi.cards.uno.game.UnoGame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UNO 规则测试：牌堆构成、可打判定、座位推进（跳过/反转/罚牌方向）。 */
class UnoRuleTest {

    /** 找到第一张符合颜色与牌面的牌（测试用）。 */
    private static UnoCard card(UnoColor color, UnoValue value) {
        for (UnoCard c : UnoDeck.create()) {
            if (c.color() == color && c.value() == value) {
                return c;
            }
        }
        throw new IllegalStateException("牌堆中不存在 " + color + " " + value);
    }

    @Test
    void deckHas108CardsWithCorrectComposition() {
        List<UnoCard> deck = UnoDeck.create();
        assertEquals(108, deck.size());
        assertEquals(108, deck.stream().map(UnoCard::id).distinct().count(), "牌 id 不得重复");
        // 四色各 25 张：0 一张、1~9 各两张、跳过/反转/+2 各两张
        for (UnoColor color : new UnoColor[]{UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE}) {
            long colored = deck.stream().filter(c -> c.color() == color).count();
            assertEquals(25, colored, color + " 应有 25 张");
            assertEquals(1, deck.stream().filter(c -> c.color() == color && c.value() == UnoValue.ZERO).count());
            for (UnoValue v : new UnoValue[]{UnoValue.ONE, UnoValue.TWO, UnoValue.THREE, UnoValue.FOUR,
                    UnoValue.FIVE, UnoValue.SIX, UnoValue.SEVEN, UnoValue.EIGHT, UnoValue.NINE,
                    UnoValue.SKIP, UnoValue.REVERSE, UnoValue.DRAW2}) {
                assertEquals(2, deck.stream().filter(c -> c.color() == color && c.value() == v).count(),
                        color + " " + v + " 应有 2 张");
            }
        }
        // 万能 4 张、万能+4 4 张
        assertEquals(4, deck.stream().filter(c -> c.value() == UnoValue.WILD).count());
        assertEquals(4, deck.stream().filter(c -> c.value() == UnoValue.WILD4).count());
    }

    @Test
    void canPlayByColorOrValueOrWild() {
        UnoCard red5 = card(UnoColor.RED, UnoValue.FIVE);
        UnoCard yellow5 = card(UnoColor.YELLOW, UnoValue.FIVE);
        UnoCard yellow8 = card(UnoColor.YELLOW, UnoValue.EIGHT);
        UnoCard wild = card(UnoColor.NONE, UnoValue.WILD);
        // 顶牌红5：同色红牌可打、同点数黄5可打、不同色不同点数不可打
        assertTrue(UnoGame.canPlay(card(UnoColor.RED, UnoValue.THREE), red5, UnoColor.RED));
        assertTrue(UnoGame.canPlay(yellow5, red5, UnoColor.RED));
        assertFalse(UnoGame.canPlay(yellow8, red5, UnoColor.RED));
        // 万能牌任意可打
        assertTrue(UnoGame.canPlay(wild, red5, UnoColor.RED));
        assertTrue(UnoGame.canPlay(card(UnoColor.NONE, UnoValue.WILD4), yellow8, UnoColor.GREEN));
    }

    @Test
    void wildChosenColorApplies() {
        UnoCard red3 = card(UnoColor.RED, UnoValue.THREE);
        UnoCard blue9 = card(UnoColor.BLUE, UnoValue.NINE);
        UnoCard yellow9 = card(UnoColor.YELLOW, UnoValue.NINE);
        // 万能牌选了蓝色后：只有蓝色牌可打（点数匹配对万能牌不生效）
        assertTrue(UnoGame.canPlay(blue9, card(UnoColor.NONE, UnoValue.WILD), UnoColor.BLUE));
        assertFalse(UnoGame.canPlay(yellow9, card(UnoColor.NONE, UnoValue.WILD), UnoColor.BLUE));
        assertFalse(UnoGame.canPlay(red3, card(UnoColor.NONE, UnoValue.WILD), UnoColor.BLUE));
        // 万能牌选色后，普通顶牌照常按颜色/点数匹配
        UnoCard blue4 = card(UnoColor.BLUE, UnoValue.FOUR);
        assertTrue(UnoGame.canPlay(yellow9, blue9, UnoColor.BLUE)); // 同点数 9
        assertFalse(UnoGame.canPlay(red3, blue4, UnoColor.BLUE));
    }

    @Test
    void seatAdvanceWithDirection() {
        assertEquals(1, UnoGame.nextSeat(0, 1, 4));
        assertEquals(2, UnoGame.nextSeat(1, 1, 4));
        assertEquals(3, UnoGame.nextSeat(2, 1, 4));
        assertEquals(0, UnoGame.nextSeat(3, 1, 4));
        // 反向
        assertEquals(2, UnoGame.nextSeat(3, -1, 4));
        assertEquals(3, UnoGame.nextSeat(0, -1, 4));
    }

    @Test
    void skipSkipsNextPlayer() {
        // 跳过：下家被跳过，轮到下下家（4 人局：0 打跳过 → 轮到 2）
        assertEquals(2, UnoGame.nextSeat(UnoGame.nextSeat(0, 1, 4), 1, 4));
    }

    @Test
    void reverseInTwoPlayerActsAsSkip() {
        // 2 人局反转=跳过对方：0 打反转 → 仍然轮到 0
        int skipOpponent = UnoGame.nextSeat(UnoGame.nextSeat(0, 1, 2), 1, 2);
        assertEquals(0, skipOpponent);
    }

    @Test
    void penaltyTargetAndNextSeat() {
        // +2/+4：下家被罚并跳过，再下家行动（4 人局：0 打 +2 → 1 被罚 → 轮到 2）
        int victim = UnoGame.nextSeat(0, 1, 4);
        assertEquals(1, victim);
        assertEquals(2, UnoGame.nextSeat(victim, 1, 4));
    }

    @Test
    void twoPlayerDraw2LoopsBack() {
        // 2 人局 +2：对方被罚后轮到出牌者自己
        int victim = UnoGame.nextSeat(0, 1, 2);
        assertEquals(1, victim);
        assertEquals(0, UnoGame.nextSeat(victim, 1, 2));
    }

    @Test
    void cardDisplayAndWild() {
        assertTrue(card(UnoColor.NONE, UnoValue.WILD).isWild());
        assertTrue(card(UnoColor.NONE, UnoValue.WILD4).isWild());
        assertFalse(card(UnoColor.RED, UnoValue.SKIP).isWild());
        assertEquals("红5", card(UnoColor.RED, UnoValue.FIVE).display());
        assertEquals("万能", card(UnoColor.NONE, UnoValue.WILD).display());
        assertEquals("万能+4", card(UnoColor.NONE, UnoValue.WILD4).display());
        assertEquals("黄+2", card(UnoColor.YELLOW, UnoValue.DRAW2).display());
    }
}
