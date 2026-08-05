package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.rule.DdzCardType;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

/**
 * 斗地主语音播报（纯客户端）：按牌型/点数选择语音并播放。
 * <ul>
 *   <li>单张：每种点数一个声音（3~2、小王、大王、花牌）</li>
 *   <li>对子：对 X 一个声音（对 3 ~ 对 2）</li>
 *   <li>其余牌型：按类型语音；炸弹（含软炸弹）统一、王炸单独</li>
 *   <li>不出、抢地主、叫 1/2/3 分：各有语音</li>
 * </ul>
 */
public final class DdzSoundPlayer {
    private DdzSoundPlayer() {
    }

    /** 出牌语音（cards 取第一张用于单张/对子的点数判断）。 */
    public static void playPlay(DdzCardType type, List<DdzCard> cards) {
        SoundEvent event = switch (type) {
            case SINGLE -> singleSound(cards.isEmpty() ? 0 : cards.get(0).rankValue());
            case PAIR -> pairSound(cards.isEmpty() ? 0 : cards.get(0).rankValue());
            case TRIPLE -> DdzSounds.TYPE_TRIPLE;
            case TRIPLE_WITH_ONE -> DdzSounds.TYPE_TRIPLE_ONE;
            case TRIPLE_WITH_PAIR -> DdzSounds.TYPE_TRIPLE_PAIR;
            case STRAIGHT -> DdzSounds.TYPE_STRAIGHT;
            case DOUBLE_STRAIGHT -> DdzSounds.TYPE_DOUBLE_STRAIGHT;
            case PLANE -> DdzSounds.TYPE_PLANE;
            case PLANE_WITH_SINGLES -> DdzSounds.TYPE_PLANE_ONE;
            case PLANE_WITH_PAIRS -> DdzSounds.TYPE_PLANE_PAIR;
            case FOUR_WITH_TWO_SINGLES -> DdzSounds.TYPE_FOUR_TWO;
            case FOUR_WITH_TWO_PAIRS -> DdzSounds.TYPE_FOUR_TWO_PAIRS;
            case BOMB, SOFT_BOMB -> DdzSounds.TYPE_BOMB;
            case ROCKET -> DdzSounds.TYPE_ROCKET;
            case PASS -> DdzSounds.TYPE_PASS;
        };
        play(event);
    }

    /** 不出。 */
    public static void playPass() {
        play(DdzSounds.TYPE_PASS);
    }

    /** 叫分（score 1/2/3；0 不叫无语音）。 */
    public static void playCall(int score) {
        SoundEvent event = switch (score) {
            case 1 -> DdzSounds.CALL_1;
            case 2 -> DdzSounds.CALL_2;
            case 3 -> DdzSounds.CALL_3;
            default -> null;
        };
        if (event != null) {
            play(event);
        }
    }

    /** 抢地主。 */
    public static void playRob() {
        play(DdzSounds.ROB);
    }

    private static SoundEvent singleSound(int rank) {
        return switch (rank) {
            case 3 -> DdzSounds.CARD_3;
            case 4 -> DdzSounds.CARD_4;
            case 5 -> DdzSounds.CARD_5;
            case 6 -> DdzSounds.CARD_6;
            case 7 -> DdzSounds.CARD_7;
            case 8 -> DdzSounds.CARD_8;
            case 9 -> DdzSounds.CARD_9;
            case 10 -> DdzSounds.CARD_10;
            case 11 -> DdzSounds.CARD_J;
            case 12 -> DdzSounds.CARD_Q;
            case 13 -> DdzSounds.CARD_K;
            case 14 -> DdzSounds.CARD_A;
            case 15 -> DdzSounds.CARD_2;
            case 16 -> DdzSounds.CARD_SJOKER;
            case 17 -> DdzSounds.CARD_BJOKER;
            case 18 -> DdzSounds.CARD_FLOWER;
            default -> DdzSounds.TYPE_TRIPLE; // 兜底（理论不会发生）
        };
    }

    private static SoundEvent pairSound(int rank) {
        return switch (rank) {
            case 3 -> DdzSounds.PAIR_3;
            case 4 -> DdzSounds.PAIR_4;
            case 5 -> DdzSounds.PAIR_5;
            case 6 -> DdzSounds.PAIR_6;
            case 7 -> DdzSounds.PAIR_7;
            case 8 -> DdzSounds.PAIR_8;
            case 9 -> DdzSounds.PAIR_9;
            case 10 -> DdzSounds.PAIR_10;
            case 11 -> DdzSounds.PAIR_J;
            case 12 -> DdzSounds.PAIR_Q;
            case 13 -> DdzSounds.PAIR_K;
            case 14 -> DdzSounds.PAIR_A;
            case 15 -> DdzSounds.PAIR_2;
            default -> DdzSounds.TYPE_DOUBLE_STRAIGHT; // 对王/花牌对兜底
        };
    }

    private static void play(SoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F));
        }
    }
}
