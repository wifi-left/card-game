package io.wifi.cards.uno.gui;

import io.wifi.cards.uno.sound.UnoSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * UNO 语音播报（纯客户端）：喊 UNO / 被抓罚牌时播放对应语音。
 * 音频文件由 tools/gen_sound.py（edge-tts）生成。
 */
public final class UnoSoundPlayer {
    private UnoSoundPlayer() {
    }

    /** 喊 UNO：播放"UNO"。 */
    public static void playUno() {
        play(UnoSounds.UNO);
    }

    /** 抓 UNO 成功：播放"罚两张"。 */
    public static void playCatch() {
        play(UnoSounds.CATCH);
    }

    private static void play(SoundEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0F, 1.0F));
        }
    }
}
