package io.wifi.cards.doudizhu.manager;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
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
import net.minecraft.network.chat.MutableComponent;
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
 * 全局内存管理器（单例）：所有活跃房间 + 玩家所属房间映射。
 * <p>纯内存存储：服务器重启即全部清空，无任何持久化。</p>
 * <p>生命周期：WAITING（满 3 人自动开局）→ 对局 → SETTLED（60 秒无操作自动销毁 /
 * 有人离开则解散；"再来一局"保持房间不散直接重开）。</p>
 */
public final class DdzMemoryManager {
    public static final DdzMemoryManager INSTANCE = new DdzMemoryManager();

    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    /** 结算后无人操作，保留的 tick 数（60 秒）。 */
    private static final long SETTLED_KEEP_MS = 60_000;

    /** 同时存在的房间数上限（防恶意客户端洪泛创建）。 */
    private static final int MAX_ROOMS = 64;

    /** 房间码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final Random RANDOM = new Random();

    private final Map<String, DdzRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoomIds = new ConcurrentHashMap<>();
    /** 旁观者所属房间映射（旁观只读观看，不占座位、无成员会话）。 */
    private final Map<UUID, String> spectatorRoomIds = new ConcurrentHashMap<>();

    private DdzMemoryManager() {
    }

    // ---------------- 房间操作 ----------------

