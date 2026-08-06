package io.wifi.cards.uno;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.uno.command.UnoCommands;
import io.wifi.cards.uno.manager.UnoMemoryManager;
import io.wifi.cards.uno.manager.UnoRoom;
import io.wifi.cards.uno.network.UnoPackets;
import io.wifi.cards.uno.sound.UnoSounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UNO 模块初始化（由 io.wifi.cards.CardGameMod 载入）。
 * 注册：网络包、命令、服务端 tick、断线事件、重连兜底、语音音效。
 */
public final class UnoMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private UnoMod() {
    }

    public static void init() {
        UnoPackets.register();
        UnoSounds.init();
        UnoCommands.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(UnoMemoryManager.INSTANCE::tick);
        // 登记到小游戏注册表：小游戏菜单 / 统一 /cardgames 命令 / 跨游戏防护自动生效
        GameRegistry.register(new GameInfo(
                GameRegistry.GAME_UNO, GameRegistry.PREFIX_UNO,
                "UNO", "U", 0xFFFFB300,
                "2~10 人，功能牌与 UNO 抓捕",
                UnoCommands::openLobby,
                (player, code) -> UnoMemoryManager.INSTANCE.joinRoom(player, code),
                UnoMemoryManager.INSTANCE::spectate,
                UnoMemoryManager.INSTANCE::leaveRoom,
                UnoCommands::invite,
                player -> UnoMemoryManager.INSTANCE.currentRoom(player) != null
                        || UnoMemoryManager.INSTANCE.spectatingRoomId(player) != null,
                UnoMemoryManager.INSTANCE::roomCount,
                UnoMemoryManager.INSTANCE::playerCount,
                () -> UnoMemoryManager.INSTANCE.roomSnapshot().stream()
                        .map(r -> r.id + " · 人数 " + r.size() + "/" + UnoRoom.MAX_PLAYERS
                                + " · " + UnoCommands.phaseName(r.phase()))
                        .toList(),
                UnoMemoryManager.INSTANCE::deleteRoom,
                UnoMemoryManager.INSTANCE::clearAllRooms));
        // 断线/进服回调：Fabric 的 DISCONNECT 在网络线程触发（ClientConnection.channelInactive/
        // handleDisconnection），直接修改房间/对局共享状态会与主线程 tick 竞态——
        // 必须调度到服务器主线程执行；任何意外异常不得导致服务器崩溃，记录日志后继续。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            server.execute(() -> {
                try {
                    UnoMemoryManager.INSTANCE.onPlayerDisconnect(player);
                } catch (Throwable t) {
                    LOGGER.error("处理 UNO 玩家断线异常", t);
                }
            });
        });
        // 重连兜底：进入服务器时若旧房间仍存在则关闭并通知
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                UnoMemoryManager.INSTANCE.onPlayerJoin(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("处理 UNO 玩家进服异常", t);
            }
        });
    }
}
