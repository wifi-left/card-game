package io.wifi.cards.uno.manager;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoDeck;
import io.wifi.cards.uno.game.UnoGame;
import io.wifi.cards.uno.model.UnoGamePhase;
import io.wifi.cards.uno.network.UnoPackets.DebugSpectatorS2C;
import io.wifi.cards.uno.network.UnoPackets.NoticeS2C;
import io.wifi.cards.uno.network.UnoPackets.RoomClosedS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局内存管理器（单例）：所有活跃 UNO 房间 + 玩家所属房间映射。
 * <p>纯内存存储：服务器重启即全部清空，无任何持久化。</p>
 * <p>生命周期：WAITING（房主点击开始，至少 2 人）→ 对局 → SETTLED（60 秒无操作自动销毁 /
 * 有人离开则解散；"再来一局"保持房间不散直接重开）。等待中不满 2 人解散。</p>
 */
public final class UnoMemoryManager {
    public static final UnoMemoryManager INSTANCE = new UnoMemoryManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    /** 结算后无人操作，保留的毫秒数（60 秒）。 */
    private static final long SETTLED_KEEP_MS = 60_000;

    /** 同时存在的房间数上限（防恶意客户端洪泛创建）。 */
    private static final int MAX_ROOMS = 64;

    /** 房间码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Random RANDOM = new Random();

    private final Map<String, UnoRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomIds = new ConcurrentHashMap<>();
    /** 旁观者所属房间映射（旁观只读观看，不占座位、无成员会话）。 */
    private final Map<UUID, String> spectatorRoomIds = new ConcurrentHashMap<>();

    private UnoMemoryManager() {
    }

    // ---------------- 房间操作 ----------------

