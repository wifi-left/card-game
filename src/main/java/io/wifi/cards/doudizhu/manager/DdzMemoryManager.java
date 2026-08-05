package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomClosedS2C;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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

    /** 同时存在的房间数上限（防恶意客户端洪泛创建）。 */
    private static final int MAX_ROOMS = 64;

    /** 房间码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Random RANDOM = new Random();

    private final Map<String, DdzRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomIds = new ConcurrentHashMap<>();

    private DdzMemoryManager() {
    }

    // ---------------- 房间操作 ----------------

    public void createRoom(MinecraftServer server, ServerPlayer player, boolean flowerMode, DdzRuleSet ruleSet, boolean announce, int botCount) {
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        // 防御：房间总数上限，防止恶意客户端洪泛创建房间耗尽内存
        if (rooms.size() >= MAX_ROOMS) {
            error(player, "房间数量已达上限，请稍后再试");
            return;
        }
        DdzRoom room = new DdzRoom(generateCode(), flowerMode, ruleSet);
        room.addPlayer(player);
        rooms.put(room.id, room);
        playerRoomIds.put(player.getUUID(), room.id);
        // 房主可选加入机器人补位（0~2 个；满 3 人自动开局）
        int bots = Math.max(0, Math.min(botCount, 3 - room.size));
        for (int i = 0; i < bots; i++) {
            room.addBot("Bot" + (room.size + 1));
        }
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
        // 公布房间：全服聊天栏广播可点击加入消息
        if (announce && server != null) {
            String mode = flowerMode ? "花牌模式" : "经典模式";
            Component message = Component.literal("[斗地主] " + player.getGameProfile().getName()
                    + " 创建了房间 " + room.id + "（" + mode + " · " + ruleSet.displayName() + "）")
                    .append(Component.literal(" [点击加入]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/doudizhu accept " + room.id))));
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    public void joinRoom(ServerPlayer player, String code) {
        // 防御：房间码长度上限（房间码固定 5 位，杜绝超长输入）
        if (code == null || code.length() > 16) {
            error(player, "房间码无效");
            return;
        }
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

    /**
     * 离开房间/退出游戏：
     * <ul>
     *   <li>等待/结算中：正常离开（不满 3 人解散房间）</li>
     *   <li>对局中：退出游戏，座位转机器人托管，对局继续；房间已无真人则关闭房间</li>
     * </ul>
     */
    public void leaveRoom(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase != DdzGamePhase.WAITING && phase != DdzGamePhase.SETTLED) {
            quitFromGame(player, room);
            return;
        }
        removeFromRoom(player, room, true);
    }

    /** 对局中退出游戏：座位转机器人托管（对局继续）；房间已无真人玩家则关闭房间。 */
    private void quitFromGame(ServerPlayer player, DdzRoom room) {
        int seat = room.seatOf(player);
        if (seat < 0) {
            return;
        }
        room.quitToBot(seat);
        playerRoomIds.remove(player.getUUID());
        room.game.setTrustSeat(seat, true); // 机器人托管代打（正轮到则立即行动）
        ServerPlayNetworking.send(player, new RoomClosedS2C("你已退出游戏，座位由机器人托管"));
        room.broadcastState();
        if (!room.hasRealPlayer()) {
            destroyRoom(room, "房间内已无真人玩家，房间关闭");
        }
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
            // 防御：单次出牌最多 21 张（花牌模式地主 17+4）；超长数组直接拒绝，
            // 防止恶意客户端发送超大数组造成内存分配与校验开销
            if (cardIds.length > 21) {
                error(player, "出牌数量异常");
                return;
            }
            // 防御：过滤越界 id（恶意客户端可能发送非法值导致数组越界）；
            // 非法 id 不在任何玩家手牌中，后续 containsAll 校验会拒绝本次出牌
            List<DdzCard> cards = new ArrayList<>(cardIds.length);
            for (int id : cardIds) {
                if (id >= 0 && id < DdzCard.TOTAL_COUNT) {
                    cards.add(DdzCard.byId(id));
                }
            }
            game.onPlay(player, cards);
        }
    }

    public void onPass(ServerPlayer player) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onPlay(player, null);
        }
    }

    public void onReveal(ServerPlayer player) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.onReveal(player);
        }
    }

    /** 出牌历史请求（历史界面打开时）：下发本局完整出牌历史。 */
    public void onHistoryRequest(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room != null && room.game != null) {
            room.game.sendHistory(room.seatOf(player));
        }
    }

    public void setTrust(ServerPlayer player, boolean enabled) {
        DdzGame game = gameOf(player);
        if (game != null) {
            game.setTrust(player, enabled);
        }
    }

    // ---------------- 生命周期 ----------------

    /**
     * 断线处理：对局中（叫分/抢地主/出牌）掉线 → 自动托管代打，对局继续；
     * 等待/结算中 → 视为离开（不满 3 人解散）。
     */
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

    /**
     * 玩家（重连）进入服务器：若其仍在对局中（断线托管续玩），
     * 替换连接引用、恢复手动控制、同步完整对局快照，使重连玩家可继续游玩。
     */
    public void onPlayerJoin(ServerPlayer player) {
        String roomId = playerRoomIds.get(player.getUUID());
        if (roomId == null) {
            return;
        }
        DdzRoom room = rooms.get(roomId);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase == DdzGamePhase.WAITING || phase == DdzGamePhase.SETTLED) {
            // 等待/结算中断线已按离开处理（会话已清除），理论不会到达；防御性兜底关闭房间
            room.broadcast(new RoomClosedS2C(player.getGameProfile().getName() + " 重连发现旧房间，房间已关闭"));
            destroyRoomInternal(room);
            return;
        }
        int seat = room.replacePlayerByUuid(player.getUUID(), player);
        if (seat < 0) {
            // 找不到对应座位（理论不会发生）→ 防御性关闭房间
            room.broadcast(new RoomClosedS2C(player.getGameProfile().getName() + " 重连失败，房间已关闭"));
            destroyRoomInternal(room);
            return;
        }
        room.game.onPlayerReconnect(seat);
        room.broadcast(new NoticeS2C(player.getGameProfile().getName() + " 已重连"));
        room.broadcastState();
        room.game.syncTo(seat);
    }

    /** 服务端每 tick：对局计时 + 空闲房间清理。 */
    public void tick(MinecraftServer server) {
        for (DdzRoom room : new ArrayList<>(rooms.values())) {
            if (room.game != null) {
                room.game.tick();
            }
            // 全部座位为机器人（真人全部退出转托管，或开局即全机器人）：
            // 无在位真人游玩，结束本局并关闭房间。手动托管（座位仍为真人）不在此列。
            if (room.game != null && room.allBot()
                    && room.phase() != DdzGamePhase.WAITING && room.phase() != DdzGamePhase.SETTLED) {
                destroyRoom(room, "房间内已无真人玩家，本局结束");
                continue;
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

    // ---------------- 调试假人（开发端） ----------------

    /** 向等待中的房间添加 1~2 个调试假人；满 3 人自动开局。 */
    public void addBots(ServerPlayer player, int count) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里，请先创建房间");
            return;
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            error(player, "只有等待中的房间可以添加假人");
            return;
        }
        int canAdd = Math.min(Math.max(count, 1), 3 - room.size);
        for (int i = 0; i < canAdd; i++) {
            room.addBot("Bot" + (room.size + 1));
        }
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
    }

    /** 移除房间内全部假人；对局中移除导致人数不足时结束本局并关闭房间。 */
    public void removeBots(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        if (room.botCount() == 0) {
            error(player, "房间内没有假人");
            return;
        }
        room.removeBots();
        room.broadcastState();
        if (room.size < 3) {
            destroyRoom(room, "调试假人已移除，房间关闭");
        }
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间快照（按房间码排序，管理命令显示用）。 */
    public List<DdzRoom> roomSnapshot() {
        List<DdzRoom> list = new ArrayList<>(rooms.values());
        list.sort(java.util.Comparator.comparing(r -> r.id));
        return list;
    }

    /** 按房间码查找房间（管理命令用），不存在返回 null。 */
    public DdzRoom roomByCode(String code) {
        return rooms.get(code.toUpperCase().trim());
    }

    /** 删除指定房间（通知成员后销毁）；返回错误信息或 null。 */
    public String deleteRoom(String code) {
        DdzRoom room = rooms.get(code.toUpperCase().trim());
        if (room == null) {
            return "房间不存在：" + code;
        }
        room.broadcast(new RoomClosedS2C("管理员删除了房间"));
        destroyRoomInternal(room);
        return null;
    }

    /** 清空全部房间（通知所有成员），返回删除数量。 */
    public int clearAllRooms() {
        int count = rooms.size();
        for (DdzRoom room : new ArrayList<>(rooms.values())) {
            room.broadcast(new RoomClosedS2C("管理员清空了所有房间"));
        }
        rooms.clear();
        playerRoomIds.clear();
        return count;
    }

    /**
     * 强制将指定玩家加入房间（调试用，无视客户端操作）。
     *
     * @return 错误消息；null 表示成功
     */
    public String forceJoin(ServerPlayer target, String roomCode) {
        DdzRoom room = rooms.get(roomCode.toUpperCase().trim());
        if (room == null) {
            return "房间不存在：" + roomCode;
        }
        if (currentRoom(target) != null) {
            return target.getGameProfile().getName() + " 已在其他房间";
        }
        if (room.isFull()) {
            return "房间已满";
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            return "游戏已经开始，无法加入";
        }
        room.addPlayer(target);
        playerRoomIds.put(target.getUUID(), room.id);
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
        return null;
    }

    // ---------------- 内部 ----------------

    private void startGame(DdzRoom room) {
        room.game = new DdzGame(room);
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

    public DdzGame gameOf(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        return room == null ? null : room.game;
    }

    private static void error(ServerPlayer player, String message) {
        if (DdzRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new NoticeS2C(message));
        }
    }
}
