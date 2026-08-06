package io.wifi.cards.board.manager;

import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.NoticeS2C;
import io.wifi.cards.board.network.BoardPackets.RoomStateS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一个棋类房间（纯内存，最多 2 人）。
 * count 即成员数（座位 0 = 黑方先手，座位 1 = 白方）；游戏开始后成员不再变动
 * （对局中退出会被转机器人托管，见 {@link #quitToBot(int)}）。
 * 调试假人：botNames[seat] 非空表示该座位是假人（members[seat] 为 null），
 * 假人由各游戏状态的托管逻辑自动行动。
 */
public class BoardRoom {
    public final String id;
    public final BoardGameType gameType;
    /** 棋盘边长（黑白棋 8 / 五子棋 15 / 围棋 9 或 19）。 */
    public final int size;
    public final ServerPlayer[] members = new ServerPlayer[2];
    /** 假人座位名（非空 = 该座位是调试假人，无真实连接）。 */
    public final String[] botNames = new String[2];
    /** 成员（真人 + 假人）数量，即座位数。 */
    public int count = 0;
    public BoardGame game;
    /** 结算完成时刻（毫秒），用于空闲房间自动销毁。 */
    public long settledAtMillis = -1;
    /** 旁观者（对局开始后可旁观，只读观看，不占座位）。 */
    public final List<ServerPlayer> spectators = new ArrayList<>();

    public BoardRoom(String id, BoardGameType gameType, int size) {
        this.id = id;
        this.gameType = gameType;
        this.size = size;
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
        return seat >= 0 && seat < 2 && botNames[seat] != null;
    }

    public int botCount() {
        int n = 0;
        for (int i = 0; i < 2; i++) {
            if (botNames[i] != null) {
                n++;
            }
        }
        return n;
    }

    /** 座位显示名（假人用名字，真人用玩家名）。 */
    public String seatName(int seat) {
        if (seat < 0 || seat >= 2) {
            return "";
        }
        if (botNames[seat] != null) {
            return botNames[seat];
        }
        return members[seat] != null ? members[seat].getGameProfile().getName() : "";
    }

    /** 加入真人（内部防御：房间最多 2 人，满员时忽略）。 */
    public void addPlayer(ServerPlayer player) {
        if (count >= 2) {
            return;
        }
        members[count++] = player;
    }

    /** 加入机器人（内部防御：房间最多 2 人，满员时忽略）。 */
    public void addBot(String name) {
        if (count >= 2) {
            return;
        }
        botNames[count++] = name;
    }

    /** 移除全部假人并压缩座位（保留真人相对顺序）。 */
    public void removeBots() {
        int write = 0;
        for (int read = 0; read < count; read++) {
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
        count = write;
    }

    public BoardPhase phase() {
        return game == null ? BoardPhase.WAITING : game.phase();
    }

    public boolean isFull() {
        return count >= 2;
    }

    public void removePlayer(ServerPlayer player) {
        for (int i = 0; i < count; i++) {
            if (members[i] == player) {
                // 成员与假人座位必须同步压缩，否则后续加入的真人会占用 bot 座位导致身份错乱
                System.arraycopy(members, i + 1, members, i, count - i - 1);
                System.arraycopy(botNames, i + 1, botNames, i, count - i - 1);
                members[count - 1] = null;
                botNames[count - 1] = null;
                count--;
                return;
            }
        }
    }

    /** 对局中玩家退出：座位转由机器人托管（对局继续），真人不再占座。 */
    public void quitToBot(int seat) {
        if (seat < 0 || seat >= count) {
            return;
        }
        String name = members[seat] != null ? members[seat].getGameProfile().getName() : "";
        members[seat] = null;
        botNames[seat] = name.isEmpty() ? "机器人" : name + "（托管）";
    }

    /** 是否所有座位都是机器人（含退出游戏转机器人托管的座位），即房间内无在位真人。 */
    public boolean allBot() {
        for (int i = 0; i < count; i++) {
            if (botNames[i] == null) {
                return false;
            }
        }
        return true;
    }

    /** 是否还有真人玩家（机器人座位不算；退出/断线托管后座位已转机器人）。 */
    public boolean hasRealPlayer() {
        for (int i = 0; i < count; i++) {
            if (members[i] != null) {
                return true;
            }
        }
        return false;
    }

    public int seatOf(ServerPlayer player) {
        for (int i = 0; i < count; i++) {
            if (members[i] == player) {
                return i;
            }
        }
        return -1;
    }

    /** 重连时用新连接对象替换座位上的旧对象（按 UUID 匹配），返回座位；找不到返回 -1。 */
    public int replacePlayerByUuid(UUID uuid, ServerPlayer newPlayer) {
        for (int i = 0; i < count; i++) {
            if (members[i] != null && members[i].getUUID().equals(uuid)) {
                members[i] = newPlayer;
                return i;
            }
        }
        return -1;
    }

    /** 是否所有玩家都已掉线。 */
    public boolean allDisconnected() {
        for (int i = 0; i < count; i++) {
            if (isConnected(members[i])) {
                return false;
            }
        }
        return true;
    }

    /** 同步房间状态给每个成员（mySeat 按接收者区分）与旁观者（mySeat=-1）。 */
    public void broadcastState() {
        String[] names = new String[2];
        String[] uuids = new String[2];
        boolean[] conn = new boolean[2];
        for (int i = 0; i < 2; i++) {
            names[i] = seatName(i);
            uuids[i] = members[i] != null ? members[i].getUUID().toString() : "";
            conn[i] = members[i] != null;
        }
        byte phaseOrdinal = (byte) phase().ordinal();
        byte gameTypeOrdinal = (byte) gameType.ordinal();
        for (int i = 0; i < count; i++) {
            sendToSeat(i, new RoomStateS2C(id, gameTypeOrdinal, (byte) size, phaseOrdinal, (byte) i, names, uuids, conn));
        }
        for (ServerPlayer sp : spectators) {
            if (isConnected(sp)) {
                ServerPlayNetworking.send(sp, new RoomStateS2C(id, gameTypeOrdinal, (byte) size, phaseOrdinal,
                        (byte) -1, names, uuids, conn));
            }
        }
    }

    public void broadcast(CustomPacketPayload payload) {
        for (int i = 0; i < count; i++) {
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
        if (seat >= 0 && seat < count) {
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

    /** 玩家连接是否仍可用（1.21.1 无公开的断线查询方法，用 fabric 的 canSend 判定）。 */
    public static boolean isConnected(ServerPlayer player, CustomPacketPayload payload) {
        return player != null && ServerPlayNetworking.canSend(player, payload.type());
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, NoticeS2C.TYPE);
    }
}
