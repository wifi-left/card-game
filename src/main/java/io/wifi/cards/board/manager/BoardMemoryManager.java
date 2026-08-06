package io.wifi.cards.board.manager;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.go.game.GoGame;
import io.wifi.cards.board.gomoku.game.GomokuGame;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.NoticeS2C;
import io.wifi.cards.board.network.BoardPackets.RoomClosedS2C;
import io.wifi.cards.board.network.BoardPackets.RoomListS2C;
import io.wifi.cards.board.othello.game.OthelloGame;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局内存管理器（单例）：所有棋类房间 + 玩家所属房间映射。
 * <p>纯内存存储：服务器重启即全部清空，无任何持久化。</p>
 * <p>生命周期：WAITING（满 2 人自动开局）→ 对局 → SETTLED（60 秒无操作自动销毁 /
 * 有人离开则解散；"再来一局"保持房间不散直接重开）。</p>
 * <p>托管：对局中玩家主动退出/断线 → 座位转机器人托管（五子棋/黑白棋由 AI 自动落子，
 * 围棋自动停一手）；全部座位非真人或全部离线 → 结束本局并关闭房间。</p>
 */
public final class BoardMemoryManager {
    public static final BoardMemoryManager INSTANCE = new BoardMemoryManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    /** 结算后无人操作，保留的毫秒数（60 秒）。 */
    private static final long SETTLED_KEEP_MS = 60_000;

    /** 同时存在的房间数上限（防恶意客户端洪泛创建）。 */
    private static final int MAX_ROOMS = 64;

    /** 房间码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Random RANDOM = new Random();

    private final Map<String, BoardRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomIds = new ConcurrentHashMap<>();
    /** 旁观者所属房间映射（旁观只读观看，不占座位、无成员会话）。 */
    private final Map<UUID, String> spectatorRoomIds = new ConcurrentHashMap<>();
    /** 大厅列表查询时间戳（LobbyQueryC2S 频率限制，随断线清理）。 */
    private final Map<UUID, Long> lobbyQueryTimes = new ConcurrentHashMap<>();

    private BoardMemoryManager() {
    }

    // ---------------- 房间操作 ----------------

