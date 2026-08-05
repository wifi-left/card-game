package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomStateS2C;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 一个斗地主房间（纯内存，最多 3 人）。
 * members 数组即座位顺序；游戏开始后成员不再变动（游戏中进行离开会被拒绝）。
 * 模式（经典/花牌）与规则集（标准/民间）在创建房间时由房主设定，本局内不变。
 * 调试假人：botNames[seat] 非空表示该座位是假人（members[seat] 为 null），
 * 假人由 DdzGame 的托管逻辑自动行动。
 */
public class DdzRoom {
    public final String id;
    public final boolean flowerMode;
    public final DdzRuleSet ruleSet;
    public final ServerPlayer[] members = new ServerPlayer[3];
    /** 假人座位名（非空 = 该座位是调试假人，无真实连接）。 */
    public final String[] botNames = new String[3];
    public int size = 0;
    public DdzGame game;
    /** 结算完成时刻（毫秒），用于空闲房间自动销毁。 */
    public long settledAtMillis = -1;

    public DdzRoom(String id, boolean flowerMode, DdzRuleSet ruleSet) {
        this.id = id;
        this.flowerMode = flowerMode;
        this.ruleSet = ruleSet;
    }

    public boolean isBot(int seat) {
        return seat >= 0 && seat < 3 && botNames[seat] != null;
    }

    public int botCount() {
        int count = 0;
        for (int i = 0; i < 3; i++) {
            if (botNames[i] != null) {
                count++;
            }
        }
        return count;
    }

    /** 座位显示名（假人用名字，真人用玩家名）。 */
    public String seatName(int seat) {
        if (seat < 0 || seat >= 3) {
            return "";
        }
        if (botNames[seat] != null) {
            return botNames[seat];
        }
        return members[seat] != null ? members[seat].getGameProfile().getName() : "";
    }

    public void addPlayer(ServerPlayer player) {
        members[size++] = player;
    }

    /** 在下一个空座位放置调试假人（假人自动托管行动）。 */
    public void addBot(String name) {
        botNames[size++] = name;
    }

    /** 移除全部假人并压缩座位（保留真人相对顺序）。 */
    public void removeBots() {
        int write = 0;
        for (int read = 0; read < size; read++) {
            if (botNames[read] == null) {
                if (write != read) {
                    members[write] = members[read];
                    members[read] = null;
                }
                write++;
            } else {
                members[read] = null;
            }
            botNames[read] = null;
        }
        size = write;
    }

    public DdzGamePhase phase() {
        return game == null ? DdzGamePhase.WAITING : game.phase();
    }

    public boolean isFull() {
        return size >= 3;
    }

    public void removePlayer(ServerPlayer player) {
        for (int i = 0; i < size; i++) {
            if (members[i] == player) {
                // 成员与假人座位必须同步压缩，否则后续加入的真人会占用 bot 座位导致身份错乱
                System.arraycopy(members, i + 1, members, i, size - i - 1);
                System.arraycopy(botNames, i + 1, botNames, i, size - i - 1);
                members[size - 1] = null;
                botNames[size - 1] = null;
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

    /** 重连时用新连接对象替换座位上的旧对象（按 UUID 匹配），返回座位；找不到返回 -1。 */
    public int replacePlayerByUuid(UUID uuid, ServerPlayer newPlayer) {
        for (int i = 0; i < size; i++) {
            if (members[i] != null && members[i].getUUID().equals(uuid)) {
                members[i] = newPlayer;
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
        String[] uuids = new String[3];
        boolean[] conn = new boolean[3];
        for (int i = 0; i < 3; i++) {
            names[i] = seatName(i);
            uuids[i] = members[i] != null ? members[i].getUUID().toString() : "";
            conn[i] = members[i] != null;
        }
        byte phaseOrdinal = (byte) phase().ordinal();
        byte ruleSetOrdinal = (byte) ruleSet.ordinal();
        for (int i = 0; i < size; i++) {
            sendToSeat(i, new RoomStateS2C(id, flowerMode, phaseOrdinal, ruleSetOrdinal, (byte) i, names, uuids, conn));
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
