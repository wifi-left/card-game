package io.wifi.cards.uno.manager;

import io.wifi.cards.uno.game.UnoGame;
import io.wifi.cards.uno.model.UnoGamePhase;
import io.wifi.cards.uno.network.UnoPackets.NoticeS2C;
import io.wifi.cards.uno.network.UnoPackets.RoomStateS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 一个 UNO 房间（纯内存，最多 {@value #MAX_PLAYERS} 人，动态座位）。
 * members 列表即座位顺序（下标 0 为房主/最早加入者），加入/离开动态增删；
 * botNames 与 members 并行（非空表示该座位是假人，members 对应位为 null）。
 * 开局由房主（座位 0）点击"开始游戏"触发，不是满员自动开。
 */
public class UnoRoom {
    public static final int MAX_PLAYERS = 10;

    public final String id;
    /** 真人成员列表（座位即下标）。 */
    public final List<ServerPlayer> members = new ArrayList<>();
    /** 假人座位名（与 members 并行；非空 = 该座位是假人）。 */
    public final List<String> botNames = new ArrayList<>();
    public UnoGame game;
    /** 结算完成时刻（毫秒），用于空闲房间自动销毁。 */
    public long settledAtMillis = -1;
    /** 旁观者（对局开始后可旁观，只读观看，不占座位）。 */
    public final List<ServerPlayer> spectators = new ArrayList<>();

    public UnoRoom(String id) {
        this.id = id;
    }

    public void addSpectator(ServerPlayer player) {
        if (!spectators.contains(player)) {
            spectators.add(player);
        }
    }

    public void removeSpectator(ServerPlayer player) {
        spectators.remove(player);
    }

    public boolean isBot(int seat) {
        return seat >= 0 && seat < size() && botNames.get(seat) != null;
    }

    public int botCount() {
        int count = 0;
        for (String name : botNames) {
            if (name != null) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return members.size();
    }

    /** 座位显示名（假人用名字，真人用玩家名）。 */
    public String seatName(int seat) {
        if (seat < 0 || seat >= size()) {
            return "";
        }
        if (botNames.get(seat) != null) {
            return botNames.get(seat);
        }
        return members.get(seat) != null ? members.get(seat).getGameProfile().getName() : "";
    }

    /** 加入真人（内部防御：房间最多 10 人，满员时忽略）。 */
    public void addPlayer(ServerPlayer player) {
        if (size() >= MAX_PLAYERS) {
            return;
        }
        members.add(player);
        botNames.add(null);
    }

    /** 加入机器人（内部防御：房间最多 10 人，满员时忽略）。 */
    public void addBot(String name) {
        if (size() >= MAX_PLAYERS) {
            return;
        }
        members.add(null);
        botNames.add(name);
    }

    /** 移除全部假人并压缩座位（保留真人相对顺序）。 */
    public void removeBots() {
        for (int i = botNames.size() - 1; i >= 0; i--) {
            if (botNames.get(i) != null) {
                members.remove(i);
                botNames.remove(i);
            }
        }
    }

    public UnoGamePhase phase() {
        return game == null ? UnoGamePhase.WAITING : game.phase();
    }

    public boolean isFull() {
        return size() >= MAX_PLAYERS;
    }

    public void removePlayer(ServerPlayer player) {
        for (int i = 0; i < size(); i++) {
            if (members.get(i) == player) {
                // 成员与假人座位必须同步移除，否则后续加入的真人会占用 bot 座位导致身份错乱
                members.remove(i);
                botNames.remove(i);
                // 房主模型：座位 0 必须是真人（否则无人能点"开始游戏"，房间卡死在等待中）。
                // 移除的若是最前的真人，把后面第一个真人换到座位 0（机器人保持相对顺序后移）
                if (!members.isEmpty() && members.get(0) == null) {
                    for (int j = 1; j < size(); j++) {
                        if (members.get(j) != null) {
                            Collections.swap(members, 0, j);
                            Collections.swap(botNames, 0, j);
                            break;
                        }
                    }
                }
                return;
            }
        }
    }

    /** 对局中玩家退出：座位转由机器人托管（对局继续），真人不再占座。 */
    public void quitToBot(int seat) {
        if (seat < 0 || seat >= size()) {
            return;
        }
        String name = members.get(seat) != null ? members.get(seat).getGameProfile().getName() : "";
        members.set(seat, null);
        botNames.set(seat, name.isEmpty() ? "机器人" : name + "（托管）");
    }

    /** 是否所有座位都是机器人（含退出游戏转机器人托管的座位），即房间内无在位真人。 */
    public boolean allBot() {
        for (String name : botNames) {
            if (name == null) {
                return false;
            }
        }
        return true;
    }

    /** 是否还有真人玩家（机器人座位不算；退出/断线托管后座位已转机器人）。 */
    public boolean hasRealPlayer() {
        for (ServerPlayer p : members) {
            if (p != null) {
                return true;
            }
        }
        return false;
    }

    public int seatOf(ServerPlayer player) {
        for (int i = 0; i < size(); i++) {
            if (members.get(i) == player) {
                return i;
            }
        }
        return -1;
    }

    /** 重连时用新连接对象替换座位上的旧对象（按 UUID 匹配），返回座位；找不到返回 -1。 */
    public int replacePlayerByUuid(UUID uuid, ServerPlayer newPlayer) {
        for (int i = 0; i < size(); i++) {
            if (members.get(i) != null && members.get(i).getUUID().equals(uuid)) {
                members.set(i, newPlayer);
                return i;
            }
        }
        return -1;
    }

    /** 是否所有玩家都已掉线。 */
    public boolean allDisconnected() {
        for (int i = 0; i < size(); i++) {
            if (isConnected(members.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 同步房间状态给每个成员（mySeat 按接收者区分）与旁观者（mySeat=-1）。 */
    public void broadcastState() {
        String[] names = new String[size()];
        String[] uuids = new String[size()];
        boolean[] conn = new boolean[size()];
        for (int i = 0; i < size(); i++) {
            names[i] = seatName(i);
            uuids[i] = members.get(i) != null ? members.get(i).getUUID().toString() : "";
            // 离线判定：成员引用存在但连接已关闭（1.21.1 无公开断线查询，用 fabric canSend 判定）
            conn[i] = members.get(i) != null && isConnected(members.get(i));
        }
        byte phaseOrdinal = (byte) phase().ordinal();
        for (int i = 0; i < size(); i++) {
            sendToSeat(i, new RoomStateS2C(id, phaseOrdinal, (byte) i, names, uuids, conn));
        }
        for (ServerPlayer sp : spectators) {
            if (isConnected(sp)) {
                ServerPlayNetworking.send(sp, new RoomStateS2C(id, phaseOrdinal, (byte) -1, names, uuids, conn));
            }
        }
    }

    public void broadcast(CustomPacketPayload payload) {
        for (int i = 0; i < size(); i++) {
            ServerPlayer p = members.get(i);
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
        if (seat >= 0 && seat < size()) {
            ServerPlayer p = members.get(seat);
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

    /** 玩家连接是否仍可用（1.21.1 无公开的断线查询方法，用 fabric 的 canSend 判定）。 */
    public static boolean isConnected(ServerPlayer player, CustomPacketPayload payload) {
        return player != null && ServerPlayNetworking.canSend(player, payload.type());
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, NoticeS2C.TYPE);
    }
}
