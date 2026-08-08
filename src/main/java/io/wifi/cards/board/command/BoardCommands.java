package io.wifi.cards.board.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.board.network.BoardPackets.OpenLobbyS2C;
import io.wifi.cards.common.command.CardGamesCommands;
import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.manager.BoardMemoryManager;
import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.DebugUiS2C;
import io.wifi.cards.board.othello.rule.OthelloRules;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 棋类服务端命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/chess</code>：打开棋类大厅（服务端校验后发 OpenLobbyS2C，客户端主线程打开 UI）</li>
 *   <li><code>/chess accept &lt;房间码&gt;</code>：加入房间（聊天点击消息触发）</li>
 *   <li><code>/chess invite &lt;玩家&gt;</code>：房主邀请玩家（被邀请者收到可点击消息）</li>
 *   <li><code>/chess leave</code>：离开房间</li>
 *   <li><code>/chess spectate &lt;房间码&gt;</code> / <code>unspectate</code>：旁观/退出旁观</li>
 *   <li><code>/chess debug ...</code>：调试命令（OP 权限，开发端测试用）</li>
 * </ul>
 */
public final class BoardCommands {
    private static final Random RANDOM = new Random();

    private BoardCommands() {
    }

    public static void registerServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("chess")
                    .executes(ctx -> openLobby(ctx.getSource()))
                    .then(Commands.literal("accept")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> accept(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("invite")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                    .then(Commands.literal("leave")
                            .executes(ctx -> leave(ctx.getSource())))
                    .then(Commands.literal("spectate")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> spectate(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("unspectate")
                            .executes(ctx -> unspectate(ctx.getSource())))
                    .then(Commands.literal("debug").requires(src -> src.hasPermission(2))
                            .then(Commands.literal("bots")
                                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 1))
                                            .executes(ctx -> debugBots(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"))))
                                    .then(Commands.literal("remove")
                                            .executes(ctx -> debugBotsRemove(ctx.getSource()))))
                            .then(Commands.literal("move")
                                    .then(Commands.argument("x", IntegerArgumentType.integer(0, 18))
                                            .then(Commands.argument("y", IntegerArgumentType.integer(0, 18))
                                                    .executes(ctx -> debugMove(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"))))))
                            .then(Commands.literal("pass")
                                    .executes(ctx -> debugPass(ctx.getSource())))
                            .then(Commands.literal("surrender")
                                    .executes(ctx -> debugSurrender(ctx.getSource())))
                            .then(Commands.literal("ui")
                                    .executes(ctx -> debugUi(ctx.getSource())))
                            .then(Commands.literal("forcejoin")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), null))
                                            .then(Commands.argument("code", StringArgumentType.word())
                                                    .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "code"))))))
                            .then(Commands.literal("rooms")
                                    .executes(ctx -> debugRooms(ctx.getSource())))
                            .then(Commands.literal("room")
                                    .then(Commands.argument("code", StringArgumentType.word())
                                            .executes(ctx -> debugRoom(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "code")))))
                            .then(Commands.literal("roomdelete")
                                    .then(Commands.argument("code", StringArgumentType.word())
                                            .executes(ctx -> debugRoomDelete(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "code")))))
                            .then(Commands.literal("roomclear")
                                    .executes(ctx -> debugRoomClear(ctx.getSource())))
                            .then(Commands.literal("kick")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> debugKick(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")))))));
        });
    }

    // ---------------- 普通命令 ----------------

    /**
     * 打开 UI：/chess。
     * <ul>
     *   <li>对局中/已结束：发房间状态 + 完整对局快照，客户端重新打开棋盘界面</li>
     *   <li>旁观中：重发旁观快照（房间状态 + 对局），客户端重新打开旁观界面</li>
     *   <li>等待中或不在房间：发 OpenLobbyS2C 打开大厅</li>
     * </ul>
     */
    private static int openLobby(CommandSourceStack source) throws CommandSyntaxException {
        openLobby(source.getPlayerOrException());
        return 1;
    }

    /** 打开该游戏 UI（/chess 与 /cardgames open 共用）：
     * 对局中重发快照 / 旁观中重发旁观快照 / 否则发 OpenLobbyS2C 打开大厅。 */
    public static void openLobby(ServerPlayer player) {
        BoardMemoryManager m = BoardMemoryManager.INSTANCE;
        BoardRoom room = m.currentRoom(player);
        if (room != null && room.phase() != BoardPhase.WAITING) {
            // 对局中/已结束：先同步房间信息（mySeat/成员），再发完整快照，客户端打开棋盘界面
            room.broadcastState();
            room.game.syncTo(room.seatOf(player));
            return;
        }
        // 旁观者：关闭 UI 后用 /chess 重新打开应回到旁观界面（而非大厅）
        String specId = m.spectatingRoomId(player);
        if (specId != null) {
            BoardRoom specRoom = m.roomByCode(specId);
            if (specRoom != null && specRoom.game != null) {
                specRoom.broadcastState(); // 旁观者收到 mySeat=-1 的房间状态
                specRoom.game.syncToSpectator(player); // 对局快照
                return;
            }
        }
        // 等待中/未进房：打开大厅 UI（房间列表通过 /cardgames rooms 命令查看）
        ServerPlayNetworking.send(player, new OpenLobbyS2C());
    }

    /** 接受邀请加入：/chess accept <房间码>（服务端直接处理；聊天点击消息触发）。 */
    private static int accept(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.joinRoom(player, code);
        return 1;
    }

    /** 旁观房间：/chess spectate <房间码>（对局开始后的只读观看）。 */
    private static int spectate(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Component error = BoardMemoryManager.INSTANCE.spectate(player, code);
        if (error != null) {
            source.sendFailure(error);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.info.spectating", code), false);
        return 1;
    }

    /** 退出旁观：/chess unspectate。 */
    private static int unspectate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.leaveSpectate(player);
        return 1;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        Component error = invite(owner, target);
        if (error != null) {
            source.sendFailure(error);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.info.invite_sent",
                target.getGameProfile().getName()), false);
        return 1;
    }

    /** 邀请玩家加入自己所在房间（/chess invite 与 /cardgames invite 共用）；
     *  成功时向目标发送可点击邀请消息，返回错误消息或 null。 */
    public static Component invite(ServerPlayer owner, ServerPlayer target) {
        BoardRoom room = BoardMemoryManager.INSTANCE.currentRoom(owner);
        if (room == null) {
            return Component.translatable("wifi_card_games.board.error.not_in_room_create");
        }
        if (room.isFull() || room.phase() != BoardPhase.WAITING) {
            return Component.translatable("wifi_card_games.board.error.invite_waiting_only");
        }
        Component message = Component.translatable("wifi_card_games.board.chat.invite",
                        owner.getGameProfile().getName(), room.id,
                        Component.translatable(room.gameType.displayName))
                .append(Component.translatable("wifi_card_games.common.click.accept_invite").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames accept " + room.id))));
        target.sendSystemMessage(message);
        return null;
    }

    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.leaveRoom(player);
        return 1;
    }

    // ---------------- 调试命令（OP） ----------------

    /** 添加调试假人：/chess debug bots 1（等待中的房间，满 2 人自动开局）。 */
    private static int debugBots(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.addBots(player, count);
        return 1;
    }

    /** 移除调试假人：/chess debug bots remove。 */
    private static int debugBotsRemove(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.removeBots(player);
        return 1;
    }

    /** 指挥当前轮到者落子（真人与假人均可）：/chess debug move <x> <y>。 */
    private static int debugMove(CommandSourceStack source, int x, int y) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onMove(null, x, y);
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.move_commanded",
                game.currentSeat(), x, y), false);
        return 1;
    }

    /** 指挥当前轮到者停一手（真人与假人均可）：/chess debug pass。 */
    private static int debugPass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onPass(null);
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.pass_commanded",
                game.currentSeat()), false);
        return 1;
    }

    /** 指挥当前轮到者认输：/chess debug surrender。 */
    private static int debugSurrender(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onSurrender(null);
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.surrender_commanded",
                game.currentSeat()), false);
        return 1;
    }

    /** 打开调试旁观界面：/chess debug ui。无房间，发送随机虚拟对局数据供旁观 UI 检查（标题带"（调试）"）。
     *  对局中/旁观中拒绝：虚拟数据会覆盖真实对局状态。 */
    private static int debugUi(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager m = BoardMemoryManager.INSTANCE;
        if (m.currentRoom(player) != null || m.spectatingRoomId(player) != null) {
            source.sendFailure(Component.translatable("wifi_card_games.board.error.debug_ui_busy"));
            return 0;
        }
        ServerPlayNetworking.send(player, randomDebugUi());
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.ui_opened"), false);
        return 1;
    }

    /**
     * 生成随机虚拟对局数据（无房间，纯 UI 调试）：
     * 随机选游戏类型与尺寸；黑白棋从初始四子起随机合法落子若干手（真实局面），
     * 五子棋/围棋随机撒子（约 30% 密度）；假玩家名 + 随机当前行动座位。
     */
    private static DebugUiS2C randomDebugUi() {
        BoardGameType[] types = BoardGameType.values();
        BoardGameType type = types[RANDOM.nextInt(types.length)];
        int size = type.sizeOptions[RANDOM.nextInt(type.sizeOptions.length)];
        byte[] board = new byte[size * size];
        if (type == BoardGameType.OTHELLO) {
            board = OthelloRules.initialBoard(size);
            byte player = OthelloRules.BLACK;
            for (int i = 0; i < 12; i++) {
                List<int[]> moves = OthelloRules.legalMoves(board, size, player);
                if (moves.isEmpty()) {
                    player = (byte) (3 - player);
                    moves = OthelloRules.legalMoves(board, size, player);
                    if (moves.isEmpty()) {
                        break;
                    }
                }
                int[] m = moves.get(RANDOM.nextInt(moves.size()));
                OthelloRules.applyMove(board, size, m[0], m[1], player);
                player = (byte) (3 - player);
            }
        } else {
            int stones = size * size * 3 / 10;
            for (int i = 0; i < stones; i++) {
                int x = RANDOM.nextInt(size);
                int y = RANDOM.nextInt(size);
                if (board[y * size + x] == 0) {
                    board[y * size + x] = (byte) (RANDOM.nextBoolean() ? 1 : 2);
                }
            }
        }
        return new DebugUiS2C((byte) type.ordinal(), (byte) size, board,
                new String[]{"DebugA", "DebugB"}, (byte) RANDOM.nextInt(2));
    }

    /** 强制将指定玩家加入房间：/chess debug forcejoin <玩家> [房间码]（缺省用执行者所在房间）。 */
    private static int debugForceJoin(CommandSourceStack source, ServerPlayer target, String roomCode) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();
        final String code;
        if (roomCode != null) {
            code = roomCode;
        } else {
            BoardRoom room = BoardMemoryManager.INSTANCE.currentRoom(executor);
            if (room == null) {
                source.sendFailure(Component.translatable("wifi_card_games.board.error.no_code_no_room"));
                return 0;
            }
            code = room.id;
        }
        Component error = BoardMemoryManager.INSTANCE.forceJoin(target, code);
        if (error != null) {
            source.sendFailure(error);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.force_joined",
                target.getGameProfile().getName(), code), false);
        return 1;
    }

    /** 强制指定玩家退出游戏（对局中座位转机器人托管；房间无真人则关闭）：/chess debug kick <玩家>。 */
    private static int debugKick(CommandSourceStack source, ServerPlayer target) {
        if (BoardMemoryManager.INSTANCE.currentRoom(target) == null) {
            source.sendFailure(Component.translatable("wifi_card_games.board.error.not_in_any_room",
                    target.getGameProfile().getName()));
            return 0;
        }
        BoardMemoryManager.INSTANCE.leaveRoom(target);
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.kicked",
                target.getGameProfile().getName()), false);
        return 1;
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间列表（每行带 [显示具体信息][删除房间] 快捷点击）：/chess debug rooms。 */
    private static int debugRooms(CommandSourceStack source) {
        List<BoardRoom> rooms = BoardMemoryManager.INSTANCE.roomSnapshot();
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.rooms.header", rooms.size()), false);
        for (BoardRoom room : rooms) {
            final String code = room.id;
            Component line = Component.translatable("wifi_card_games.board.rooms.line",
                            room.id, Component.translatable(room.gameType.displayName),
                            room.gameType == BoardGameType.GO
                                    ? Component.translatable("wifi_card_games.board.size_go_short", room.size)
                                    : Component.empty(),
                            room.count,
                            Component.translatable(phaseNameKey(room.phase())))
                    .append(click("wifi_card_games.board.rooms.detail_click", "/chess debug room " + code,
                            ChatFormatting.GREEN))
                    .append(click("wifi_card_games.board.rooms.delete_click", "/chess debug roomdelete " + code,
                            ChatFormatting.RED));
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** 房间详细信息（成员：真人 + 机器人，含在线状态）：/chess debug room <房间码>。 */
    private static int debugRoom(CommandSourceStack source, String code) {
        BoardRoom room = BoardMemoryManager.INSTANCE.roomByCode(code);
        if (room == null) {
            source.sendFailure(Component.translatable("wifi_card_games.board.error.room_not_found", code));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.room.header",
                room.id, Component.translatable(room.gameType.displayName),
                room.gameType == BoardGameType.GO
                        ? Component.translatable("wifi_card_games.board.size_go", room.size) : Component.empty(),
                Component.translatable(phaseNameKey(room.phase()))), false);
        for (int i = 0; i < 2; i++) {
            String name = room.seatName(i);
            if (name.isEmpty()) {
                continue;
            }
            final int seat = i;
            final Component line;
            if (room.isBot(i)) {
                line = Component.translatable("wifi_card_games.board.room.seat_bot", i + 1, name);
            } else {
                boolean online = room.members[i] != null && BoardRoom.isConnected(room.members[i]);
                line = Component.translatable("wifi_card_games.board.room.seat_real", i + 1, name,
                        Component.translatable(online
                                ? "wifi_card_games.board.room.online" : "wifi_card_games.board.room.offline"));
            }
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** 删除指定房间：/chess debug roomdelete <房间码>。 */
    private static int debugRoomDelete(CommandSourceStack source, String code) {
        Component error = BoardMemoryManager.INSTANCE.deleteRoom(code);
        if (error != null) {
            source.sendFailure(error);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.room_deleted", code.toUpperCase()), false);
        return 1;
    }

    /** 清空所有房间：/chess debug roomclear。 */
    private static int debugRoomClear(CommandSourceStack source) {
        int count = BoardMemoryManager.INSTANCE.clearAllRooms();
        source.sendSuccess(() -> Component.translatable("wifi_card_games.board.debug.rooms_cleared", count), false);
        return 1;
    }

    /** 阶段翻译键（管理命令/注册表房间摘要显示用）。 */
    public static String phaseNameKey(BoardPhase phase) {
        return switch (phase) {
            case WAITING -> "wifi_card_games.board.phase.waiting";
            case PLAYING -> "wifi_card_games.board.phase.playing";
            case SETTLED -> "wifi_card_games.board.phase.settled";
        };
    }

    /** 房间详细信息行（/cardgames roominfo 用）；房间不存在返回空列表。 */
    public static List<Component> roomDetail(String code) {
        BoardRoom r = BoardMemoryManager.INSTANCE.roomByCode(code);
        if (r == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("wifi_card_games.board.room.mode",
                Component.translatable(r.gameType.displayName),
                r.gameType == BoardGameType.GO
                        ? Component.translatable("wifi_card_games.board.size_go_detail", r.size)
                        : Component.translatable("wifi_card_games.board.size_rect_detail", r.size),
                Component.translatable(r.announce
                        ? "wifi_card_games.board.room.public" : "wifi_card_games.board.room.private")));
        lines.add(Component.translatable("wifi_card_games.board.room.phase_players",
                Component.translatable(phaseNameKey(r.phase())), r.count));
        for (int i = 0; i < 2; i++) {
            String name = r.seatName(i);
            if (name.isEmpty()) {
                lines.add(Component.translatable("wifi_card_games.board.room.empty_seat", i + 1));
                continue;
            }
            Component extra = r.isBot(i) ? Component.translatable("wifi_card_games.board.room.bot_tag")
                    : (BoardRoom.isConnected(r.members[i]) ? Component.empty()
                            : Component.translatable("wifi_card_games.board.room.offline_tag"));
            lines.add(Component.translatable("wifi_card_games.board.room.seat", i + 1, name,
                    Component.translatable(i == 0
                            ? "wifi_card_games.board.side.black" : "wifi_card_games.board.side.white"))
                    .append(extra));
        }
        lines.add(Component.translatable("wifi_card_games.board.room.spectators", r.spectators.size()));
        return lines;
    }

    private static BoardGame gameOf(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        BoardGame game = BoardMemoryManager.INSTANCE.gameOf(player);
        if (game == null) {
            source.sendFailure(Component.translatable("wifi_card_games.board.error.not_in_game_self"));
        }
        return game;
    }

    /** 可点击命令文本（label 为翻译键）。 */
    private static MutableComponent click(String labelKey, String command, ChatFormatting color) {
        return Component.translatable(labelKey).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }
}
