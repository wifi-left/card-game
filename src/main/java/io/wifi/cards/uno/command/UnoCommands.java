package io.wifi.cards.uno.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.uno.network.UnoPackets.OpenLobbyS2C;
import io.wifi.cards.common.command.CardGamesCommands;
import io.wifi.cards.uno.game.UnoGame;
import io.wifi.cards.uno.manager.UnoMemoryManager;
import io.wifi.cards.uno.manager.UnoRoom;
import io.wifi.cards.uno.model.UnoGamePhase;
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

/**
 * UNO 服务端命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/uno</code>：打开大厅（服务端校验后发 OpenLobbyS2C，客户端主线程打开 UI）</li>
 *   <li><code>/uno accept &lt;房间码&gt;</code>：加入房间（聊天点击消息触发）</li>
 *   <li><code>/uno invite &lt;玩家&gt;</code>：房主邀请玩家（被邀请者收到可点击消息）</li>
 *   <li><code>/uno leave</code>：离开房间</li>
 *   <li><code>/uno start</code>：房主开始游戏（等价大厅"开始游戏"按钮）</li>
 *   <li><code>/uno debug ...</code>：调试命令（OP 权限，开发端测试用）</li>
 * </ul>
 */
public final class UnoCommands {
    private UnoCommands() {
    }

