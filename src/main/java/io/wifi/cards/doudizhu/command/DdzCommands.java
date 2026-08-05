package io.wifi.cards.doudizhu.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.wifi.cards.doudizhu.gui.DdzClientState;
import io.wifi.cards.doudizhu.gui.DdzLobbyScreen;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.manager.DdzRoom;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.JoinRoomC2S;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 斗地主命令：
 * <ul>
 *   <li>客户端 <code>/doudizhu</code>：打开大厅</li>
 *   <li>客户端 <code>/doudizhu accept &lt;房间码&gt;</code>：接受邀请加入（由聊天点击触发）</li>
 *   <li>服务端 <code>/doudizhu invite &lt;玩家&gt;</code>：房主邀请玩家（被邀请者收到可点击消息）</li>
 *   <li>服务端 <code>/doudizhu leave</code>：离开房间</li>
 * </ul>
 */
public final class DdzCommands {
    private DdzCommands() {
    }

    // ---------------- 服务端 ----------------

    public static void registerServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("doudizhu")
                    .then(Commands.literal("invite")
                            .then(Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> invite(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                    .then(Commands.literal("leave")
                            .executes(ctx -> leave(ctx.getSource()))));
        });
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

    // ---------------- 客户端 ----------------

    public static void registerClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("doudizhu")
                    .executes(ctx -> openLobby(ctx.getSource()))
                    .then(ClientCommandManager.literal("accept")
                            .then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
                                    .executes(ctx -> accept(ctx.getSource(), StringArgumentType.getString(ctx, "code"))))));
        });
    }

    private static int openLobby(FabricClientCommandSource source) {
        DdzClientState state = DdzClientState.INSTANCE;
        if (state.inGame()) {
            source.sendError(Component.literal("你正在对局中，无法打开大厅"));
            return 0;
        }
        Minecraft.getInstance().setScreen(new DdzLobbyScreen());
        return 1;
    }

    private static int accept(FabricClientCommandSource source, String code) {
        ClientPlayNetworking.send(new JoinRoomC2S(code.trim().toUpperCase()));
        return 1;
    }
}
