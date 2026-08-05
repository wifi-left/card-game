package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomClosedS2C;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局内存管理器（单例）：所有活跃房间 + 玩家所属房间映射。
 * <p>纯内存存储：服务器重启即全部清空，无任何持久化。</p>
 * <p>生命周期：WAITING（满 3 人自动开局）→ 对局 → SETTLED（60 秒无操作自动销毁 /
 * 有人离开则解散；"再来一局"保持房间不散直接重开）。</p>
 */
public final class DdzMemoryManager {
    public static final DdzMemoryManager INSTANCE = new DdzMemoryManager();

    /** 结算后无人操作，保留的 tick 数（60 秒）。 */
    private static final long SETTLED_KEEP_MS = 60_000;

    /** 房间码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Random RANDOM = new Random();

    private final Map<String, DdzRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomIds = new ConcurrentHashMap<>();

    private DdzMemoryManager() {
    }

    // ---------------- 房间操作 ----------------

    public void createRoom(ServerPlayer player, boolean flowerMode, DdzRuleSet ruleSet) {
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        DdzRoom room = new DdzRoom(generateCode(), flowerMode, ruleSet);
        room.addPlayer(player);
        rooms.put(room.id, room);
        playerRoomIds.put(player.getUUID(), room.id);
        room.broadcastState();
    }

    public void joinRoom(ServerPlayer player, String code) {
        DdzRoom room = rooms.get(code.toUpperCase().trim());
        if (room == null) {
            error(player, "房间不存在：" + code);
            return;
        }
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        if (room.isFull()) {
            error(player, "房间已满");
            return;
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            error(player, "游戏已经开始，无法加入");
            return;
        }
        room.addPlayer(player);
        playerRoomIds.put(player.getUUID(), room.id);
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
    }

    public void leaveRoom(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase != DdzGamePhase.WAITING && phase != DdzGamePhase.SETTLED) {
            error(player, "对局进行中，不能离开房间");
            return;
        }
        removeFromRoom(player, room, true);
    }

    /** 再来一局：结算后重置房间状态并重新发牌（房间不散）。 */
    public void nextGame(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room == null || room.game == null || room.phase() != DdzGamePhase.SETTLED) {
            return;
        }
        room.settledAtMillis = -1;
        room.game.start();
    }

    // ---------------- 对局操作转发 ----------------

    public void onCall(ServerPlayer player, byte score) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onCall(player, score);
        }
    }

    public void onRob(ServerPlayer player, boolean rob) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onRob(player, rob);
        }
    }

    public void onPlayCards(ServerPlayer player, int[] cardIds) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onPlay(player, DdzCard.byIds(cardIds));
        }
    }

    public void onPass(ServerPlayer player) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onPlay(player, null);
        }
    }

    public void setTrust(ServerPlayer player, boolean enabled) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.setTrust(player, enabled);
        }
    }

    // ---------------- 生命周期 ----------------

    /** 断线处理：对局中→自动托管；等待/结算中→视为离开。 */
    public void onPlayerDisconnect(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase == DdzGamePhase.WAITING || phase == DdzGamePhase.SETTLED) {
            removeFromRoom(player, room, true);
        } else {
            room.game.onPlayerDisconnect(room.seatOf(player));
        }
    }

    /** 服务端每 tick：对局计时 + 空闲房间清理。 */
    public void tick(MinecraftServer server) {
        for (DdzRoom room : new ArrayList<>(rooms.values())) {
            if (room.game != null) {
                room.game.tick();
            }
            if (room.game != null && room.allDisconnected()
                    && room.phase() != DdzGamePhase.WAITING && room.phase() != DdzGamePhase.SETTLED) {
                destroyRoom(room, "所有玩家已离线，房间已解散");
                continue;
            }
            if (room.phase() == DdzGamePhase.SETTLED && room.settledAtMillis > 0
                    && System.currentTimeMillis() - room.settledAtMillis > SETTLED_KEEP_MS) {
                destroyRoom(room, "房间空闲过久，已解散");
            }
        }
    }

    // ---------------- 内部 ----------------

    private void startGame(DdzRoom room) {
        room.game = new DdzGame(room, room.members);
        room.game.start();
    }

    /** 将玩家移出房间；不满 3 人时解散房间并通知剩余玩家。 */
    private void removeFromRoom(ServerPlayer player, DdzRoom room, boolean notifyOthers) {
        room.removePlayer(player);
        playerRoomIds.remove(player.getUUID());
        if (room.size < 3) {
            if (notifyOthers) {
                room.broadcast(new RoomClosedS2C("有玩家离开，房间已解散"));
            }
            destroyRoomInternal(room);
        } else {
            room.broadcastState();
        }
        // 离开者本人回到大厅（空 reason 不弹提示）
        if (DdzRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new RoomClosedS2C(""));
        }
    }

    private void destroyRoom(DdzRoom room, String reason) {
        room.broadcast(new RoomClosedS2C(reason));
        destroyRoomInternal(room);
    }

    private void destroyRoomInternal(DdzRoom room) {
        rooms.remove(room.id);
        for (int i = 0; i < room.size; i++) {
            ServerPlayer member = room.members[i];
            if (member != null) {
                playerRoomIds.remove(member.getUUID());
            }
        }
    }

    private String generateCode() {
        while (true) {
            StringBuilder sb = new StringBuilder(5);
            for (int i = 0; i < 5; i++) {
                sb.append(CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)]);
            }
            String code = sb.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
    }

    public DdzRoom currentRoom(ServerPlayer player) {
        String roomId = playerRoomIds.get(player.getUUID());
        return roomId == null ? null : rooms.get(roomId);
    }

    private DdzGame gameOf(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        return room == null ? null : room.game;
    }

    private static void error(ServerPlayer player, String message) {
        if (DdzRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new NoticeS2C(message));
        }
    }
}