    public void createRoom(MinecraftServer server, ServerPlayer player, boolean flowerMode, DdzRuleSet ruleSet, boolean announce, int botCount) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        if (currentRoom(player) != null) {
            error(player, Component.translatable("wifi_card_games.ddz.error.in_room"));
            return;
        }
        // 跨游戏防护：一个玩家同时只能在一个小游戏中（房间成员或旁观），
        // 防止开着斗地主牌局又加入 UNO/棋类房间造成双线对局状态混乱
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_DOUDIZHU);
        if (other != null) {
            error(player, Component.translatable("wifi_card_games.common.error.busy_other_game",
                    Component.translatable(other.displayName())));
            return;
        }
        // 防御：房间总数上限，防止恶意客户端洪泛创建房间耗尽内存
        if (rooms.size() >= MAX_ROOMS) {
            error(player, Component.translatable("wifi_card_games.ddz.error.too_many_rooms"));
            return;
        }
        DdzRoom room = new DdzRoom(generateCode(), flowerMode, ruleSet, announce);
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
            Component message = Component.translatable("wifi_card_games.ddz.chat.room_created_broadcast",
                            player.getGameProfile().getName(), room.id,
                            Component.translatable(flowerMode
                                    ? "wifi_card_games.ddz.mode.flower" : "wifi_card_games.ddz.mode.classic"),
                            Component.translatable(ruleSet.displayName()))
                    .append(clickJoin(room.id));
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
        // 无大厅 UI：直接提示房主房间已创建（可点击查看列表）
        player.sendSystemMessage(Component.translatable("wifi_card_games.ddz.chat.room_created", room.id)
                .append(click(Component.translatable("wifi_card_games.common.click.room_list"),
                        "/cardgames rooms")));
    }

    public void joinRoom(ServerPlayer player, String code) {
        // 先退出旁观状态（旁观者建房/入房自动退出旁观）
        leaveSpectateInternal(player);
        // 防御：房间码长度上限（完整码前缀-5位共 8 字符，杜绝超长输入）
        if (code == null || code.length() > 16) {
            error(player, Component.translatable("wifi_card_games.ddz.error.bad_code"));
            return;
        }
        DdzRoom room = rooms.get(fullCode(code));
        if (room == null) {
            error(player, Component.translatable("wifi_card_games.ddz.error.room_not_found", code));
            return;
        }
        if (currentRoom(player) != null) {
            error(player, Component.translatable("wifi_card_games.ddz.error.in_room"));
            return;
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝加入
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_DOUDIZHU);
        if (other != null) {
            error(player, Component.translatable("wifi_card_games.common.error.busy_other_game",
                    Component.translatable(other.displayName())));
            return;
        }
        if (room.isFull()) {
            error(player, Component.translatable("wifi_card_games.ddz.error.room_full"));
            return;
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            error(player, Component.translatable("wifi_card_games.ddz.error.game_started"));
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
     *   <li>等待/结算中：正常离开（不满 3 人解散房间）</li>
     *   <li>对局中：退出游戏，座位转机器人托管，对局继续；房间已无真人则关闭房间</li>
     * </ul>
     */
    public void leaveRoom(ServerPlayer player) {
        // 旁观者：/doudizhu leave 等同退出旁观
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            leaveSpectate(player);
            return;
        }
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, Component.translatable("wifi_card_games.ddz.error.not_in_room"));
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
        ServerPlayNetworking.send(player, new RoomClosedS2C(
                Component.translatable("wifi_card_games.ddz.error.quit_to_bot")));
        room.broadcastState();
        if (!room.hasRealPlayer()) {
            destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.no_real_player"));
        }
    }

    /** 再来一局：结算后重置房间状态并重新发牌（房间不散）。 */
    public void nextGame(ServerPlayer player) {
        // 严格限制：旁观者无权开始新对局（新局只能由成员触发）
        if (spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, Component.translatable("wifi_card_games.ddz.error.spectator_no_start"));
            return;
        }
        DdzRoom room = currentRoom(player);
        if (room == null || room.game == null || room.phase() != DdzGamePhase.SETTLED) {
            return;
        }
        room.settledAtMillis = -1;
        room.game.start();
    }

    // ---------------- 对局操作转发 ----------------

    /**
     * 对局操作的门卫：旁观者一律明确拒绝（只读观看，任何操作请求都提示），
     * 非成员且非旁观者（理论不可达）静默忽略。
     */
    private DdzGame gameOfStrict(ServerPlayer player) {
        DdzGame game = gameOf(player);
        if (game == null && spectatorRoomIds.containsKey(player.getUUID())) {
            error(player, Component.translatable("wifi_card_games.ddz.error.spectator_no_operate"));
        }
        return game;
    }

    public void onCall(ServerPlayer player, byte score) {
        DdzGame game = gameOfStrict(player);
        if (game != null) {
            game.onCall(player, score);
        }
    }

    public void onRob(ServerPlayer player, boolean rob) {
        DdzGame game = gameOfStrict(player);
        if (game != null) {
            game.onRob(player, rob);
        }
    }

    public void onPlayCards(ServerPlayer player, int[] cardIds) {
        DdzGame game = gameOfStrict(player);
        if (game != null) {
            // 防御：单次出牌最多 21 张（花牌模式地主 17+4）；超长数组直接拒绝，
            // 防止恶意客户端发送超大数组造成内存分配与校验开销
            if (cardIds.length > 21) {
                error(player, Component.translatable("wifi_card_games.ddz.error.bad_play_count"));
                return;
            }
            // 防御：空数组不允许（"不出"必须走 PassC2S，防止伪装）
            if (cardIds.length == 0) {
                error(player, Component.translatable("wifi_card_games.ddz.error.select_cards"));
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
            // 防御：过滤后为空（全部非法 id）等价于"不出"，拒绝——
            // 空数组不允许的语义必须保持，防伪装（"不出"必须走 PassC2S）
            if (cards.isEmpty()) {
                error(player, Component.translatable("wifi_card_games.ddz.error.select_cards"));
                return;
            }
            game.onPlay(player, cards);
        }
    }

    public void onPass(ServerPlayer player) {
        DdzGame game = gameOfStrict(player);
        if (game != null) {
            game.onPlay(player, null);
        }
    }

    public void onReveal(ServerPlayer player) {
        DdzGame game = gameOfStrict(player);
        if (game != null) {
            game.onReveal(player);
        }
    }

    /** 出牌历史请求（历史界面打开时）：下发本局完整出牌历史。 */
    public void onHistoryRequest(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        if (room != null && room.game != null) {
            room.game.sendHistory(room.seatOf(player));
            return;
        }
        // 旁观者：按其旁观房间下发历史
        String specId = spectatorRoomIds.get(player.getUUID());
        if (specId != null) {
            DdzRoom specRoom = rooms.get(specId);
            if (specRoom != null && specRoom.game != null) {
                specRoom.game.sendHistoryToSpectator(player);
            }
        }
    }

    public void setTrust(ServerPlayer player, boolean enabled) {
        DdzGame game = gameOfStrict(player);
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
        // 旁观者：清理旁观关系后直接返回（无成员会话）
        String specId = spectatorRoomIds.remove(player.getUUID());
        if (specId != null) {
            DdzRoom specRoom = rooms.get(specId);
            if (specRoom != null) {
                specRoom.removeSpectator(player);
            }
            return;
        }
        DdzRoom room = currentRoom(player);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase == DdzGamePhase.WAITING || phase == DdzGamePhase.SETTLED) {
            removeFromRoom(player, room, true);
        } else {
            // 对局中掉线：座位转机器人托管，对局继续；广播状态让其余玩家看到离线标记
            room.game.onPlayerDisconnect(room.seatOf(player));
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
        DdzRoom room = rooms.get(roomId);
        if (room == null) {
            playerRoomIds.remove(player.getUUID());
            return;
        }
        DdzGamePhase phase = room.phase();
        if (phase == DdzGamePhase.WAITING) {
            // 等待中断线已按离开处理（会话已清除），理论不会到达；防御性兜底关闭房间
            room.broadcast(new RoomClosedS2C(Component.translatable(
                    "wifi_card_games.ddz.error.reconnect_old_room", player.getGameProfile().getName())));
            destroyRoomInternal(room);
            return;
        }
        // 对局中（PLAYING）掉线托管续玩、或对局已结算（SETTLED）后重连：
        // 会话仍有效，正常替换连接引用并同步快照（SETTLED 重连可看结算结果，
        // 房间本可保留至 60 秒空闲销毁——不能销毁房间把其他成员一起踢回大厅）
        int seat = room.replacePlayerByUuid(player.getUUID(), player);
        if (seat < 0) {
            // 找不到对应座位（理论不会发生）→ 防御性关闭房间
            room.broadcast(new RoomClosedS2C(Component.translatable(
                    "wifi_card_games.ddz.error.reconnect_failed", player.getGameProfile().getName())));
            destroyRoomInternal(room);
            return;
        }
        room.game.onPlayerReconnect(seat);
        room.broadcast(new NoticeS2C(Component.translatable(
                "wifi_card_games.ddz.info.reconnected", player.getGameProfile().getName())));
        room.broadcastState();
        room.game.syncTo(seat);
    }

    /** 服务端每 tick：对局计时 + 空闲房间清理。 */
    public void tick(MinecraftServer server) {
        for (DdzRoom room : new ArrayList<>(rooms.values())) {
            if (room.game != null) {
                try {
                    room.game.tick();
                } catch (Throwable t) {
                    // 防御：单个房间状态机异常（理论由托管引擎等触发）不得崩溃整个服务器——
                    // 记录日志并关闭该房间，其余房间继续正常运行
                    LOGGER.error("斗地主房间 {} tick 异常，房间已关闭", room.id, t);
                    destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.room_broken"));
                    continue;
                }
            }
            // 全部座位为机器人（真人全部退出转托管，或开局即全机器人）：
            // 无在位真人游玩，结束本局并关闭房间。手动托管（座位仍为真人）不在此列。
            if (room.game != null && room.allBot()
                    && room.phase() != DdzGamePhase.WAITING && room.phase() != DdzGamePhase.SETTLED) {
                destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.all_bot_ended"));
                continue;
            }
            if (room.game != null && room.allDisconnected()
                    && room.phase() != DdzGamePhase.WAITING && room.phase() != DdzGamePhase.SETTLED) {
                destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.all_offline"));
                continue;
            }
            if (room.phase() == DdzGamePhase.SETTLED && room.settledAtMillis > 0
                    && System.currentTimeMillis() - room.settledAtMillis > SETTLED_KEEP_MS) {
                destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.room_idle"));
            }
        }
    }

    // ---------------- 调试假人（开发端） ----------------

    /** 向等待中的房间添加 1~2 个调试假人；满 3 人自动开局。 */
    public void addBots(ServerPlayer player, int count) {
        DdzRoom room = currentRoom(player);
        if (room == null) {
            error(player, Component.translatable("wifi_card_games.ddz.error.not_in_room_create"));
            return;
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            error(player, Component.translatable("wifi_card_games.ddz.error.bot_only_waiting"));
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
            error(player, Component.translatable("wifi_card_games.ddz.error.not_in_room"));
            return;
        }
        if (room.botCount() == 0) {
            error(player, Component.translatable("wifi_card_games.ddz.error.no_bots"));
            return;
        }
        room.removeBots();
        room.broadcastState();
        if (room.size < 3) {
            destroyRoom(room, Component.translatable("wifi_card_games.ddz.error.bots_removed_closed"));
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
        return rooms.get(fullCode(code));
    }

    /** 删除指定房间（通知成员后销毁）；返回错误消息组件或 null。 */
    public Component deleteRoom(String code) {
        DdzRoom room = rooms.get(fullCode(code));
        if (room == null) {
            return Component.translatable("wifi_card_games.ddz.error.room_not_found", code);
        }
        room.broadcast(new RoomClosedS2C(Component.translatable("wifi_card_games.ddz.error.admin_deleted")));
        destroyRoomInternal(room);
        return null;
    }

    /** 清空全部房间（通知所有成员），返回删除数量。 */
    public int clearAllRooms() {
        int count = rooms.size();
        for (DdzRoom room : new ArrayList<>(rooms.values())) {
            room.broadcast(new RoomClosedS2C(Component.translatable("wifi_card_games.ddz.error.admin_cleared")));
        }
        rooms.clear();
        playerRoomIds.clear();
        spectatorRoomIds.clear(); // 旁观关系随房间一并清空
        return count;
    }

    // ---------------- 旁观（对局开始后只读观看） ----------------

    /** 请求旁观房间；返回错误消息组件或 null。 */
    public Component spectate(ServerPlayer player, String code) {
        if (code == null || code.length() > 16) {
            return Component.translatable("wifi_card_games.ddz.error.bad_code");
        }
        DdzRoom room = rooms.get(fullCode(code));
        if (room == null) {
            return Component.translatable("wifi_card_games.ddz.error.room_not_found", code);
        }
        DdzGamePhase phase = room.phase();
        if (phase == DdzGamePhase.WAITING) {
            return Component.translatable("wifi_card_games.ddz.error.not_started");
        }
        if (phase == DdzGamePhase.SETTLED) {
            return Component.translatable("wifi_card_games.ddz.error.game_settled");
        }
        if (currentRoom(player) != null) {
            return Component.translatable("wifi_card_games.ddz.error.in_room_no_spectate");
        }
        // 跨游戏防护：其他小游戏有会话（成员或旁观）时拒绝旁观
        GameInfo other = GameRegistry.busyInOtherGame(player, GameRegistry.GAME_DOUDIZHU);
        if (other != null) {
            return Component.translatable("wifi_card_games.common.error.busy_spectate",
                    Component.translatable(other.displayName()));
        }
        String existing = spectatorRoomIds.get(player.getUUID());
        if (existing != null) {
            if (existing.equals(room.id)) {
                // 已在旁观同一房间：视为重新打开旁观界面（重发完整快照，客户端回到 GameScreen）
                room.broadcastState();
                room.game.syncToSpectator(player);
                return null;
            }
            return Component.translatable("wifi_card_games.ddz.error.spectating_other");
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
            error(player, Component.translatable("wifi_card_games.ddz.error.not_spectating"));
            return;
        }
        DdzRoom room = rooms.get(roomId);
        if (room != null) {
            room.removeSpectator(player);
        }
        ServerPlayNetworking.send(player, new RoomClosedS2C(
                Component.translatable("wifi_card_games.ddz.info.left_spectate")));
    }

    /** 进入/加入房间前自动退出旁观（避免同时旁观与对局）。 */
    private void leaveSpectateInternal(ServerPlayer player) {
        String roomId = spectatorRoomIds.remove(player.getUUID());
        if (roomId != null) {
            DdzRoom room = rooms.get(roomId);
            if (room != null) {
                room.removeSpectator(player);
            }
        }
    }

    /**
     * 强制将指定玩家加入房间（调试用，无视客户端操作）。
     *
     * @return 错误消息组件；null 表示成功
     */
    public Component forceJoin(ServerPlayer target, String roomCode) {
        // 先退出旁观状态（强制入房同样要求退出旁观）
        leaveSpectateInternal(target);
        DdzRoom room = rooms.get(fullCode(roomCode));
        if (room == null) {
            return Component.translatable("wifi_card_games.ddz.error.room_not_found", roomCode);
        }
        if (currentRoom(target) != null) {
            return Component.translatable("wifi_card_games.ddz.error.force_in_other_room",
                    target.getGameProfile().getName());
        }
        // 跨游戏防护：强制入房同样要求退出其他小游戏
        GameInfo other = GameRegistry.busyInOtherGame(target, GameRegistry.GAME_DOUDIZHU);
        if (other != null) {
            return Component.translatable("wifi_card_games.common.error.force_join_busy",
                    target.getGameProfile().getName(), Component.translatable(other.displayName()));
        }
        if (room.isFull()) {
            return Component.translatable("wifi_card_games.ddz.error.room_full");
        }
        if (room.phase() != DdzGamePhase.WAITING) {
            return Component.translatable("wifi_card_games.ddz.error.game_started");
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
        // 全服广播：房间已开始，其他玩家可点击旁观
        ServerPlayer host = room.members[0];
        if (host != null && host.getServer() != null) {
            Component msg = Component.translatable("wifi_card_games.ddz.chat.room_started", room.id)
                    .append(click(Component.translatable("wifi_card_games.common.click.spectate"),
                            "/cardgames spectate " + room.id));
            host.getServer().getPlayerList().broadcastSystemMessage(msg, false);
        }
    }

    /** 将玩家移出房间；不满 3 人时解散房间并通知剩余玩家。 */
    private void removeFromRoom(ServerPlayer player, DdzRoom room, boolean notifyOthers) {
        room.removePlayer(player);
        playerRoomIds.remove(player.getUUID());
        if (room.size < 3) {
            if (notifyOthers) {
                room.broadcast(new RoomClosedS2C(Component.translatable("wifi_card_games.ddz.error.player_left")));
            }
            destroyRoomInternal(room);
        } else {
            room.broadcastState();
        }
        // 离开者本人回到大厅（空组件不弹提示）
        if (DdzRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new RoomClosedS2C(Component.empty()));
        }
    }

    private void destroyRoom(DdzRoom room, Component reason) {
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
        for (ServerPlayer sp : room.spectators) {
            spectatorRoomIds.remove(sp.getUUID());
        }
    }

    private String generateCode() {
        // 统一房间号格式：前缀-5位码（前缀见 GameRegistry，全服唯一区分所属游戏）
        String prefix = GameRegistry.PREFIX_DOUDIZHU + "-";
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

    /** 房间码规范化：去掉本游戏前缀（兼容 "DZ-XXXXX" 完整码与裸码 "XXXXX" 两种输入）。 */
    /** 房间码规范化：统一为完整码（补上前缀），兼容裸码与完整码输入（rooms 的 key 为完整码）。 */
    private static String fullCode(String code) {
        String norm = code == null ? "" : code.toUpperCase().trim();
        return norm.startsWith(GameRegistry.PREFIX_DOUDIZHU + "-") ? norm : GameRegistry.PREFIX_DOUDIZHU + "-" + norm;
    }

    /** 当前房间总数（菜单统计用）。 */
    public int roomCount() {
        return rooms.size();
    }

    /** 在线人数统计（房间成员 + 旁观者，菜单统计用）。 */
    public int playerCount() {
        int count = 0;
        for (DdzRoom room : rooms.values()) {
            count += room.size + room.spectators.size();
        }
        return count;
    }

    public DdzRoom currentRoom(ServerPlayer player) {
        String roomId = playerRoomIds.get(player.getUUID());
        return roomId == null ? null : rooms.get(roomId);
    }

    /** 玩家正在旁观哪个房间（无则 null）。 */
    public String spectatingRoomId(ServerPlayer player) {
        return spectatorRoomIds.get(player.getUUID());
    }

    /** 玩家正在旁观的房间对象；未旁观或房间已销毁返回 null。 */
    public DdzRoom spectatorRoomOf(ServerPlayer player) {
        String id = spectatorRoomIds.get(player.getUUID());
        return id == null ? null : rooms.get(id);
    }

    public DdzGame gameOf(ServerPlayer player) {
        DdzRoom room = currentRoom(player);
        return room == null ? null : room.game;
    }

    private static void error(ServerPlayer player, Component message) {
        if (DdzRoom.isConnected(player)) {
            ServerPlayNetworking.send(player, new NoticeS2C(message));
        }
    }

    /** 可点击命令文本（绿色 + RUN_COMMAND）。 */
    private static MutableComponent click(Component label, String command) {
        return label.copy().withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    /** "[点击加入]" 可点击文本（绑定 accept 命令）。 */
    private static MutableComponent clickJoin(String roomId) {
        return click(Component.translatable("wifi_card_games.common.click.join"), "/cardgames accept " + roomId);
    }
}
