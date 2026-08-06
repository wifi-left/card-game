package io.wifi.cards.common.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.network.CommonPackets;
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

/**
 * 小游戏统一命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/cardgames</code>：打开小游戏菜单（服务端发 OpenMenuS2C，客户端渲染可滚动列表）</li>
 *   <li><code>/cardgames open &lt;game&gt;</code>：打开指定游戏大厅（doudizhu / uno / board）</li>
 *   <li><code>/cardgames join|accept &lt;房间码&gt;</code>：按房间码前缀路由加入对应游戏（申请加入）</li>
 *   <li><code>/cardgames spectate &lt;房间码&gt;</code>：按前缀路由旁观</li>
 *   <li><code>/cardgames leave</code>：离开当前任意游戏的房间/旁观</li>
 *   <li><code>/cardgames invite &lt;玩家&gt;</code>：邀请玩家加入自己所在游戏房间</li>
 *   <li><code>/cardgames list</code>：聊天列出各游戏与房间/在线统计</li>
 *   <li><code>/cardgames debug rooms|roomdelete|roomclear</code>：OP 全游戏统一房间管理</li>
 * </ul>
 * 房间操作全部经 GameRegistry 路由到对应游戏，各游戏自身命令（/doudizhu /uno /chess）保留不变。
 */
public final class CardGamesCommands {
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

    /** 打开指定游戏 UI：/cardgames open <game>（对局/旁观中恢复界面，否则打开大厅）。 */
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
