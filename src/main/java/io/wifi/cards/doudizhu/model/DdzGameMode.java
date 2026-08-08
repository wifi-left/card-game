package io.wifi.cards.doudizhu.model;

/** 游戏模式：经典（54 张）/ 花牌（55 张，含万能牌）。 */
public enum DdzGameMode {
    CLASSIC("wifi_card_games.ddz.mode.classic"),
    FLOWER("wifi_card_games.ddz.mode.flower");

    private final String displayNameKey;

    DdzGameMode(String displayNameKey) {
        this.displayNameKey = displayNameKey;
    }

    /** 模式显示名翻译键。 */
    public String displayName() {
        return displayNameKey;
    }
}
