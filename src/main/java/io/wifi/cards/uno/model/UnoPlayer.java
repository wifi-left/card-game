package io.wifi.cards.uno.model;

import io.wifi.cards.uno.card.UnoCard;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端对局玩家状态（座位即列表下标，0~9）。
 * 手牌对客户端保密：网络只下发自己的手牌，其余玩家只见剩余张数。
 */
public class UnoPlayer {
    private final java.util.UUID uuid;
    private final String name;
    private final int seat;
    private final List<UnoCard> hand = new ArrayList<>();
    /** 本局是否已喊过 UNO（剩 1 张时须喊，未喊可被抓罚 2 张）。 */
    private boolean declaredUno;
    /** 托管：断线/主动托管由机器人引擎自动行动（假人座位视为常驻托管）。 */
    private boolean trusted;

    public UnoPlayer(java.util.UUID uuid, String name, int seat) {
        this.uuid = uuid;
        this.name = name;
        this.seat = seat;
    }

    public java.util.UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public int seat() {
        return seat;
    }

    public List<UnoCard> hand() {
        return hand;
    }

    public boolean declaredUno() {
        return declaredUno;
    }

    public void setDeclaredUno(boolean declaredUno) {
        this.declaredUno = declaredUno;
    }

    public boolean trusted() {
        return trusted;
    }

    public void setTrusted(boolean trusted) {
        this.trusted = trusted;
    }
}
