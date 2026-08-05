package io.wifi.cards.doudizhu.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.game.DdzGame;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.manager.DdzRoom;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.OpenLobbyS2C;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 斗地主服务端命令（服务端可加载，不引用任何含 client 的包）：
 * <ul>
 *   <li><code>/doudizhu</code>：打开大厅（服务端校验后发 OpenLobbyS2C，客户端主线程打开 UI）</li>
 *   <li><code>/doudizhu accept &lt;房间码&gt;</code>：加入房间（聊天点击消息触发）</li>
 *   <li><code>/doudizhu invite &lt;玩家&gt;</code>：房主邀请玩家（被邀请者收到可点击消息）</li>
 *   <li><code>/doudizhu leave</code>：离开房间</li>
 *   <li><code>/doudizhu debug ...</code>：调试命令（OP 权限，开发端测试用）</li>
 * </ul>
 */
public final class DdzCommands {
    private DdzCommands() {
    }

    public static void registerServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("doudizhu")
                    .executes(ctx -> openLobby(ctx.getSource()))
                    .then(Commands.literal("accept")
                            .then(Commands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> accept(ctx.getSource(), StringArgumentType.getString(ctx, "code")))))
                    .then(Commands.literal("invite")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                    .then(Commands.literal("leave")
                            .executes(ctx -> leave(ctx.getSource())))
                    .then(Commands.literal("debug").requires(src -> src.hasPermission(2))
                            .then(Commands.literal("bots")
                                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 2))
                                            .executes(ctx -> debugBots(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count"))))
                                    .then(Commands.literal("remove")
                                            .executes(ctx -> debugBotsRemove(ctx.getSource()))))
                            .then(Commands.literal("auto")
                                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> debugAuto(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
                            .then(Commands.literal("call")
                                    .then(Commands.argument("score", IntegerArgumentType.integer(0, 3))
                                            .executes(ctx -> debugCall(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "score")))))
                            .then(Commands.literal("rob")
                                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                                            .executes(ctx -> debugRob(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))))
                            .then(Commands.literal("play")
                                    .then(Commands.argument("cards", StringArgumentType.greedyString())
                                            .executes(ctx -> debugPlay(ctx.getSource(), StringArgumentType.getString(ctx, "cards")))))
                            .then(Commands.literal("pass")
                                    .executes(ctx -> debugPass(ctx.getSource())))
                            .then(Commands.literal("forcejoin")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), null))
                                            .then(Commands.argument("code", StringArgumentType.word())
                                                    .executes(ctx -> debugForceJoin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "code"))))))
                            .then(Commands.literal("kick")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> debugKick(ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")))))
                            .then(Commands.literal("trust")
                                    .then(Commands.argument("seat", IntegerArgumentType.integer(0, 2))
                                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                    .executes(ctx -> debugTrustSeat(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "seat"),
                                                            BoolArgumentType.getBool(ctx, "enabled")))))
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                    .executes(ctx -> debugTrust(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                            BoolArgumentType.getBool(ctx, "enabled"))))))));
        });
    }

    // ---------------- 普通命令 ----------------

    /**
     * 打开 UI：/doudizhu。
     * <ul>
     *   <li>对局中（叫分/抢地主/出牌/结算）：发房间状态 + 完整对局快照，客户端重新打开游戏界面</li>
     *   <li>等待中或不在房间：发 OpenLobbyS2C 打开大厅</li>
     * </ul>
     */
    private static int openLobby(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzRoom room = DdzMemoryManager.INSTANCE.currentRoom(player);
        if (room != null && room.phase() != DdzGamePhase.WAITING) {
            // 对局中：先同步房间信息（mySeat/成员），再发完整快照，客户端 onReconnect 会打开 GameScreen
            room.broadcastState();
            room.game.syncTo(room.seatOf(player));
            return 1;
        }
        ServerPlayNetworking.send(player, new OpenLobbyS2C());
        return 1;
    }

    /** 接受邀请加入：/doudizhu accept <房间码>（服务端直接处理；聊天点击消息触发）。 */
    private static int accept(CommandSourceStack source, String code) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzMemoryManager.INSTANCE.joinRoom(player, code);
        return 1;
    }

    private static int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer owner = source.getPlayerOrException();
        DdzRoom room = DdzMemoryManager.INSTANCE.currentRoom(owner);
        if (room == null) {
            source.sendFailure(Component.literal("你不在任何房间里，请先创建房间"));
            return 0;
        }
        if (room.size >= 3 || room.phase() != DdzGamePhase.WAITING) {
            source.sendFailure(Component.literal("只能邀请玩家加入等待中的房间"));
            return 0;
        }
        Component message = Component.literal(owner.getGameProfile().getName() + " 邀请你加入斗地主房间[" + room.id + "] ")
                .append(Component.literal("[接受邀请]").withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/doudizhu accept " + room.id))));
        target.sendSystemMessage(message);
        source.sendSuccess(() -> Component.literal("已向 " + target.getGameProfile().getName() + " 发送邀请"), false);
        return 1;
    }

    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzMemoryManager.INSTANCE.leaveRoom(player);
        return 1;
    }

    // ---------------- 调试命令（OP） ----------------

    /** 添加调试假人：/doudizhu debug bots <1|2>（等待中的房间，满 3 人自动开局）。 */
    private static int debugBots(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzMemoryManager.INSTANCE.addBots(player, count);
        return 1;
    }

    /** 移除调试假人：/doudizhu debug bots remove。 */
    private static int debugBotsRemove(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzMemoryManager.INSTANCE.removeBots(player);
        return 1;
    }

    /** 执行者本人托管开关：开启=自己进入自动托管；关闭=退出托管。
     *  /doudizhu debug auto <true|false>（配合 /execute as @a 可让所有玩家各自进入托管）。 */
    private static int debugAuto(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzMemoryManager.INSTANCE.setTrust(player, enabled);
        source.sendSuccess(() -> Component.literal("托管：" + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    /** 指挥当前轮到者叫分（真人与假人均可）：/doudizhu debug call <0|1|2|3>。 */
    private static int debugCall(CommandSourceStack source, int score) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        int seat = game.currentSeat();
        game.onCall(null, score);
        source.sendSuccess(() -> Component.literal("已指挥座位 " + seat + " 叫分：" + score), false);
        return 1;
    }

    /** 指挥当前轮到者抢地主（真人与假人均可）：/doudizhu debug rob <true|false>。 */
    private static int debugRob(CommandSourceStack source, boolean rob) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        int seat = game.currentSeat();
        game.onRob(null, rob);
        source.sendSuccess(() -> Component.literal("已指挥座位 " + seat + " 抢地主：" + (rob ? "抢" : "不抢")), false);
        return 1;
    }

    /**
     * 指挥当前轮到者出指定牌（真人与假人均可）：/doudizhu debug play &lt;牌串&gt;。
     * 牌串字符：3-9 T(10) J Q K A 2 X(小王) D(大王) F(花牌)，重复表示多张（如 "8888" 炸弹）。
     * 该座位手牌数量不足或字符非法时不行动。
     */
    private static int debugPlay(CommandSourceStack source, String cardsStr) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        int seat = game.currentSeat();
        List<DdzCard> cards = parseCards(cardsStr, game.handOf(seat));
        if (cards == null) {
            source.sendFailure(Component.literal("牌串非法或该座位手牌中数量不足（字符：3-9 T J Q K A 2 X小王 D大王 F花牌）"));
            return 0;
        }
        if (game.onPlay(null, cards)) {
            source.sendSuccess(() -> Component.literal("已指挥座位 " + seat + " 出牌：" + cardsStr), false);
            return 1;
        }
        source.sendFailure(Component.literal("该牌型不合法或无法压过上家，未出牌"));
        return 0;
    }

    /** 指挥当前轮到者不出（真人与假人均可）：/doudizhu debug pass。 */
    private static int debugPass(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        int seat = game.currentSeat();
        if (game.onPlay(null, null)) {
            source.sendSuccess(() -> Component.literal("已指挥座位 " + seat + " 不出"), false);
            return 1;
        }
        source.sendFailure(Component.literal("该座位当前不能不出（如自由出牌权）"));
        return 0;
    }

    /** 强制将指定玩家加入房间：/doudizhu debug forcejoin <玩家> [房间码]（缺省用执行者所在房间）。 */
    private static int debugForceJoin(CommandSourceStack source, ServerPlayer target, String roomCode) throws CommandSyntaxException {
        ServerPlayer executor = source.getPlayerOrException();
        final String code;
        if (roomCode != null) {
            code = roomCode;
        } else {
            DdzRoom room = DdzMemoryManager.INSTANCE.currentRoom(executor);
            if (room == null) {
                source.sendFailure(Component.literal("未指定房间码且你不在任何房间中"));
                return 0;
            }
            code = room.id;
        }
        String error = DdzMemoryManager.INSTANCE.forceJoin(target, code);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 加入房间 " + code), false);
        return 1;
    }

    /** 强制指定玩家退出游戏（对局中座位转机器人托管；房间无真人则关闭）：
     *  /doudizhu debug kick <玩家>。 */
    private static int debugKick(CommandSourceStack source, ServerPlayer target) {
        if (DdzMemoryManager.INSTANCE.currentRoom(target) == null) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " 不在任何房间中"));
            return 0;
        }
        DdzMemoryManager.INSTANCE.leaveRoom(target);
        source.sendSuccess(() -> Component.literal("已强制 " + target.getGameProfile().getName() + " 退出游戏"), false);
        return 1;
    }

    /** 强制指定座位开启/关闭托管（真人与假人均可，无需真人在线）：/doudizhu debug trust <0|1|2> <true|false>。 */
    private static int debugTrustSeat(CommandSourceStack source, int seat, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DdzGame game = gameOf(source, player);
        if (game == null) {
            return 0;
        }
        game.setTrustSeat(seat, enabled);
        source.sendSuccess(() -> Component.literal("已" + (enabled ? "开启" : "关闭") + " 座位 " + seat + " 的托管"), false);
        return 1;
    }

    /** 强制开启/关闭指定玩家的托管：/doudizhu debug trust <玩家> <true|false>。 */
    private static int debugTrust(CommandSourceStack source, ServerPlayer target, boolean enabled) {
        DdzGame game = DdzMemoryManager.INSTANCE.gameOf(target);
        if (game == null) {
            source.sendFailure(Component.literal(target.getGameProfile().getName() + " 不在对局中"));
            return 0;
        }
        game.setTrust(target, enabled);
        source.sendSuccess(() -> Component.literal("已" + (enabled ? "开启" : "关闭") + " " + target.getGameProfile().getName() + " 的托管"), false);
        return 1;
    }

    /** 解析牌串并从手牌中取牌；非法字符或数量不足返回 null。 */
    private static List<DdzCard> parseCards(String s, List<DdzCard> hand) {
        List<Integer> ranks = new ArrayList<>(s.length());
        for (char c : s.toCharArray()) {
            int v = switch (c) {
                case '3', '4', '5', '6', '7', '8', '9' -> c - '0';
                case '0', 'T', 't' -> 10;
                case 'J', 'j' -> 11;
                case 'Q', 'q' -> 12;
                case 'K', 'k' -> 13;
                case 'A', 'a' -> 14;
                case '2' -> 15;
                case 'X', 'x' -> 16; // 小王
                case 'D', 'd' -> 17; // 大王
                case 'F', 'f' -> 18; // 花牌
                default -> -1;
            };
            if (v < 0) {
                return null;
            }
            ranks.add(v);
        }
        List<DdzCard> result = new ArrayList<>(ranks.size());
        for (int v : ranks) {
            DdzCard found = null;
            for (DdzCard c : hand) {
                if (c.rankValue() == v && !result.contains(c)) {
                    found = c;
                    break;
                }
            }
            if (found == null) {
                return null; // 手牌中数量不足
            }
            result.add(found);
        }
        return result;
    }

    private static DdzGame gameOf(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        DdzGame game = DdzMemoryManager.INSTANCE.gameOf(player);
        if (game == null) {
            source.sendFailure(Component.literal("你不在任何对局中"));
        }
        return game;
    }
}
