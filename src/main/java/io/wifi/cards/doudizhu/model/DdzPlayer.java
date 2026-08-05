package io.wifi.cards.doudizhu.model;

import io.wifi.cards.doudizhu.card.DdzCard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 对局中的一名玩家（服务端权威状态）。 */
public class DdzPlayer {
    private final UUID uuid;
    private final String name;
    private final int seat;
    private final List<DdzCard> hand = new ArrayList<>();
    private boolean trusted;
    private boolean connected = true;
    private boolean landlord;

    public DdzPlayer(UUID uuid, String name, int seat) {
        this.uuid = uuid;
        this.name = name;
        this.seat = seat;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public int seat() {
        return seat;
    }

    public List<DdzCard> hand() {
        return hand;
    }

    public boolean trusted() {
        return trusted;
    }

    public void setTrusted(boolean trusted) {
        this.trusted = trusted;
    }

    public boolean connected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean landlord() {
        return landlord;
    }

    public void setLandlord(boolean landlord) {
        this.landlord = landlord;
    }
}
