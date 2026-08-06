package io.wifi.cards.uno.sound;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * UNO 语音音效（服务端可达类，不引用任何含 client 的包）。
 * <p>资源命名空间与斗地主共用 {@value #NS}（仅 a-z0-9_，遵守资源包命名规则）。</p>
 * <p>语音规则：喊 UNO 播"UNO"；被抓罚牌播"罚两张"。音频由 tools/gen_sound.py
 * （edge-tts）生成，源文本见 tools/input_uno.txt。</p>
 */
public final class UnoSounds {
    /** 声音资源命名空间（a-z0-9_，不含连字符；与斗地主共用）。 */
    public static final String NS = "wifi_card_games";

    private UnoSounds() {
    }

    /** 喊 UNO。 */
    public static final SoundEvent UNO = reg("uno_uno");
    /** 被抓罚两张（抓 UNO 成功）。 */
    public static final SoundEvent CATCH = reg("uno_catch");

    private static SoundEvent reg(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NS, name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** 触发注册（静态字段初始化即完成；UnoMod.init 调用以确保类加载）。 */
    public static void init() {
    }
}