    public static void registerServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("uno")
                    .executes(ctx -> openLobby(ctx.getSource()))
                    .then(Commands.literal("accept")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> accept(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("invite")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                    .then(Commands.literal("leave")
                            .executes(ctx -> leave(ctx.getSource())))
                    .then(Commands.literal("start")
                            .executes(ctx -> start(ctx.getSource())))
                    .then(Commands.literal("spectate")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> spectate(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("unspectate")
                            .executes(ctx -> unspectate(ctx.getSource())))
                    .then(Commands.literal("debug").requires(src -> src.hasPermission(2))
                            .then(Commands.literal("bots")
                                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 9))
                                            .executes(ctx -> debugBots(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"))))
                                    .then(Commands.literal("remove")
                                            .executes(ctx -> debugBotsRemove(ctx.getSource()))))
                            .then(Commands.literal("auto")
                                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> debugAuto(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
                            .then(Commands.literal("trust")
                                    .then(Commands.argument("seat", IntegerArgumentType.integer(0, 9))
                                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                    .executes(ctx -> debugTrustSeat(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "seat"),
                                                            BoolArgumentType.getBool(ctx, "enabled")))))
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                    .executes(ctx -> debugTrust(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                            BoolArgumentType.getBool(ctx, "enabled"))))))
                            .then(Commands.literal("forcejoin")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), null))
                                            .then(Commands.argument("code", StringArgumentType.word())
                                                    .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "code"))))))
                            .then(Commands.literal("rooms")
                                    .executes(ctx -> debugRooms(ctx.getSource(), 1))
                                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                            .executes(ctx -> debugRooms(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "page")))))
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
                                                    EntityArgument.getPlayer(ctx, "player")))))
                            .then(Commands.literal("spectateui")
                                    .executes(ctx -> debugSpectateUi(ctx.getSource())))));
        });
    }

    // ---------------- 普通命令 ----------------

    /**
     * 打开 UI：/uno。
     * <ul>
     *   <li>对局中（出牌/结算）：发房间状态 + 完整对局快照，客户端重新打开游戏界面</li>
     *   <li>旁观中：重发旁观快照（房间状态 + 对局 + 各家手牌），客户端重新打开旁观界面</li>
     *   <li>等待中或不在房间：发 OpenLobbyS2C 打开大厅</li>
     * </ul>
     */
    private static int openLobby(CommandSourceStack source) throws CommandSyntaxException {
        openLobby(source.getPlayerOrException());
        return 1;
    }

    /** 打开该游戏 UI（/uno 与 /cardgames open 共用）：
     * 对局中重发快照 / 旁观中重发旁观快照 / 否则发 OpenLobbyS2C 打开大厅。 */
    public static void openLobby(ServerPlayer player) {
        UnoMemoryManager m = UnoMemoryManager.INSTANCE;
        UnoRoom room = m.currentRoom(player);
        if (room != null && room.phase() != UnoGamePhase.WAITING) {
            // 对局中：先同步房间信息（mySeat/成员），再发完整快照，客户端 onReconnect 会打开 GameScreen
            room.broadcastState();
            room.game.syncTo(room.seatOf(player));
            return;
        }
        // 旁观者：关闭 UI 后用 /uno 重新打开应回到旁观界面（而非大厅）
        String specId = m.spectatingRoomId(player);
        if (specId != null) {
            UnoRoom specRoom = m.roomByCode(specId);
            if (specRoom != null && specRoom.game != null) {
                specRoom.broadcastState(); // 旁观者收到 mySeat=-1 的房间状态
                specRoom.game.syncToSpectator(player); // 对局快照 + 各家手牌
                return;
            }
        }
        // 等待中/未进房：打开大厅 UI（房间列表通过 /cardgames rooms 命令查看）
        ServerPlayNetworking.send(player, new OpenLobbyS2C());
    }

    /** 接受邀请加入：/uno accept <房间码>（服务端直接处理；聊天点击消息触发）。 */
    private static int accept(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.joinRoom(player, code);
        return 1;
    }

    /** 旁观房间：/uno spectate <房间码>（对局开始后的只读观看）。 */
    private static int spectate(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String error = UnoMemoryManager.INSTANCE.spectate(player, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("正在旁观房间 " + code + "，输入 /cardgames leave 退出旁观"), false);
        return 1;
    }

    /** 退出旁观：/uno unspectate。 */
    private static int unspectate(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.leaveSpectate(player);
        return 1;
    }

    /** 房主开始游戏：/uno start（等价大厅"开始游戏"按钮）。 */
    private static int start(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.startGame(player);
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

    /** 邀请玩家加入自己所在房间（/uno invite 与 /cardgames invite 共用）；
     *  成功时向目标发送可点击邀请消息，返回错误消息或 null。 */
    public static String invite(ServerPlayer owner, ServerPlayer target) {
        UnoRoom room = UnoMemoryManager.INSTANCE.currentRoom(owner);
        if (room == null) {
            return "你不在任何房间里，请先创建房间";
        }
        if (room.isFull() || room.phase() != UnoGamePhase.WAITING) {
            return "只能邀请玩家加入等待中的房间";
        }
        Component message = Component.literal(owner.getGameProfile().getName() + " 邀请你加入 UNO 房间[" + room.id + "] ")
                .append(Component.literal("[接受邀请]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cardgames accept " + room.id))));
        target.sendSystemMessage(message);
        return null;
    }

    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.leaveRoom(player);
        return 1;
    }

    // ---------------- 调试命令（OP） ----------------

    /** 添加调试假人：/uno debug bots <1|2|3>（等待中的房间）。 */
    private static int debugBots(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.addBots(player, count);
        return 1;
    }

    /** 移除调试假人：/uno debug bots remove。 */
    private static int debugBotsRemove(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.removeBots(player);
        return 1;
    }

    /** 执行者本人托管开关：开启=自己进入自动托管；关闭=退出托管。
     *  /uno debug auto <true|false>（配合 /execute as @a 可让所有玩家各自进入托管）。 */
    private static int debugAuto(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.setTrust(player, enabled);
        source.sendSuccess(() -> Component.literal("托管：" + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    /** 强制指定座位开启/关闭托管（真人与假人均可，无需真人在线）：/uno debug trust <0~9> <true|false>。 */
    private static int debugTrustSeat(CommandSourceStack source, int seat, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.setTrustSeat(seat, enabled);
        source.sendSuccess(() -> Component.literal("已" + (enabled ? "开启" : "关闭") + " 座位 " + seat + " 的托管"), false);
        return 1;
    }

    /** 强制开启/关闭指定玩家的托管：/uno debug trust <玩家> <true|false>。 */
    private static int debugTrust(CommandSourceStack source, ServerPlayer target, boolean enabled) {
        UnoGame game = UnoMemoryManager.INSTANCE.gameOf(target);
        if (game == null) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " 不在对局中"));
            return 0;
        }
        game.setTrust(target, enabled);
        source.sendSuccess(() -> Component.literal("已" + (enabled ? "开启" : "关闭") + " " + target.getGameProfile().getName() + " 的托管"), false);
        return 1;
    }

    /** 强制将指定玩家加入房间：/uno debug forcejoin <玩家> [房间码]（缺省用执行者所在房间）。 */
    private static int debugForceJoin(CommandSourceStack source, ServerPlayer target, String roomCode) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();
        final String code;
        if (roomCode != null) {
            code = roomCode;
        } else {
            UnoRoom room = UnoMemoryManager.INSTANCE.currentRoom(executor);
            if (room == null) {
                source.sendFailure(Component.literal("未指定房间码且你不在任何房间中"));
                return 0;
            }
            code = room.id;
        }
        String error = UnoMemoryManager.INSTANCE.forceJoin(target, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 加入房间 " + code), false);
        return 1;
    }

    /** 强制指定玩家退出游戏（对局中座位转机器人托管；房间无真人则关闭）：
     *  /uno debug kick <玩家>。 */
    private static int debugKick(CommandSourceStack source, ServerPlayer target) {
        if (UnoMemoryManager.INSTANCE.currentRoom(target) == null) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " 不在任何房间中"));
            return 0;
        }
        UnoMemoryManager.INSTANCE.leaveRoom(target);
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 退出游戏"), false);
        return 1;
    }

    /** 旁观 UI 调试（无房间）：生成随机虚拟旁观快照，客户端打开"（调试）"旁观界面：
     *  /uno debug spectateui。 */
    private static int debugSpectateUi(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UnoMemoryManager.INSTANCE.debugSpectatorUi(player);
        return 1;
    }

    // ---------------- 管理员房间管理 ----------------

    /** 房间列表（一页 10 个，可翻页；每行带 [显示具体信息][删除房间] 快捷点击）：
     *  /uno debug rooms [页码]。 */
    private static int debugRooms(CommandSourceStack source, int page) {
        List<UnoRoom> rooms = UnoMemoryManager.INSTANCE.roomSnapshot();
        int perPage = 10;
        int totalPages = Math.max(1, (rooms.size() + perPage - 1) / perPage);
        final int shownPage = Math.min(page, totalPages);
        source.sendSuccess(() -> Component.literal(
                "房间列表（共 " + rooms.size() + " 个，第 " + shownPage + "/" + totalPages + " 页）"), false);
        int from = (shownPage - 1) * perPage;
        for (int i = from; i < Math.min(rooms.size(), from + perPage); i++) {
            UnoRoom room = rooms.get(i);
            final String code = room.id;
            Component line = Component.literal((i + 1) + ". [" + code + "] 人数 " + room.size() + "/10 · "
                    + phaseName(room.phase()))
                    .append(Component.literal(" [显示具体信息]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/uno debug room " + code))))
                    .append(Component.literal(" [删除房间]").withStyle(style -> style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/uno debug roomdelete " + code))));
            source.sendSuccess(() -> line, false);
        }
        if (totalPages > 1) {
            MutableComponent nav = Component.literal("翻页：");
            if (shownPage > 1) {
                nav.append(Component.literal(" [上一页]").withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/uno debug rooms " + (shownPage - 1)))));
            }
            if (shownPage < totalPages) {
                nav.append(Component.literal(" [下一页]").withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/uno debug rooms " + (shownPage + 1)))));
            }
            final MutableComponent navLine = nav;
            source.sendSuccess(() -> navLine, false);
        }
        return 1;
    }

    /** 房间详细信息（成员：真人 + 机器人，含在线/托管状态）：/uno debug room <房间码>。 */
    private static int debugRoom(CommandSourceStack source, String code) {
        UnoRoom room = UnoMemoryManager.INSTANCE.roomByCode(code);
        if (room == null) {
            source.sendFailure(Component.literal("房间不存在：" + code));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("房间 " + room.id + " · " + phaseName(room.phase())), false);
        for (int i = 0; i < room.size(); i++) {
            String name = room.seatName(i);
            if (name.isEmpty()) {
                continue;
            }
            final int seat = i;
            final String line;
            if (room.isBot(i)) {
                line = "  座位" + (i + 1) + "：" + name + "（机器人）";
            } else {
                boolean online = room.members.get(i) != null && UnoRoom.isConnected(room.members.get(i));
                boolean trusted = room.game != null && room.game.isTrusted(i);
                line = "  座位" + (i + 1) + "：" + name + "（真人·" + (online ? "在线" : "离线")
                        + (trusted ? "·托管中" : "") + "）";
            }
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 删除指定房间：/uno debug roomdelete <房间码>。 */
    private static int debugRoomDelete(CommandSourceStack source, String code) {
        String error = UnoMemoryManager.INSTANCE.deleteRoom(code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已删除房间 " + code.toUpperCase()), false);
        return 1;
    }

    /** 清空所有房间：/uno debug roomclear。 */
    private static int debugRoomClear(CommandSourceStack source) {
        int count = UnoMemoryManager.INSTANCE.clearAllRooms();
        source.sendSuccess(() -> Component.literal("已清空全部房间（共 " + count + " 个）"), false);
        return 1;
    }

    /** 阶段中文名（管理命令/注册表房间摘要显示用）。 */
    public static String phaseName(UnoGamePhase phase) {
        return switch (phase) {
            case WAITING -> "等待中";
            case PLAYING -> "出牌阶段";
            case SETTLED -> "本局结束";
        };
    }

    /** 房间详细信息行（/cardgames roominfo 用）；房间不存在返回空列表。 */
    public static List<String> roomDetail(String code) {
        UnoRoom r = UnoMemoryManager.INSTANCE.roomByCode(code);
        if (r == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add((r.announce ? "公开" : "未公开") + " · 玩家 " + r.size() + "/" + UnoRoom.MAX_PLAYERS);
        lines.add("阶段：" + phaseName(r.phase()));
        for (int i = 0; i < r.size(); i++) {
            String name = r.seatName(i);
            if (name.isEmpty()) {
                lines.add((i + 1) + ". 等待加入…");
                continue;
            }
            String extra = r.isBot(i) ? "（机器人）"
                    : (UnoRoom.isConnected(r.members.get(i)) ? "" : "（离线）");
            lines.add((i + 1) + ". " + name + extra + (i == 0 ? "（房主）" : ""));
        }
        lines.add("旁观：" + r.spectators.size() + " 人");
        return lines;
    }

    private static UnoGame gameOf(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        UnoGame game = UnoMemoryManager.INSTANCE.gameOf(player);
        if (game == null) {
            source.sendFailure(Component.literal("你不在任何对局中"));
        }
        return game;
    }
}
