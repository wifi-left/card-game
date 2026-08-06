package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.common.Room;
import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
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
public class DdzRoom extends Room {
    public final boolean flowerMode;
    public final DdzRuleSet ruleSet;
    public final ServerPlayer[] members = new ServerPlayer[3];
    /** 假人座位名（非空 = 该座位是调试假人，无真实连接）。 */
    public final String[] botNames = new String[3];
    public int size = 0;
    public DdzGame game;

    public DdzRoom(String id, boolean flowerMode, DdzRuleSet ruleSet, boolean announce) {
        super(id, announce);
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

    /** 加入真人（内部防御：房间最多 3 人，满员时忽略）。 */
    public void addPlayer(ServerPlayer player) {
        if (size >= 3) {
            return;
        }
        members[size++] = player;
    }

    /** 加入机器人（内部防御：房间最多 3 人，满员时忽略）。 */
    public void addBot(String name) {
        if (size >= 3) {
            return;
        }
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

    /** 对局中玩家退出：座位转由机器人托管（对局继续），真人不再占座。 */
    public void quitToBot(int seat) {
        if (seat < 0 || seat >= size) {
            return;
        }
        String name = members[seat] != null ? members[seat].getGameProfile().getName() : "";
        members[seat] = null;
        botNames[seat] = name.isEmpty() ? "机器人" : name + "（托管）";
    }

    /** 是否所有座位都是机器人（含退出游戏转机器人托管的座位），即房间内无在位真人。 */
    public boolean allBot() {
        for (int i = 0; i < size; i++) {
            if (botNames[i] == null) {
                return false;
            }
        }
        return true;
    }

    /** 是否还有真人玩家（机器人座位不算；退出/断线托管后座位已转机器人）。 */
    public boolean hasRealPlayer() {
        for (int i = 0; i < size; i++) {
            if (members[i] != null) {
                return true;
            }
        }
        return false;
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

    /**
     * 是否所有玩家都已掉线。
     * 在线判定用「服务器玩家列表查询」（carpet 假人/调试实体无真实网络连接，
     * canSend 恒为 false，会被误判为离线——它们实体在线且参与对局，应视为在线）。
     */
    public boolean allDisconnected() {
        for (int i = 0; i < size; i++) {
            if (isOnline(members[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 玩家是否在线（在服务器玩家列表中；carpet 假人无网络连接但实体在线，返回 true）。
     * 服务器玩家列表查询（{@code server.getPlayerList().getPlayer(uuid)}）。
     */
    public static boolean isOnline(ServerPlayer player) {
        return player != null && player.server != null
                && player.server.getPlayerList().getPlayer(player.getUUID()) != null;
    }

    /** 同步房间状态给每个成员（mySeat 按接收者区分）与旁观者（mySeat=-1）。 */
    public void broadcastState() {
        String[] names = new String[3];
        String[] uuids = new String[3];
        boolean[] conn = new boolean[3];
        for (int i = 0; i < 3; i++) {
            names[i] = seatName(i);
            uuids[i] = members[i] != null ? members[i].getUUID().toString() : "";
            conn[i] = members[i] != null && isConnected(members[i]);
        }
        byte phaseOrdinal = (byte) phase().ordinal();
        byte ruleSetOrdinal = (byte) ruleSet.ordinal();
        for (int i = 0; i < size; i++) {
            sendToSeat(i, new RoomStateS2C(id, flowerMode, phaseOrdinal, ruleSetOrdinal, (byte) i, names, uuids, conn));
        }
        for (ServerPlayer sp : spectators) {
            if (isConnected(sp)) {
                ServerPlayNetworking.send(sp, new RoomStateS2C(id, flowerMode, phaseOrdinal, ruleSetOrdinal,
                        (byte) -1, names, uuids, conn));
            }
        }
    }

    public void broadcast(CustomPacketPayload payload) {
        for (int i = 0; i < size; i++) {
            ServerPlayer p = members[i];
            if (isConnected(p, payload)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
        for (ServerPlayer sp : spectators) {
            if (isConnected(sp, payload)) {
                ServerPlayNetworking.send(sp, payload);
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

    /** 单独发给指定旁观者。 */
    public void sendToSpectator(ServerPlayer spectator, CustomPacketPayload payload) {
        if (isConnected(spectator, payload)) {
            ServerPlayNetworking.send(spectator, payload);
        }
    }
}
