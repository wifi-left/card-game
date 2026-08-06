package io.wifi.cards.common.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.RoomBrief;
import io.wifi.cards.common.network.CommonPackets;
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
import java.util.Comparator;
import java.util.List;

/**
 * 小游戏统一命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/cardgames</code>：打开小游戏菜单（服务端发 OpenMenuS2C，客户端渲染可滚动列表）</li>
 *   <li><code>/cardgames open &lt;game&gt;</code>：打开指定游戏大厅（doudizhu / uno / board）</li>
 *   <li><code>/cardgames join|accept &lt;房间码&gt;</code>：按房间码前缀路由加入对应游戏（申请加入）</li>
 *   <li><code>/cardgames spectate &lt;房间码&gt;</code>：按前缀路由旁观</li>
 *   <li><code>/cardgames leave</code>：离开当前任意游戏的房间/旁观</li>
 *   <li><code>/cardgames invite &lt;玩家&gt;</code>：邀请玩家加入自己所在游戏房间</li>
 *   <li><code>/cardgames rooms [game] [页码]</code>：房间列表（每页 10 个，可点击加入/旁观；
 *       game 可省略（全部游戏）；OP 额外显示[信息][删除]操作，且列表含未公开房间）</li>
 *   <li><code>/cardgames roominfo &lt;房间码&gt;</code>：房间详细信息</li>
 *   <li><code>/cardgames roomdelete &lt;房间码&gt;</code>：删除房间（OP）</li>
 *   <li><code>/cardgames list</code>：聊天列出各游戏与房间/在线统计</li>
 *   <li><code>/cardgames debug rooms|roomdelete|roomclear</code>：OP 全游戏统一房间管理</li>
 * </ul>
 * 房间操作全部经 GameRegistry 路由到对应游戏，各游戏自身命令（/doudizhu /uno /chess）保留不变。
 */
public final class CardGamesCommands {
    /** 房间列表每页条数。 */
    private static final int ROOMS_PAGE_SIZE = 10;

    private CardGamesCommands() {
    }