    public void createRoom(MinecraftServer server, ServerPlayer player, boolean announce, int botCount) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        // 跨游戏防护：一个玩家同时只能在一个小游戏中（房间成员或旁观）
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_UNO);
        if (other != null) {
            error(player, "你正在【" + other.displayName() + "】中，请先退出该游戏再进入其他小游戏");
            return;
        }
        // 防御：房间总数上限，防止恶意客户端洪泛创建房间耗尽内存
        if (rooms.size() >= MAX_ROOMS) {
            error(player, "房间数量已达上限，请稍后再试");
            return;
        }
        UnoRoom room = new UnoRoom(generateCode(), announce);
        room.addPlayer(player);
        rooms.put(room.id, room);
        playerRoomIds.put(player.getUUID(), room.id);
        // 房主可选加入机器人补位（0~9 个；开局由房主点击开始）
        int bots = Math.max(0, Math.min(botCount, UnoRoom.MAX_PLAYERS - room.size()));
        for (int i = 0; i < bots; i++) {
            room.addBot("Bot" + (room.size() + 1));
        }
        room.broadcastState();
        // 公布房间：全服聊天栏广播可点击加入消息
        if (announce && server != null) {
            Component message = Component.literal("[UNO] " + player.getGameProfile().getName()
                    + " 创建了房间 " + room.id)
                    .append(Component.literal(" [点击加入]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames accept " + room.id))));
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
        // 无大厅 UI：直接提示房主房间已创建（可点击查看列表）
        player.sendSystemMessage(Component.literal("已创建房间 " + room.id + "，等待玩家加入")
                .append(Component.literal(" [房间列表]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames rooms")))));
    }

    public void joinRoom(ServerPlayer player, String code) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        // 防御：房间码长度上限（完整码前缀-5位共 8 字符，杜绝超长输入）
        if (code == null || code.length() > 16) {
            error(player, "房间码无效");
            return;
        }
        UnoRoom room = rooms.get(fullCode(code));
        if (room == null) {
            error(player, "房间不存在：" + code);
            return;
        }
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝加入
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_UNO);
        if (other != null) {
            error(player, "你正在【" + other.displayName() + "】中，请先退出该游戏再进入其他小游戏");
            return;
        }
        if (room.isFull()) {
            error(player, "房间已满");
            return;
        }
        if (room.phase() != UnoGamePhase.WAITING) {
            error(player, "游戏已经开始，无法加入");
            return;
        }
        room.addPlayer(player);
        playerRoomIds.put(player.getUUID(), room.id);
        room.broadcastState();
    }

    /** 开始游戏：仅房主（座位 0）在等待中可触发，至少 2 名玩家。 */
    public void startGame(ServerPlayer player) {
        // 旁观者无权开始
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, "旁观者不能开始游戏");
            return;
        }
        UnoRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        if (room.phase() != UnoGamePhase.WAITING) {
            error(player, "游戏已经开始");
            return;
        }
        if (room.seatOf(player) != 0) {
            error(player, "只有房主可以开始游戏");
            return;
        }
        if (room.size() < 2) {
            error(player, "至少需要 2 名玩家才能开始（可创建房间时加入机器人）");
            return;
        }
        startGame(room);
    }

    /**
     * 离开房间/退出游戏：
     * <ul>
     *   <li>旁观中：退出旁观（不占成员座位，不影响对局）</li>
     *   <li>等待/结算中：正常离开（不满 2 人解散房间）</li>
     *   <li>对局中：退出游戏，座位转机器人托管，对局继续；房间已无真人则关闭房间</li>
     * </ul>
     */
    public void leaveRoom(ServerPlayer player) {
        // 旁观者：/uno leave 等同退出旁观
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            leaveSpectate(player);
            return;
        }
        UnoRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        UnoGamePhase phase = room.phase();
        if (phase != UnoGamePhase.WAITING && phase != UnoGamePhase.SETTLED) {
            quitFromGame(player, room);
            return;
        }
        removeFromRoom(player, room, true);
    }

    /** 对局中退出游戏：座位转机器人托管（对局继续）；房间已无真人玩家则关闭房间。 */
    private void quitFromGame(ServerPlayer player, UnoRoom room) {
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

    /** 再来一局：结算后重置房间状态并重新发牌（房间不散）。
     *  注意必须重建 UnoGame：SETTLED 后成员可能已离开（座位压缩），
     *  复用旧实例会保留开局时的固定玩家列表，产生幽灵座位（无人行动的卡局座位）。 */
    public void nextGame(ServerPlayer player) {
        // 严格限制：旁观者无权开始新对局（新局只能由成员触发）
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, "旁观者不能开始新对局");
            return;
        }
        UnoRoom room = currentRoom(player);
        if (room == null || room.game == null || room.phase() != UnoGamePhase.SETTLED) {
            return;
        }
        room.settledAtMillis = -1;
        room.game = new UnoGame(room); // 按当前房间座位重建（成员离开后座位数可能变化）
        room.game.start();
    }

    // ---------------- 对局操作转发 ----------------

    /**
     * 对局操作的门卫：旁观者一律明确拒绝（只读观看，任何操作请求都提示），
     * 非成员且非旁观者（理论不可达）静默忽略。
     */
    private UnoGame gameOfStrict(ServerPlayer player) {
        UnoGame game = gameOf(player);
        if (game == null && spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, "旁观者不能操作对局");
        }
        return game;
    }

    public void onPlayCard(ServerPlayer player, int cardId, byte colorOrdinal) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.onPlay(player, cardId, colorOrdinal);
        }
    }

    public void onDraw(ServerPlayer player) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.onDraw(player);
        }
    }

    public void onPass(ServerPlayer player) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.onPass(player);
        }
    }

    public void onDeclareUno(ServerPlayer player) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.onDeclareUno(player);
        }
    }

    public void onCatchUno(ServerPlayer player, byte targetSeat) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.onCatchUno(player, targetSeat);
        }
    }

    public void setTrust(ServerPlayer player, boolean enabled) {
        UnoGame game = gameOfStrict(player);
        if (game != null) {
            game.setTrust(player, enabled);
        }
    }

    /** 事件历史请求（历史界面打开时）：下发本局完整事件历史（旁观者按其旁观房间下发）。 */
    public void onHistoryRequest(ServerPlayer player) {
        UnoRoom room = currentRoom(player);
        if (room != null && room.game != null) {
            room.game.sendHistory(room.seatOf(player));
            return;
        }
        String specId = spectatorRoomIds.get(player.getUUID());
        if (specId != null) {
            UnoRoom specRoom = rooms.get(specId);
            if (specRoom != null && specRoom.game != null) {
                specRoom.game.sendHistoryToSpectator(player);
            }
        }
    }

    // ---------------- 生命周期 ----------------

    /**
     * 断线处理：对局中掉线 → 自动托管代打，对局继续；等待/结算中 → 视为离开（不满 2 人解散）。
     */
    public void onPlayerDisconnect(ServerPlayer player) {
        // 旁观者：清理旁观关系后直接返回（无成员会话）
        String specId = spectatorRoomIds.remove(player.getUUID());
        if (specId != null) {
            UnoRoom specRoom = rooms.get(specId);
            if (specRoom != null) {
                specRoom.removeSpectator(player);
            }
            return;
        }
        UnoRoom room = currentRoom(player);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        UnoGamePhase phase = room.phase();
        if (phase == UnoGamePhase.WAITING || phase == UnoGamePhase.SETTLED) {
            removeFromRoom(player, room, true);
        } else {
            room.game.onPlayerDisconnect(room.seatOf(player));
            // 重发房间状态：其余玩家界面显示"离线"标记（conn 按连接可用性判定）
            room.broadcastState();
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
        UnoRoom room = rooms.get(roomId);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        UnoGamePhase phase = room.phase();
        if (phase == UnoGamePhase.WAITING) {
            // 等待中断线已按离开处理（会话已清除），理论不会到达；防御性兜底关闭房间
            room.broadcast(new RoomClosedS2C(player.getGameProfile().getName() + " 重连发现旧房间，房间已关闭"));
            destroyRoomInternal(room);
            return;
        }
        // 对局中（PLAYING）掉线托管续玩、或对局已结算（SETTLED）后重连：
        // 会话仍有效，正常替换连接引用并同步快照（SETTLED 重连可看结算结果、点"再来一局"，
        // 房间本可保留至 60 秒空闲销毁——不能销毁房间把其他成员一起踢回大厅）
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
        for (UnoRoom room : new ArrayList<>(rooms.values())) {
            if (room.game != null) {
                try {
                    room.game.tick();
                } catch (Throwable t) {
                    // 防御：单个房间状态机异常不得崩溃整个服务器——记录日志并关闭该房间
                    LOGGER.error("UNO 房间 {} tick 异常，房间已关闭", room.id, t);
                    destroyRoom(room, "房间状态异常，已关闭");
                    continue;
                }
            }
            // 等待中的房间已无真人玩家（房主离开后只剩机器人）：无人能点击"开始游戏"，
            // 若不清理会形成永久僵尸房间——销毁
            if (room.phase() == UnoGamePhase.WAITING && !room.hasRealPlayer()) {
                destroyRoom(room, "房间内已无真人玩家，房间关闭");
                continue;
            }
            // 全部座位为机器人（真人全部退出转托管，或开局即全机器人）：
            // 无在位真人游玩，结束本局并关闭房间。手动托管（座位仍为真人）不在此列。
            if (room.game != null && room.allBot()
                    && room.phase() != UnoGamePhase.WAITING && room.phase() != UnoGamePhase.SETTLED) {
                destroyRoom(room, "房间内已无真人玩家，本局结束");
                continue;
            }
            if (room.game != null && room.allDisconnected()
                    && room.phase() != UnoGamePhase.WAITING && room.phase() != UnoGamePhase.SETTLED) {
                destroyRoom(room, "所有玩家已离线，房间已解散");
                continue;
            }
            if (room.phase() == UnoGamePhase.SETTLED && room.settledAtMillis > 0
                    && System.currentTimeMillis() - room.settledAtMillis > SETTLED_KEEP_MS) {
                destroyRoom(room, "房间空闲过久，已解散");
            }
        }
    }

    // ---------------- 调试假人（开发端） ----------------

    /** 向等待中的房间添加 1~3 个调试假人。 */
    public void addBots(ServerPlayer player, int count) {
        UnoRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里，请先创建房间");
            return;
        }
        if (room.phase() != UnoGamePhase.WAITING) {
            error(player, "只有等待中的房间可以添加假人");
            return;
        }
        int canAdd = Math.min(Math.max(count, 1), UnoRoom.MAX_PLAYERS - room.size());
        for (int i = 0; i < canAdd; i++) {
            room.addBot("Bot" + (room.size() + 1));
        }
        room.broadcastState();
    }

    /** 移除房间内全部假人；对局中移除导致人数不足时结束本局并关闭房间。 */
    public void removeBots(ServerPlayer player) {
        UnoRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        if (room.botCount() == 0) {
            error(player, "房间内没有假人");
            return;
        }
        room.removeBots();
        if (room.phase() != UnoGamePhase.WAITING) {
            // 对局中移除假人：游戏实例持有开局时的座位快照，拆座会造成座位错位，
            // 直接结束本局并关闭房间（调试命令专用，对局中勿拆座）
            destroyRoom(room, "调试假人已移除，房间关闭");
            return;
        }
        room.broadcastState();
        if (room.size() < 2) {
            destroyRoom(room, "调试假人已移除，房间关闭");
        }
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间快照（按房间码排序，管理命令显示用）。 */
    public List<UnoRoom> roomSnapshot() {
        List<UnoRoom> list = new ArrayList<>(rooms.values());
        list.sort(java.util.Comparator.comparing(r -> r.id));
        return list;
    }

    /** 按房间码查找房间（管理命令用），不存在返回 null。 */
    public UnoRoom roomByCode(String code) {
        return rooms.get(fullCode(code));
    }

    /** 删除指定房间（通知成员后销毁）；返回错误信息或 null。 */
    public String deleteRoom(String code) {
        UnoRoom room = rooms.get(fullCode(code));
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
        for (UnoRoom room : new ArrayList<>(rooms.values())) {
            room.broadcast(new RoomClosedS2C("管理员清空了所有房间"));
        }
        rooms.clear();
        playerRoomIds.clear();
        spectatorRoomIds.clear(); // 旁观关系随房间一并清空
        return count;
    }

    // ---------------- 旁观（对局开始后只读观看） ----------------

    /** 请求旁观房间；返回错误信息或 null。 */
    public String spectate(ServerPlayer player, String code) {
        if (code == null || code.length() > 16) {
            return "房间码无效";
        }
        UnoRoom room = rooms.get(fullCode(code));
        if (room == null) {
            return "房间不存在：" + code;
        }
        UnoGamePhase phase = room.phase();
        if (phase == UnoGamePhase.WAITING) {
            return "游戏尚未开始，无法旁观";
        }
        if (phase == UnoGamePhase.SETTLED) {
            return "本局已结束，无法旁观";
        }
        if (currentRoom(player) != null) {
            return "你已在房间中，无法旁观";
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝旁观
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_UNO);
        if (other != null) {
            return "你正在【" + other.displayName() + "】中，无法旁观其他小游戏";
        }
        String existing = spectatorRoomIds.get(player.getUUID());
        if (existing != null) {
            if (existing.equals(room.id)) {
                // 已在旁观同一房间：视为重新打开旁观界面（重发完整快照，客户端回到 GameScreen）
                room.broadcastState();
                room.game.syncToSpectator(player);
                return null;
            }
            return "你已在旁观其他房间";
        }
        room.addSpectator(player);
        spectatorRoomIds.put(player.getUUID(), room.id);
        room.broadcastState(); // 名字/状态同步（含旁观者 mySeat=-1）
        room.game.syncToSpectator(player);
        return null;
    }

    /** 退出旁观。 */
    public void leaveSpectate(ServerPlayer player) {
        String roomId = spectatorRoomIds.remove(player.getUUID());
        if (roomId == null) {
            error(player, "你不在旁观任何房间");
            return;
        }
        UnoRoom room = rooms.get(roomId);
        if (room != null) {
            room.removeSpectator(player);
        }
        ServerPlayNetworking.send(player, new RoomClosedS2C("已退出旁观"));
    }

    /** 进入/加入房间前自动退出旁观（避免同时旁观与对局）。 */
    private void leaveSpectateInternal(ServerPlayer player) {
        String roomId = spectatorRoomIds.remove(player.getUUID());
        if (roomId != null) {
            UnoRoom room = rooms.get(roomId);
            if (room != null) {
                room.removeSpectator(player);
            }
        }
    }

    /**
     * 强制将指定玩家加入房间（调试用，无视客户端操作）。
     *
     * @return 错误消息；null 表示成功
     */
    public String forceJoin(ServerPlayer target, String roomCode) {
        // 先退出旁观状态（强制入房同样要求退出旁观）
        leaveSpectateInternal(target);
        UnoRoom room = rooms.get(fullCode(roomCode));
        if (room == null) {
            return "房间不存在：" + roomCode;
        }
        if (currentRoom(target) != null) {
            return target.getGameProfile().getName() + " 已在其他房间";
        }
        // 跨游戏防护：强制入房同样要求退出其他小游戏
        GameInfo other = GameRegistry.busyInOtherGame(target, GameRegistry.GAME_UNO);
        if (other != null) {
            return target.getGameProfile().getName() + " 正在【" + other.displayName() + "】中，无法强制加入";
        }
        if (room.isFull()) {
            return "房间已满";
        }
        if (room.phase() != UnoGamePhase.WAITING) {
            return "游戏已经开始，无法加入";
        }
        room.addPlayer(target);
        playerRoomIds.put(target.getUUID(), room.id);
        room.broadcastState();
        return null;
    }

    // ---------------- 旁观 UI 调试（管理员） ----------------

    /**
     * 旁观 UI 调试：无房间、无真实对局，生成一组随机虚拟旁观快照发给玩家
     * （客户端标题显示"（调试）"，仅用于检查旁观界面渲染/滚动，不产生任何房间）。
     * 要求玩家当前不在房间、不在旁观，避免与真实对局状态混淆。
     */
    public void debugSpectatorUi(ServerPlayer player) {
        if (currentRoom(player) != null) {
            error(player, "请先退出房间再使用旁观调试");
            return;
        }
        if (spectatingRoomId(player) != null) {
            error(player, "请先退出旁观再使用旁观调试");
            return;
        }
        Random random = new Random();
        // 8~10 名虚拟玩家（人数多保证旁观面板超高，便于检查滚动条）
        int count = 8 + random.nextInt(3);
        String[] names = new String[count];
        int[][] hands = new int[count][];
        for (int i = 0; i < count; i++) {
            names[i] = "调试玩家" + (i + 1);
            List<UnoCard> deck = UnoDeck.create();
            Collections.shuffle(deck, random);
            int size;
            if (i < 2) {
                size = 1; // 前两名玩家各剩 1 张：展示 UNO 标记（已喊/可抓）
            } else {
                size = 7 + random.nextInt(14); // 7~20 张
            }
            hands[i] = new int[size];
            for (int j = 0; j < size; j++) {
                hands[i][j] = deck.get(j).id();
            }
        }
        boolean[] unoCatchable = new boolean[count];
        boolean[] declaredUno = new boolean[count];
        unoCatchable[0] = true; // 0 号未喊 UNO（可抓标记）
        declaredUno[1] = true;  // 1 号已喊 UNO
        // 起牌：随机数字牌
        List<UnoCard> deck = UnoDeck.create();
        Collections.shuffle(deck, random);
        UnoCard top = null;
        for (UnoCard c : deck) {
            if (c.value().isNumber()) {
                top = c;
                break;
            }
        }
        if (top == null) {
            top = deck.get(0);
        }
        ServerPlayNetworking.send(player, new DebugSpectatorS2C(names, hands,
                (byte) random.nextInt(count), (byte) (random.nextBoolean() ? 1 : -1),
                top.id(), (byte) top.color().ordinal(), unoCatchable, declaredUno));
    }

    // ---------------- 内部 ----------------

    private void startGame(UnoRoom room) {
        room.game = new UnoGame(room);
        room.game.start();
        // 全服广播：房间已开始，其他玩家可点击旁观
        ServerPlayer host = room.members.get(0);
        if (host != null && host.getServer() != null) {
            Component msg = Component.literal("[UNO] 房间 " + room.id + " 已开始，")
                    .append(Component.literal("[点击旁观]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames spectate " + room.id))));
            host.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** 将玩家移出房间；不满 2 人时解散房间并通知剩余玩家。 */
    private void removeFromRoom(ServerPlayer player, UnoRoom room, boolean notifyOthers) {
        room.removePlayer(player);
        playerRoomIds.remove(player.getUUID());
        if (room.size() < 2) {
            if (notifyOthers) {
                room.broadcast(new RoomClosedS2C("有玩家离开，房间已解散"));
            }
            destroyRoomInternal(room);
        } else {
            room.broadcastState();
        }
        // 离开者本人回到大厅（空 reason 不弹提示）
        if (UnoRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new RoomClosedS2C(""));
        }
    }

    private void destroyRoom(UnoRoom room, String reason) {
        room.broadcast(new RoomClosedS2C(reason));
        destroyRoomInternal(room);
    }

    private void destroyRoomInternal(UnoRoom room) {
        rooms.remove(room.id);
        for (int i = 0; i < room.size(); i++) {
            ServerPlayer member = room.members.get(i);
            if (member != null) {
                playerRoomIds.remove(member.getUUID());
            }
        }
        for (ServerPlayer sp : room.spectators) {
            spectatorRoomIds.remove(sp.getUUID());
        }
    }

    private String generateCode() {
        // 统一房间号格式：前缀-5位码（前缀见 GameRegistry，全服唯一区分所属游戏）
        String prefix = GameRegistry.PREFIX_UNO + "-";
        while (true) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < 5; i++) {
                sb.append(CODE_CHARS[RANDOM.nextInt(CODE_CHARS.length)]);
            }
            String code = sb.toString();
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
    }

    /** 房间码规范化：去掉本游戏前缀（兼容 "UN-XXXXX" 完整码与裸码 "XXXXX" 两种输入）。 */
    /** 房间码规范化：统一为完整码（补上前缀），兼容裸码与完整码输入（rooms 的 key 为完整码）。 */
    private static String fullCode(String code) {
        String norm = code == null ? "" : code.toUpperCase().trim();
        return norm.startsWith(GameRegistry.PREFIX_UNO + "-") ? norm : GameRegistry.PREFIX_UNO + "-" + norm;
    }

    /** 当前房间总数（菜单统计用）。 */
    public int roomCount() {
        return rooms.size();
    }

    /** 在线人数统计（房间成员 + 旁观者，菜单统计用）。 */
    public int playerCount() {
        int count = 0;
        for (UnoRoom room : rooms.values()) {
            count += room.size() + room.spectators.size();
        }
        return count;
    }

    public UnoRoom currentRoom(ServerPlayer player) {
        String roomId = playerRoomIds.get(player.getUUID());
        return roomId == null ? null : rooms.get(roomId);
    }

    /** 玩家正在旁观哪个房间（无则 null）。 */
    public String spectatingRoomId(ServerPlayer player) {
        return spectatorRoomIds.get(player.getUUID());
    }

    /** 玩家正在旁观的房间对象；未旁观或房间已销毁返回 null。 */
    public UnoRoom spectatorRoomOf(ServerPlayer player) {
        String id = spectatorRoomIds.get(player.getUUID());
        return id == null ? null : rooms.get(id);
    }

    public UnoGame gameOf(ServerPlayer player) {
        UnoRoom room = currentRoom(player);
        return room == null ? null : room.game;
    }

    private static void error(ServerPlayer player, String message) {
        if (UnoRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new NoticeS2C(message));
        }
    }
}