    public void createRoom(MinecraftServer server, ServerPlayer player, BoardGameType gameType, int size, boolean announce, int botCount) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        // 跨游戏防护：一个玩家同时只能在一个小游戏中（房间成员或旁观）
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_BOARD);
        if (other != null) {
            error(player, "你正在【" + other.displayName() + "】中，请先退出该游戏再进入其他小游戏");
            return;
        }
        // 防御：房间总数上限，防止恶意客户端洪泛创建房间耗尽内存
        if (rooms.size() >= MAX_ROOMS) {
            error(player, "房间数量已达上限，请稍后再试");
            return;
        }
        int realSize = BoardGameType.safeSize(gameType, size);
        BoardRoom room = new BoardRoom(generateCode(), gameType, realSize, announce);
        room.addPlayer(player);
        rooms.put(room.id, room);
        playerRoomIds.put(player.getUUID(), room.id);
        // 房主可选加入 1 个机器人补位（围棋无 AI，忽略机器人）
        if (gameType != BoardGameType.GO) {
            int bots = Math.max(0, Math.min(botCount, 2 - room.count));
            for (int i = 0; i < bots; i++) {
                room.addBot("Bot" + (room.count + 1));
            }
        }
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
        // 公布房间：全服聊天栏广播可点击加入消息
        if (announce && server != null) {
            Component message = Component.literal("[棋牌] " + player.getGameProfile().getName()
                    + " 创建了房间 " + room.id + "（" + gameType.displayName + sizeText(gameType, realSize) + "）")
                    .append(Component.literal(" [点击加入]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames accept " + room.id))));
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    public void joinRoom(ServerPlayer player, String code) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        // 防御：房间码长度上限（完整码前缀-5位共 8 字符，杜绝超长输入）
        if (code == null || code.length() > 16) {
            error(player, "房间码无效");
            return;
        }
        BoardRoom room = rooms.get(cleanCode(code));
        if (room == null) {
            error(player, "房间不存在：" + code);
            return;
        }
        if (currentRoom(player) != null) {
            error(player, "你已经在房间里了");
            return;
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝加入
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_BOARD);
        if (other != null) {
            error(player, "你正在【" + other.displayName() + "】中，请先退出该游戏再进入其他小游戏");
            return;
        }
        if (room.isFull()) {
            error(player, "房间已满");
            return;
        }
        if (room.phase() != BoardPhase.WAITING) {
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
     *   <li>旁观中：退出旁观（不占成员座位，不影响对局）</li>
     *   <li>等待/结算中：正常离开（不满 2 人解散房间）</li>
     *   <li>对局中：退出游戏，座位转机器人托管，对局继续；房间已无真人则关闭房间</li>
     * </ul>
     */
    public void leaveRoom(ServerPlayer player) {
        // 旁观者：/board leave 等同退出旁观
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            leaveSpectate(player);
            return;
        }
        BoardRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里");
            return;
        }
        BoardPhase phase = room.phase();
        if (phase != BoardPhase.WAITING && phase != BoardPhase.SETTLED) {
            quitFromGame(player, room);
            return;
        }
        removeFromRoom(player, room, true);
    }

    /**
     * 对局中退出游戏：五子棋/黑白棋座位转机器人托管（对局继续）；
     * 围棋无托管，退出即结束本局（对方获胜，见 GoGame.onPlayerQuit）。
     * 房间已无真人玩家则关闭房间。
     */
    private void quitFromGame(ServerPlayer player, BoardRoom room) {
        int seat = room.seatOf(player);
        if (seat < 0) {
            return;
        }
        boolean isGo = room.gameType == BoardGameType.GO;
        // 先通知状态机再转托管：围棋结算文案用原玩家名（quitToBot 后 seatName 会带"（托管）"后缀）
        room.game.onPlayerQuit(seat); // 五子棋/黑白棋：托管代打；围棋：直接结束本局
        room.quitToBot(seat);
        playerRoomIds.remove(player.getUUID());
        ServerPlayNetworking.send(player, new RoomClosedS2C(
                isGo ? "你已退出游戏，本局结束" : "你已退出游戏，座位由机器人托管"));
        room.broadcastState();
        if (!room.hasRealPlayer()) {
            destroyRoom(room, "房间内已无真人玩家，房间关闭");
        }
    }

    /** 再来一局：结算后重置房间状态并重新开局（房间不散）。 */
    public void nextGame(ServerPlayer player) {
        // 严格限制：旁观者无权开始新对局（新局只能由成员触发）
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, "旁观者不能开始新对局");
            return;
        }
        BoardRoom room = currentRoom(player);
        if (room == null || room.game == null || room.phase() != BoardPhase.SETTLED) {
            return;
        }
        // 围棋无 AI：房间存在退出者转的机器人座位时无法正常对局（机器人每轮跳过），拒绝开新局
        if (room.gameType == BoardGameType.GO && room.botCount() > 0) {
            error(player, "有玩家已退出，围棋无法开始新对局");
            return;
        }
        room.settledAtMillis = -1;
        room.game.begin();
        // start() 会重置托管标记：断线成员的座位重新标记托管（正轮到则自动行动），
        // 避免新局中该座位每回合干等 60 秒超时
        for (int i = 0; i < room.count; i++) {
            if (!room.isBot(i) && !BoardRoom.isConnected(room.members[i])) {
                room.game.onPlayerQuit(i);
            }
        }
    }

    // ---------------- 对局操作转发 ----------------

    /**
     * 对局操作的门卫：旁观者一律明确拒绝（只读观看，任何操作请求都提示），
     * 非成员且非旁观者（理论不可达）静默忽略。
     */
    private BoardGame gameOfStrict(ServerPlayer player) {
        BoardGame game = gameOf(player);
        if (game == null && spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, "旁观者不能操作对局");
        }
        return game;
    }

    public void onMove(ServerPlayer player, byte x, byte y) {
        BoardGame game = gameOfStrict(player);
        if (game != null) {
            game.onMove(player, x & 0xFF, y & 0xFF);
        }
    }

    public void onPass(ServerPlayer player) {
        BoardGame game = gameOfStrict(player);
        if (game != null) {
            game.onPass(player);
        }
    }

    public void onSurrender(ServerPlayer player) {
        BoardGame game = gameOfStrict(player);
        if (game != null) {
            game.onSurrender(player);
        }
    }

    // ---------------- 生命周期 ----------------

    /**
     * 断线处理：对局中掉线 → 自动托管代打，对局继续；
     * 等待/结算中 → 视为离开（不满 2 人解散）。
     */
    public void onPlayerDisconnect(ServerPlayer player) {
        lobbyQueryTimes.remove(player.getUUID()); // 清理频率限制记录，防 Map 泄漏
        // 旁观者：清理旁观关系后直接返回（无成员会话）
        String specId = spectatorRoomIds.remove(player.getUUID());
        if (specId != null) {
            BoardRoom specRoom = rooms.get(specId);
            if (specRoom != null) {
                specRoom.removeSpectator(player);
            }
            return;
        }
        BoardRoom room = currentRoom(player);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        BoardPhase phase = room.phase();
        if (phase == BoardPhase.WAITING || phase == BoardPhase.SETTLED) {
            removeFromRoom(player, room, true);
        } else {
            int seat = room.seatOf(player);
            if (room.gameType == BoardGameType.GO) {
                // 围棋：断线即结束本局（无托管）；座位转机器人占位，
                // 防止结算后"再来一局"时该座位无人操作（nextGame 会拒绝含机器人的围棋房间）
                room.game.onPlayerQuit(seat);
                room.quitToBot(seat);
            } else {
                room.game.onPlayerQuit(seat); // 五子棋/黑白棋：断线自动托管续玩（保留座位引用供重连）
                // 提示剩余玩家：对手已掉线由托管代打（此前掉线完全不可见，只能从 AI 走子节奏猜测）
                room.broadcast(new NoticeS2C(player.getGameProfile().getName() + " 掉线，由托管代打"));
                room.broadcastState();
            }
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
        BoardRoom room = rooms.get(roomId);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        BoardPhase phase = room.phase();
        if (phase == BoardPhase.WAITING) {
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

    /** 服务端每 tick：对局计时/托管行动 + 空闲房间清理。 */
    public void tick(MinecraftServer server) {
        for (BoardRoom room : new ArrayList<>(rooms.values())) {
            if (room.game != null) {
                try {
                    room.game.tick();
                } catch (Throwable t) {
                    // 防御：单个房间状态机异常（理论由托管引擎等触发）不得崩溃整个服务器——
                    // 记录日志并关闭该房间，其余房间继续正常运行
                    LOGGER.error("棋牌房间 {} tick 异常，房间已关闭", room.id, t);
                    destroyRoom(room, "房间状态异常，已关闭");
                    continue;
                }
            }
            // 全部座位为机器人（真人全部退出转托管，或开局即全机器人）：
            // 不允许全假人房间存在——无在位真人游玩，任何阶段（含结算中）立即结束并关闭房间。
            // 等待中不存在全 bot 房间（创建/补 bot 上限 1 个，真人离开即解散），无需处理。
            if (room.game != null && room.allBot()
                    && room.phase() != BoardPhase.WAITING) {
                destroyRoom(room, "房间内已无真人玩家，本局结束");
                continue;
            }
            if (room.game != null && room.allDisconnected()
                    && room.phase() != BoardPhase.WAITING && room.phase() != BoardPhase.SETTLED) {
                destroyRoom(room, "所有玩家已离线，房间已解散");
                continue;
            }
            if (room.phase() == BoardPhase.SETTLED && room.settledAtMillis > 0
                    && System.currentTimeMillis() - room.settledAtMillis > SETTLED_KEEP_MS) {
                destroyRoom(room, "房间空闲过久，已解散");
            }
        }
    }

    // ---------------- 调试假人（开发端） ----------------

    /** 向等待中的房间添加 1 个调试假人；满 2 人自动开局。 */
    public void addBots(ServerPlayer player, int count) {
        BoardRoom room = currentRoom(player);
        if (room == null) {
            error(player, "你不在任何房间里，请先创建房间");
            return;
        }
        if (room.gameType == BoardGameType.GO) {
            error(player, "围棋暂不支持机器人");
            return;
        }
        if (room.phase() != BoardPhase.WAITING) {
            error(player, "只有等待中的房间可以添加假人");
            return;
        }
        int canAdd = Math.min(Math.max(count, 1), 2 - room.count);
        for (int i = 0; i < canAdd; i++) {
            room.addBot("Bot" + (room.count + 1));
        }
        room.broadcastState();
        if (room.isFull()) {
            startGame(room);
        }
    }

    /** 移除房间内全部假人；对局中移除导致人数不足时结束本局并关闭房间。 */
    public void removeBots(ServerPlayer player) {
        BoardRoom room = currentRoom(player);
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
        if (room.count < 2) {
            destroyRoom(room, "调试假人已移除，房间关闭");
        }
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间快照（按房间码排序，管理命令/大厅列表显示用）。 */
    public List<BoardRoom> roomSnapshot() {
        List<BoardRoom> list = new ArrayList<>(rooms.values());
        list.sort(java.util.Comparator.comparing(r -> r.id));
        return list;
    }

    /** 按房间码查找房间（管理命令用），不存在返回 null。 */
    public BoardRoom roomByCode(String code) {
        return rooms.get(cleanCode(code));
    }

    /** 删除指定房间（通知成员后销毁）；返回错误信息或 null。 */
    public String deleteRoom(String code) {
        BoardRoom room = rooms.get(cleanCode(code));
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
        for (BoardRoom room : new ArrayList<>(rooms.values())) {
            room.broadcast(new RoomClosedS2C("管理员清空了所有房间"));
        }
        rooms.clear();
        playerRoomIds.clear();
        spectatorRoomIds.clear(); // 旁观关系随房间一并清空
        return count;
    }

    /** 大厅房间列表下发（LobbyQueryC2S 响应）：仅公开房间（创建时"公布房间"开启）。
     *  等待中可加入 / 对局中可旁观 / 已结束仅展示。
     *  带频率限制（最小间隔 500ms/玩家）：恶意客户端高频刷包会占用服务端主线程与带宽。 */
    public void sendRoomList(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = lobbyQueryTimes.get(player.getUUID());
        if (last != null && now - last < 500) {
            return;
        }
        lobbyQueryTimes.put(player.getUUID(), now);
        List<BoardRoom> list = new ArrayList<>();
        for (BoardRoom r : roomSnapshot()) {
            if (r.announce) {
                list.add(r);
            }
        }
        String[] codes = new String[list.size()];
        String[] lines = new String[list.size()];
        byte[] statuses = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) {
            BoardRoom r = list.get(i);
            codes[i] = r.id;
            String phaseText = switch (r.phase()) {
                case WAITING -> "等待中";
                case PLAYING -> "对局中";
                case SETTLED -> "已结束";
            };
            String a = r.seatName(0);
            String b = r.seatName(1);
            lines[i] = r.gameType.displayName + sizeText(r.gameType, r.size) + " · "
                    + (a.isEmpty() ? "等待加入…" : a) + " vs " + (b.isEmpty() ? "等待加入…" : b)
                    + " · " + phaseText;
            statuses[i] = (byte) (r.phase() == BoardPhase.WAITING ? 0
                    : r.phase() == BoardPhase.PLAYING ? 1 : 2);
        }
        ServerPlayNetworking.send(player, new RoomListS2C(codes, lines, statuses));
    }

    // ---------------- 旁观（对局开始后只读观看） ----------------

    /** 请求旁观房间；返回错误信息或 null。 */
    public String spectate(ServerPlayer player, String code) {
        if (code == null || code.length() > 16) {
            return "房间码无效";
        }
        BoardRoom room = rooms.get(cleanCode(code));
        if (room == null) {
            return "房间不存在：" + code;
        }
        BoardPhase phase = room.phase();
        if (phase == BoardPhase.WAITING) {
            return "游戏尚未开始，无法旁观";
        }
        if (phase == BoardPhase.SETTLED) {
            return "本局已结束，无法旁观";
        }
        if (currentRoom(player) != null) {
            return "你已在房间中，无法旁观";
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝旁观
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_BOARD);
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
        BoardRoom room = rooms.get(roomId);
        if (room != null) {
            room.removeSpectator(player);
        }
        ServerPlayNetworking.send(player, new RoomClosedS2C("已退出旁观"));
    }

    /** 进入/加入房间前自动退出旁观（避免同时旁观与对局）。 */
    private void leaveSpectateInternal(ServerPlayer player) {
        String roomId = spectatorRoomIds.remove(player.getUUID());
        if (roomId != null) {
            BoardRoom room = rooms.get(roomId);
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
        BoardRoom room = rooms.get(cleanCode(roomCode));
        if (room == null) {
            return "房间不存在：" + roomCode;
        }
        if (currentRoom(target) != null) {
            return target.getGameProfile().getName() + " 已在其他房间";
        }
        // 跨游戏防护：强制入房同样要求退出其他小游戏
        GameInfo other = GameRegistry.busyInOtherGame(target, GameRegistry.GAME_BOARD);
        if (other != null) {
            return target.getGameProfile().getName() + " 正在【" + other.displayName() + "】中，无法强制加入";
        }
        if (room.isFull()) {
            return "房间已满";
        }
        if (room.phase() != BoardPhase.WAITING) {
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

    private void startGame(BoardRoom room) {
        room.game = switch (room.gameType) {
            case OTHELLO -> new OthelloGame(room);
            case GOMOKU -> new GomokuGame(room);
            case GO -> new GoGame(room);
        };
        room.game.begin();
        // 全服广播：房间已开始，其他玩家可点击旁观
        ServerPlayer host = room.members[0];
        if (host != null && host.getServer() != null) {
            Component msg = Component.literal("[棋牌] 房间 " + room.id + "（" + room.gameType.displayName + "）已开始，")
                    .append(Component.literal("[点击旁观]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames spectate " + room.id))));
            host.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** 将玩家移出房间；不满 2 人时解散房间并通知剩余玩家。 */
    private void removeFromRoom(ServerPlayer player, BoardRoom room, boolean notifyOthers) {
        room.removePlayer(player);
        playerRoomIds.remove(player.getUUID());
        if (room.count < 2) {
            if (notifyOthers) {
                room.broadcast(new RoomClosedS2C("有玩家离开，房间已解散"));
            }
            destroyRoomInternal(room);
        } else {
            room.broadcastState();
        }
        // 离开者本人回到大厅（空 reason 不弹提示）
        if (BoardRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new RoomClosedS2C(""));
        }
    }

    private void destroyRoom(BoardRoom room, String reason) {
        room.broadcast(new RoomClosedS2C(reason));
        destroyRoomInternal(room);
    }

    private void destroyRoomInternal(BoardRoom room) {
        rooms.remove(room.id);
        for (int i = 0; i < room.count; i++) {
            ServerPlayer member = room.members[i];
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
        String prefix = GameRegistry.PREFIX_BOARD + "-";
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

    /** 房间码规范化：去掉本游戏前缀（兼容 "BD-XXXXX" 完整码与裸码 "XXXXX" 两种输入）。 */
    private static String cleanCode(String code) {
        String norm = code == null ? "" : code.toUpperCase().trim();
        return norm.startsWith(GameRegistry.PREFIX_BOARD + "-") ? norm.substring(3) : norm;
    }

    /** 当前房间总数（菜单统计用）。 */
    public int roomCount() {
        return rooms.size();
    }

    /** 在线人数统计（房间成员 + 旁观者，菜单统计用）。 */
    public int playerCount() {
        int count = 0;
        for (BoardRoom room : rooms.values()) {
            count += room.count + room.spectators.size();
        }
        return count;
    }

    public BoardRoom currentRoom(ServerPlayer player) {
        String roomId = playerRoomIds.get(player.getUUID());
        return roomId == null ? null : rooms.get(roomId);
    }

    /** 玩家正在旁观哪个房间（无则 null）。 */
    public String spectatingRoomId(ServerPlayer player) {
        return spectatorRoomIds.get(player.getUUID());
    }

    /** 玩家正在旁观的房间对象；未旁观或房间已销毁返回 null。 */
    public BoardRoom spectatorRoomOf(ServerPlayer player) {
        String id = spectatorRoomIds.get(player.getUUID());
        return id == null ? null : rooms.get(id);
    }

    public BoardGame gameOf(ServerPlayer player) {
        BoardRoom room = currentRoom(player);
        return room == null ? null : room.game;
    }

    private static String sizeText(BoardGameType type, int size) {
        return type == BoardGameType.GO ? size + "路" : " · " + size + "×" + size + "盘";
    }

    private static void error(ServerPlayer player, String message) {
        if (BoardRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new NoticeS2C(message));
        }
    }
}