    public static void registerServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("cardgames")
                    .executes(ctx -> openMenu(ctx.getSource()))
                    .then(Commands.literal("menu")
                            .executes(ctx -> openMenu(ctx.getSource())))
                    .then(Commands.literal("open")
                            .then(Commands.argument("game", StringArgumentType.word())
                                    .executes(ctx -> openGame(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "game")))))
                    .then(Commands.literal("join")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> join(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("accept")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> join(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("spectate")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> spectate(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("leave")
                            .executes(ctx -> leave(ctx.getSource())))
                    .then(Commands.literal("invite")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource(),
                                            EntityArgument.getPlayer(ctx, "player")))))
                    .then(Commands.literal("rooms")
                            .executes(ctx -> rooms(ctx.getSource(), null, 1))
                            .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes(ctx -> rooms(ctx.getSource(), null,
                                            IntegerArgumentType.getInteger(ctx, "page"))))
                            .then(Commands.argument("game", StringArgumentType.word())
                                    .executes(ctx -> rooms(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "game"), 1))
                                    .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                            .executes(ctx -> rooms(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "game"),
                                                    IntegerArgumentType.getInteger(ctx, "page"))))))
                    .then(Commands.literal("roominfo")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> roomInfo(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("roomdelete").requires(src -> src.hasPermission(2))
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> roomDelete(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("list")
                            .executes(ctx -> list(ctx.getSource())))
                    .then(Commands.literal("debug").requires(src -> src.hasPermission(2))
                            .then(Commands.literal("rooms")
                                    .executes(ctx -> debugRooms(ctx.getSource())))
                            .then(Commands.literal("roomdelete")
                                    .then(Commands.argument("code", StringArgumentType.word())
                                            .executes(ctx -> debugRoomDelete(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "code")))))
                            .then(Commands.literal("roomclear")
                                    .executes(ctx -> debugRoomClear(ctx.getSource())))));
        });
    }

    // ---------------- 普通命令 ----------------

    /** 打开小游戏菜单：/cardgames（服务端构建菜单快照下发，客户端主线程打开界面）。 */
    private static int openMenu(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerPlayNetworking.send(player, CommonPackets.snapshot());
        return 1;
    }

    /** 打开指定游戏 UI：/cardgames open <game>（对局/旁观中恢复界面，否则聊天栏显示房间列表）。 */
    private static int openGame(CommandSourceStack source, String gameId) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GameInfo info = GameRegistry.byId(gameId);
        if (info == null) {
            source.sendFailure(Component.literal("未知的游戏：" + gameId + "，可用：" + GameRegistry.gameIdsText()));
            return 0;
        }
        info.opener().accept(player);
        return 1;
    }

    /** 申请加入房间：/cardgames join|accept <房间码>（按前缀路由；失败经该游戏 NoticeS2C 提示）。 */
    private static int join(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GameInfo info = GameRegistry.gameOfCode(code);
        if (info == null) {
            source.sendFailure(Component.literal("房间码无效：请使用完整房间码（如 " + GameRegistry.exampleCode() + "）"));
            return 0;
        }
        info.joiner().accept(player, code);
        return 1;
    }

    /** 旁观房间：/cardgames spectate <房间码>（按前缀路由）。 */
    private static int spectate(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GameInfo info = GameRegistry.gameOfCode(code);
        if (info == null) {
            source.sendFailure(Component.literal("房间码无效：请使用完整房间码（如 " + GameRegistry.exampleCode() + "）"));
            return 0;
        }
        String error = info.spectater().apply(player, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("正在旁观" + info.displayName() + "房间 " + code.toUpperCase()
                + "，输入 /cardgames leave 退出旁观"), false);
        return 1;
    }

    /** 离开当前任意游戏的房间/旁观：/cardgames leave。 */
    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GameInfo session = GameRegistry.currentGame(player);
        if (session == null) {
            source.sendFailure(Component.literal("你不在任何小游戏的房间/旁观中"));
            return 0;
        }
        session.leaver().accept(player);
        source.sendSuccess(() -> Component.literal("已退出" + session.displayName()), false);
        return 1;
    }

    /** 邀请玩家加入自己所在房间：/cardgames invite <玩家>。 */
    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        GameInfo session = GameRegistry.currentGame(owner);
        if (session == null) {
            source.sendFailure(Component.literal("你不在任何房间里，请先创建房间"));
            return 0;
        }
        String error = session.inviter().apply(owner, target);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已向 " + target.getGameProfile().getName() + " 发送邀请"), false);
        return 1;
    }

    /** 聊天列出各游戏与房间/在线统计：/cardgames list。 */
    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        source.sendSuccess(() -> Component.literal("小游戏列表："), false);
        for (GameInfo info : GameRegistry.all()) {
            final GameInfo f = info;
            source.sendSuccess(() -> Component.literal("· [" + f.iconText() + "] " + f.displayName()
                    + "（房间码前缀 " + f.prefix() + "-XXXXX）：房间 " + f.roomCount().getAsInt()
                    + " · 在线 " + f.playerCount().getAsInt() + " ")
                    .append(Component.literal("[打开]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/cardgames open " + f.gameId())))), false);
        }
        return 1;
    }

    // ---------------- 房间列表 / 房间信息 / 删除（OP） ----------------

    /**
     * 房间列表（可点击加入/旁观，每页 10 个可翻页）：/cardgames rooms [game] [页码]。
     * game 省略时列出全部游戏；OP（hasPermission(2)）额外显示[信息][删除]操作，且列表含未公开房间。
     */
    private static int rooms(CommandSourceStack source, String gameId, int page) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        for (Component line : roomListLines(player, gameId, page)) {
            final Component f = line;
            source.sendSuccess(() -> f, false);
        }
        return 1;
    }

    /** 构建房间列表聊天消息行（标题 + 房间行 + 翻页按钮）；gameId 非空时只列该游戏。
     *  排序：等待中（未满可加入）优先，其次对局中，最后已结束（同状态按房间码）。 */
    private static List<Component> roomListLines(ServerPlayer player, String gameId, int page) {
        boolean isOp = player.hasPermissions(2);
        List<RoomBrief> all = new ArrayList<>();
        for (GameInfo info : GameRegistry.all()) {
            if (gameId != null && !info.gameId().equals(gameId)) {
                continue;
            }
            all.addAll(info.roomBriefs().apply(isOp));
        }
        all.sort(Comparator.comparingInt(RoomBrief::status).thenComparing(RoomBrief::code));
        int totalPages = Math.max(1, (all.size() + ROOMS_PAGE_SIZE - 1) / ROOMS_PAGE_SIZE);
        int p = Math.max(1, Math.min(page, totalPages));
        int from = (p - 1) * ROOMS_PAGE_SIZE;
        int to = Math.min(all.size(), from + ROOMS_PAGE_SIZE);
        List<Component> lines = new ArrayList<>();
        // header：指定游戏时显示游戏名
        String scopeName = gameId != null
                ? GameRegistry.byId(gameId) != null ? GameRegistry.byId(gameId).displayName() : gameId
                : "小游戏";
        lines.add(Component.literal("=== " + scopeName + "房间（共 " + all.size() + " 个 · 第 "
                + p + "/" + totalPages + " 页）==="));
        if (all.isEmpty()) {
            lines.add(Component.literal("暂无房间，进入大厅点击创建房间"));
            return lines;
        }
        for (int i = from; i < to; i++) {
            RoomBrief b = all.get(i);
            MutableComponent line = Component.literal("· " + b.code() + " " + b.line() + "  ");
            if (b.status() == 0) {
                line.append(click("[加入]", "/cardgames accept " + b.code(), ChatFormatting.GREEN));
            } else if (b.status() == 1) {
                line.append(click("[旁观]", "/cardgames spectate " + b.code(), ChatFormatting.GREEN));
            } else {
                line.append(Component.literal("[已结束]").withStyle(ChatFormatting.GRAY));
            }
            if (isOp) {
                line.append(click(" [信息]", "/cardgames roominfo " + b.code(), ChatFormatting.AQUA));
                line.append(click(" [删除]", "/cardgames roomdelete " + b.code(), ChatFormatting.RED));
            }
            lines.add(line);
        }
        if (totalPages > 1) {
            // 翻页按钮保留 game 过滤（指定游戏时翻页不跳出该游戏）
            String navPrefix = gameId != null ? "/cardgames rooms " + gameId + " " : "/cardgames rooms ";
            MutableComponent nav = Component.literal("");
            if (p > 1) {
                nav.append(click("[上一页]", navPrefix + (p - 1), ChatFormatting.YELLOW));
            }
            if (p > 1 && p < totalPages) {
                nav.append(Component.literal("  "));
            }
            if (p < totalPages) {
                nav.append(click("[下一页]", navPrefix + (p + 1), ChatFormatting.YELLOW));
            }
            lines.add(nav);
        }
        return lines;
    }

    /** 房间详细信息：/cardgames roominfo <房间码>。 */
    private static int roomInfo(CommandSourceStack source, String code) throws CommandSyntaxException {
        GameInfo info = GameRegistry.gameOfCode(code);
        if (info == null) {
            source.sendFailure(Component.literal("房间码无效：请使用完整房间码（如 " + GameRegistry.exampleCode() + "）"));
            return 0;
        }
        List<String> lines = info.roomDetailer().apply(code);
        if (lines.isEmpty()) {
            source.sendFailure(Component.literal("房间不存在：" + code.toUpperCase()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== " + info.displayName() + " 房间 " + code.toUpperCase() + " ==="), false);
        for (String line : lines) {
            final String f = line;
            source.sendSuccess(() -> Component.literal("· " + f), false);
        }
        return 1;
    }

    /** 删除房间（OP）：/cardgames roomdelete <房间码>（与 debug roomdelete 同一实现）。 */
    private static int roomDelete(CommandSourceStack source, String code) {
        return debugRoomDelete(source, code);
    }

    /** 可点击命令文本（绿色/黄色等提示色 + RUN_COMMAND）。 */
    private static MutableComponent click(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    // ---------------- 管理员统一房间管理（OP） ----------------

    /** 全游戏房间总览（每游戏一行摘要 + 房间列表，可点击进入该游戏的完整管理命令）：/cardgames debug rooms。 */
    private static int debugRooms(CommandSourceStack source) {
        for (GameInfo info : GameRegistry.all()) {
            final GameInfo f = info;
            source.sendSuccess(() -> Component.literal("【" + f.displayName() + "】共 " + f.roomCount().getAsInt()
                    + " 个房间（" + f.playerCount().getAsInt() + " 人在线） ")
                    .append(Component.literal("[管理]").withStyle(style -> style
                            .withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/" + f.gameId() + " debug rooms")))), false);
            List<String> lines = f.roomLines().get();
            if (lines.isEmpty()) {
                source.sendSuccess(() -> Component.literal("  （无）"), false);
            } else {
                for (String line : lines) {
                    source.sendSuccess(() -> Component.literal("  " + line), false);
                }
            }
        }
        return 1;
    }

    /** 删除指定房间（按前缀路由到对应游戏）：/cardgames debug roomdelete <房间码>。 */
    private static int debugRoomDelete(CommandSourceStack source, String code) {
        GameInfo info = GameRegistry.gameOfCode(code);
        if (info == null) {
            source.sendFailure(Component.literal("房间码无效：请使用完整房间码（如 " + GameRegistry.exampleCode() + "）"));
            return 0;
        }
        String error = info.roomDeleter().apply(code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已删除" + info.displayName() + "房间 " + code.toUpperCase()), false);
        return 1;
    }

    /** 清空全部游戏的所有房间：/cardgames debug roomclear。 */
    private static int debugRoomClear(CommandSourceStack source) {
        int total = 0;
        for (GameInfo info : GameRegistry.all()) {
            total += info.roomClearer().getAsInt();
        }
        final int cleared = total;
        source.sendSuccess(() -> Component.literal("已清空全部小游戏房间（共 " + cleared + " 个）"), false);
        return 1;
    }
}
