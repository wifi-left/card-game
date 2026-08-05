package io.wifi.cards.doudizhu.model;

/** 游戏模式：经典（54 张）/ 花牌（55 张，含万能牌）。 */
public enum DdzGameMode {
    CLASSIC("经典"),
    FLOWER("花牌");

    private final String displayName;

    DdzGameMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
