package io.wifi.cards.board;

import io.wifi.cards.board.command.BoardCommands;
import io.wifi.cards.board.manager.BoardMemoryManager;
import io.wifi.cards.board.network.BoardPackets;
import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 棋类模块初始化（黑白棋/五子棋/围棋，由 io.wifi.cards.CardGameMod 载入）。
 * 注册：网络包、命令、服务端 tick、断线事件、重连兜底。
 */
public final class BoardMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private BoardMod() {
    }

    public static void init() {
        BoardPackets.register();
        BoardCommands.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(BoardMemoryManager.INSTANCE::tick);
        // 登记到小游戏注册表：小游戏菜单 / 统一 /cardgames 命令 / 跨游戏防护自动生效
        GameRegistry.register(new GameInfo(
                GameRegistry.GAME_BOARD, GameRegistry.PREFIX_BOARD,
                "棋类", "棋", 0xFF1E88E5,
                "黑白棋 / 五子棋 / 围棋",
                BoardCommands::openLobby,
                (player, code) -> BoardMemoryManager.INSTANCE.joinRoom(player, code),
                BoardMemoryManager.INSTANCE::spectate,
                BoardMemoryManager.INSTANCE::leaveRoom,
                BoardCommands::invite,
                player -> BoardMemoryManager.INSTANCE.currentRoom(player) != null
                        || BoardMemoryManager.INSTANCE.spectatingRoomId(player) != null,
                BoardMemoryManager.INSTANCE::roomCount,
                BoardMemoryManager.INSTANCE::playerCount,
                () -> BoardMemoryManager.INSTANCE.roomSnapshot().stream()
                        .map(r -> r.id + " · " + r.gameType.displayName
                                + " · 人数 " + r.count + "/2 · " + BoardCommands.phaseName(r.phase()))
                        .toList(),
                BoardMemoryManager.INSTANCE::deleteRoom,
                BoardMemoryManager.INSTANCE::clearAllRooms));
        // 断线/进服回调：Fabric 的 DISCONNECT 在网络线程触发（ClientConnection.channelInactive/
        // handleDisconnection），直接修改房间/对局共享状态会与主线程 tick 竞态——
        // 必须调度到服务器主线程执行；任何意外异常不得导致服务器崩溃，记录日志后继续。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            server.execute(() -> {
                try {
                    BoardMemoryManager.INSTANCE.onPlayerDisconnect(player);
                } catch (Throwable t) {
                    LOGGER.error("处理玩家断线异常", t);
                }
            });
        });
        // 重连兜底：进入服务器时若旧房间仍存在则关闭并通知
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                BoardMemoryManager.INSTANCE.onPlayerJoin(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("处理玩家进服异常", t);
            }
        });
    }
}
