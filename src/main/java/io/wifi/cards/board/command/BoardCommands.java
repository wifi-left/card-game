package io.wifi.cards.board.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.board.game.BoardGame;
import io.wifi.cards.board.manager.BoardMemoryManager;
import io.wifi.cards.board.manager.BoardRoom;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.DebugUiS2C;
import io.wifi.cards.board.network.BoardPackets.OpenLobbyS2C;
import io.wifi.cards.board.othello.rule.OthelloRules;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Random;

/**
 * 棋类服务端命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/board</code>：打开棋类大厅（服务端校验后发 OpenLobbyS2C，客户端主线程打开 UI）</li>
 *   <li><code>/board accept &lt;房间码&gt;</code>：加入房间（聊天点击消息触发）</li>
 *   <li><code>/board invite &lt;玩家&gt;</code>：房主邀请玩家（被邀请者收到可点击消息）</li>
 *   <li><code>/board leave</code>：离开房间</li>
 *   <li><code>/board spectate &lt;房间码&gt;</code> / <code>unspectate</code>：旁观/退出旁观</li>
 *   <li><code>/board debug ...</code>：调试命令（OP 权限，开发端测试用）</li>
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
     * 打开 UI：/board。
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

    /** 打开该游戏 UI（/board 与 /cardgames open 共用）：
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
        // 旁观者：关闭 UI 后用 /board 重新打开应回到旁观界面（而非大厅）
        String specId = m.spectatingRoomId(player);
        if (specId != null) {
            BoardRoom specRoom = m.roomByCode(specId);
            if (specRoom != null && specRoom.game != null) {
                specRoom.broadcastState(); // 旁观者收到 mySeat=-1 的房间状态
                specRoom.game.syncToSpectator(player); // 对局快照
                return;
            }
        }
        ServerPlayNetworking.send(player, new OpenLobbyS2C());
    }

    /** 接受邀请加入：/board accept <房间码>（服务端直接处理；聊天点击消息触发）。 */
    private static int accept(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.joinRoom(player, code);
        return 1;
    }

    /** 旁观房间：/board spectate <房间码>（对局开始后的只读观看）。 */
    private static int spectate(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String error = BoardMemoryManager.INSTANCE.spectate(player, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("正在旁观房间 " + code + "，输入 /cardgames leave 退出旁观"), false);
        return 1;
    }

    /** 退出旁观：/board unspectate。 */
    private static int unspectate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.leaveSpectate(player);
        return 1;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        String error = invite(owner, target);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已向 " + target.getGameProfile().getName() + " 发送邀请"), false);
        return 1;
    }

    /** 邀请玩家加入自己所在房间（/board invite 与 /cardgames invite 共用）；
     *  成功时向目标发送可点击邀请消息，返回错误消息或 null。 */
    public static String invite(ServerPlayer owner, ServerPlayer target) {
        BoardRoom room = BoardMemoryManager.INSTANCE.currentRoom(owner);
        if (room == null) {
            return "你不在任何房间里，请先创建房间";
        }
        if (room.isFull() || room.phase() != BoardPhase.WAITING) {
            return "只能邀请玩家加入等待中的房间";
        }
        Component message = Component.literal(owner.getGameProfile().getName() + " 邀请你加入棋牌房间["
                + room.id + "]（" + room.gameType.displayName + "） ")
                .append(Component.literal("[接受邀请]").withStyle(style -> style
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

    /** 添加调试假人：/board debug bots 1（等待中的房间，满 2 人自动开局）。 */
    private static int debugBots(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.addBots(player, count);
        return 1;
    }

    /** 移除调试假人：/board debug bots remove。 */
    private static int debugBotsRemove(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager.INSTANCE.removeBots(player);
        return 1;
    }

    /** 指挥当前轮到者落子（真人与假人均可）：/board debug move <x> <y>。 */
    private static int debugMove(CommandSourceStack source, int x, int y) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onMove(null, x, y);
        source.sendSuccess(() -> Component.literal("已指挥座位 " + game.currentSeat() + " 落子：" + x + "," + y), false);
        return 1;
    }

    /** 指挥当前轮到者停一手（真人与假人均可）：/board debug pass。 */
    private static int debugPass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onPass(null);
        source.sendSuccess(() -> Component.literal("已指挥座位 " + game.currentSeat() + " 停一手"), false);
        return 1;
    }

    /** 指挥当前轮到者认输：/board debug surrender。 */
    private static int debugSurrender(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.onSurrender(null);
        source.sendSuccess(() -> Component.literal("已指挥座位 " + game.currentSeat() + " 认输"), false);
        return 1;
    }

    /** 打开调试旁观界面：/board debug ui。无房间，发送随机虚拟对局数据供旁观 UI 检查（标题带"（调试）"）。
     *  对局中/旁观中拒绝：虚拟数据会覆盖真实对局状态。 */
    private static int debugUi(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BoardMemoryManager m = BoardMemoryManager.INSTANCE;
        if (m.currentRoom(player) != null || m.spectatingRoomId(player) != null) {
            source.sendFailure(Component.literal("对局/旁观中无法打开调试界面"));
            return 0;
        }
        ServerPlayNetworking.send(player, randomDebugUi());
        source.sendSuccess(() -> Component.literal("已打开调试旁观界面（随机虚拟对局，无真实房间）"), false);
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
                new String[]{"调试者A", "调试者B"}, (byte) RANDOM.nextInt(2));
    }

    /** 强制将指定玩家加入房间：/board debug forcejoin <玩家> [房间码]（缺省用执行者所在房间）。 */
    private static int debugForceJoin(CommandSourceStack source, ServerPlayer target, String roomCode) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();
        final String code;
        if (roomCode != null) {
            code = roomCode;
        } else {
            BoardRoom room = BoardMemoryManager.INSTANCE.currentRoom(executor);
            if (room == null) {
                source.sendFailure(Component.literal("未指定房间码且你不在任何房间中"));
                return 0;
            }
            code = room.id;
        }
        String error = BoardMemoryManager.INSTANCE.forceJoin(target, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 加入房间 " + code), false);
        return 1;
    }

    /** 强制指定玩家退出游戏（对局中座位转机器人托管；房间无真人则关闭）：/board debug kick <玩家>。 */
    private static int debugKick(CommandSourceStack source, ServerPlayer target) {
        if (BoardMemoryManager.INSTANCE.currentRoom(target) == null) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " 不在任何房间中"));
            return 0;
        }
        BoardMemoryManager.INSTANCE.leaveRoom(target);
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 退出游戏"), false);
        return 1;
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间列表（每行带 [显示具体信息][删除房间] 快捷点击）：/board debug rooms。 */
    private static int debugRooms(CommandSourceStack source) {
        List<BoardRoom> rooms = BoardMemoryManager.INSTANCE.roomSnapshot();
        source.sendSuccess(() -> Component.literal("房间列表（共 " + rooms.size() + " 个）"), false);
        for (BoardRoom room : rooms) {
            final String code = room.id;
            Component line = Component.literal(room.id + " [" + room.gameType.displayName
                    + (room.gameType == BoardGameType.GO ? room.size + "路" : "") + "] "
                    + room.count + "/2 · " + phaseName(room.phase()))
                    .append(Component.literal(" [显示具体信息]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chess debug room " + code))))
                    .append(Component.literal(" [删除房间]").withStyle(style -> style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/chess debug roomdelete " + code))));
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** 房间详细信息（成员：真人 + 机器人，含在线状态）：/board debug room <房间码>。 */
    private static int debugRoom(CommandSourceStack source, String code) {
        BoardRoom room = BoardMemoryManager.INSTANCE.roomByCode(code);
        if (room == null) {
            source.sendFailure(Component.literal("房间不存在：" + code));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("房间 " + room.id + "（" + room.gameType.displayName
                + (room.gameType == BoardGameType.GO ? " " + room.size + "路" : "")
                + "）· " + phaseName(room.phase())), false);
        for (int i = 0; i < 2; i++) {
            String name = room.seatName(i);
            if (name.isEmpty()) {
                continue;
            }
            final int seat = i;
            final String line;
            if (room.isBot(i)) {
                line = "  座位" + (i + 1) + "：" + name + "（机器人）";
            } else {
                boolean online = room.members[i] != null && BoardRoom.isConnected(room.members[i]);
                line = "  座位" + (i + 1) + "：" + name + "（真人·" + (online ? "在线" : "离线") + "）";
            }
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 删除指定房间：/board debug roomdelete <房间码>。 */
    private static int debugRoomDelete(CommandSourceStack source, String code) {
        String error = BoardMemoryManager.INSTANCE.deleteRoom(code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已删除房间 " + code.toUpperCase()), false);
        return 1;
    }

    /** 清空所有房间：/board debug roomclear。 */
    private static int debugRoomClear(CommandSourceStack source) {
        int count = BoardMemoryManager.INSTANCE.clearAllRooms();
        source.sendSuccess(() -> Component.literal("已清空全部房间（共 " + count + " 个）"), false);
        return 1;
    }

    /** 阶段中文名（管理命令/注册表房间摘要显示用）。 */
    public static String phaseName(BoardPhase phase) {
        return switch (phase) {
            case WAITING -> "等待中";
            case PLAYING -> "对局中";
            case SETTLED -> "本局结束";
        };
    }

    private static BoardGame gameOf(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        BoardGame game = BoardMemoryManager.INSTANCE.gameOf(player);
        if (game == null) {
            source.sendFailure(Component.literal("你不在任何对局中"));
        }
        return game;
    }
}
