package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomStateS2C;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一个斗地主房间（纯内存，最多 3 人）。
 * members 数组即座位顺序；游戏开始后成员不再变动（游戏中进行离开会被拒绝）。
 * 模式（经典/花牌）与规则集（标准/民间）在创建房间时由房主设定，本局内不变。
 */
public class DdzRoom {
    public final String id;
    public final boolean flowerMode;
    public final DdzRuleSet ruleSet;
    public final ServerPlayer[] members = new ServerPlayer[3];
    public int size = 0;
    public DdzGame game;
    /** 结算完成时刻（毫秒），用于空闲房间自动销毁。 */
    public long settledAtMillis = -1;

    public DdzRoom(String id, boolean flowerMode, DdzRuleSet ruleSet) {
        this.id = id;
        this.flowerMode = flowerMode;
        this.ruleSet = ruleSet;
    }

    public DdzGamePhase phase() {
        return game == null ? DdzGamePhase.WAITING : game.phase();
    }

    public boolean isFull() {
        return size >= 3;
    }

    public void addPlayer(ServerPlayer player) {
        members[size++] = player;
    }

    public void removePlayer(ServerPlayer player) {
        for (int i = 0; i < size; i++) {
            if (members[i] == player) {
                System.arraycopy(members, i + 1, members, i, size - i - 1);
                members[size - 1] = null;
                size--;
                return;
            }
        }
    }

    public int seatOf(ServerPlayer player) {
        for (int i = 0; i < size; i++) {
            if (members[i] == player) {
                return i;
            }
        }
        return -1;
    }

    /** 是否所有玩家都已掉线。 */
    public boolean allDisconnected() {
        for (int i = 0; i < size; i++) {
            if (isConnected(members[i])) {
                return false;
            }
        }
        return true;
    }

    /** 同步房间状态给每个成员（mySeat 按接收者区分）。 */
    public void broadcastState() {
        String[] names = new String[3];
        boolean[] conn = new boolean[3];
        for (int i = 0; i < 3; i++) {
            if (members[i] != null) {
                names[i] = members[i].getGameProfile().getName();
                conn[i] = true;
            } else {
                names[i] = "";
            }
        }
        byte phaseOrdinal = (byte) phase().ordinal();
        byte ruleSetOrdinal = (byte) ruleSet.ordinal();
        for (int i = 0; i < size; i++) {
            sendToSeat(i, new RoomStateS2C(id, flowerMode, phaseOrdinal, ruleSetOrdinal, (byte) i, names, conn));
        }
    }

    public void broadcast(CustomPacketPayload payload) {
        for (int i = 0; i < size; i++) {
            ServerPlayer p = members[i];
            if (isConnected(p, payload)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public void sendToSeat(int seat, CustomPacketPayload payload) {
        if (seat >= 0 && seat < size) {
            ServerPlayer p = members[seat];
            if (isConnected(p, payload)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** 玩家连接是否仍可用（1.21.1 无公开的断线查询方法，用 fabric 的 canSend 判定）。 */
    public static boolean isConnected(ServerPlayer player, CustomPacketPayload payload) {
        return player != null && ServerPlayNetworking.canSend(player, payload.type());
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, NoticeS2C.TYPE);
    }
}
