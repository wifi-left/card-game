package io.wifi.cards.doudizhu.sound;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * 斗地主语音音效（服务端可达类，不引用任何含 client 的包）。
 * <p>资源命名空间使用 {@value #NS}（仅 a-z0-9_，遵守资源包命名规则），
 * 与模组 id（wifi-card-games，用于网络包/入口）相互独立。</p>
 * <p>语音规则：单张按点数逐张；对子按点数（对 A、对 2…）；其余牌型按类型；
 * 炸弹统一（普通炸弹/含花牌炸弹）；王炸单独；不出、抢地主、叫 1/2/3 分各有语音。</p>
 */
public final class DdzSounds {
    /** 声音资源命名空间（a-z0-9_，不含连字符）。 */
    public static final String NS = "wifi_card_games";

    private DdzSounds() {
    }

    // ---- 单张（每种点数一个声音） ----
    public static final SoundEvent CARD_3 = reg("ddz_card_3");
    public static final SoundEvent CARD_4 = reg("ddz_card_4");
    public static final SoundEvent CARD_5 = reg("ddz_card_5");
    public static final SoundEvent CARD_6 = reg("ddz_card_6");
    public static final SoundEvent CARD_7 = reg("ddz_card_7");
    public static final SoundEvent CARD_8 = reg("ddz_card_8");
    public static final SoundEvent CARD_9 = reg("ddz_card_9");
    public static final SoundEvent CARD_10 = reg("ddz_card_10");
    public static final SoundEvent CARD_J = reg("ddz_card_j");
    public static final SoundEvent CARD_Q = reg("ddz_card_q");
    public static final SoundEvent CARD_K = reg("ddz_card_k");
    public static final SoundEvent CARD_A = reg("ddz_card_a");
    public static final SoundEvent CARD_2 = reg("ddz_card_2");
    public static final SoundEvent CARD_SJOKER = reg("ddz_card_sjoker");
    public static final SoundEvent CARD_BJOKER = reg("ddz_card_bjoker");
    public static final SoundEvent CARD_FLOWER = reg("ddz_card_flower");

    // ---- 对子（对 X，按点数） ----
    public static final SoundEvent PAIR_3 = reg("ddz_pair_3");
    public static final SoundEvent PAIR_4 = reg("ddz_pair_4");
    public static final SoundEvent PAIR_5 = reg("ddz_pair_5");
    public static final SoundEvent PAIR_6 = reg("ddz_pair_6");
    public static final SoundEvent PAIR_7 = reg("ddz_pair_7");
    public static final SoundEvent PAIR_8 = reg("ddz_pair_8");
    public static final SoundEvent PAIR_9 = reg("ddz_pair_9");
    public static final SoundEvent PAIR_10 = reg("ddz_pair_10");
    public static final SoundEvent PAIR_J = reg("ddz_pair_j");
    public static final SoundEvent PAIR_Q = reg("ddz_pair_q");
    public static final SoundEvent PAIR_K = reg("ddz_pair_k");
    public static final SoundEvent PAIR_A = reg("ddz_pair_a");
    public static final SoundEvent PAIR_2 = reg("ddz_pair_2");

    // ---- 牌型语音 ----
    public static final SoundEvent TYPE_TRIPLE = reg("ddz_type_triple");
    public static final SoundEvent TYPE_TRIPLE_ONE = reg("ddz_type_triple_one");
    public static final SoundEvent TYPE_TRIPLE_PAIR = reg("ddz_type_triple_pair");
    public static final SoundEvent TYPE_STRAIGHT = reg("ddz_type_straight");
    public static final SoundEvent TYPE_DOUBLE_STRAIGHT = reg("ddz_type_double_straight");
    public static final SoundEvent TYPE_PLANE = reg("ddz_type_plane");
    public static final SoundEvent TYPE_PLANE_ONE = reg("ddz_type_plane_one");
    public static final SoundEvent TYPE_PLANE_PAIR = reg("ddz_type_plane_pair");
    public static final SoundEvent TYPE_FOUR_TWO = reg("ddz_type_four_two");
    public static final SoundEvent TYPE_FOUR_TWO_PAIRS = reg("ddz_type_four_two_pairs");
    /** 普通炸弹（含含花牌炸弹：花牌+三张同值，如 999+花牌）。 */
    public static final SoundEvent TYPE_BOMB = reg("ddz_type_bomb");
    /** 王炸（大小王）。 */
    public static final SoundEvent TYPE_ROCKET = reg("ddz_type_rocket");
    /** 不出。 */
    public static final SoundEvent TYPE_PASS = reg("ddz_type_pass");

    // ---- 叫分 / 抢地主 ----
    public static final SoundEvent CALL_1 = reg("ddz_call_1");
    public static final SoundEvent CALL_2 = reg("ddz_call_2");
    public static final SoundEvent CALL_3 = reg("ddz_call_3");
    public static final SoundEvent ROB = reg("ddz_rob");

    /** 打牌界面背景音乐（循环播放，音量 0.3）。 */
    public static final SoundEvent BGM = reg("ddz_bgm");

    private static SoundEvent reg(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NS, name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** 触发注册（静态字段初始化即完成；DdzMod.init 调用以确保类加载）。 */
    public static void init() {
    }
}
