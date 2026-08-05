package io.wifi.cards.doudizhu.model;

/**
 * 游戏状态机：
 * WAITING（等待满员）→ DEALING（发牌）→ CALLING（叫分）
 * CALLING →（有人叫 3 分）→ ROBBING（循环抢地主）
 * CALLING →（无人叫 3 分，最高分确定）→ PLAYING
 * ROBBING →（连续 2 人不抢）→ PLAYING
 * PLAYING →（有人出完牌）→ SETTLED
 * SETTLED →（再来一局）→ 重新发牌（回到 DEALING）
 */
public enum DdzGamePhase {
    WAITING,
    DEALING,
    CALLING,
    ROBBING,
    PLAYING,
    SETTLED
}
