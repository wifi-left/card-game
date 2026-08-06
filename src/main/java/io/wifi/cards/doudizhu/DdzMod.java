package io.wifi.cards.doudizhu;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.RoomBrief;
import io.wifi.cards.doudizhu.command.DdzCommands;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 斗地主模块初始化（由 io.wifi.cards.CardGameMod 载入）。
 * 注册：网络包、命令、服务端 tick、断线事件、重连兜底、语音音效。
 */
public final class DdzMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private DdzMod() {
    }

    public static void init() {
        DdzPackets.register();
        DdzSounds.init();
        DdzCommands.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(DdzMemoryManager.INSTANCE::tick);
        // 登记到小游戏注册表：小游戏菜单 / 统一 /cardgames 命令 / 跨游戏防护自动生效
        GameRegistry.register(new GameInfo(
                GameRegistry.GAME_DOUDIZHU, GameRegistry.PREFIX_DOUDIZHU,
                "斗地主", "斗", 0xFFE53935,
                "经典 / 花牌万能牌，3 人对局",
                DdzCommands::openLobby,
                player -> DdzMemoryManager.INSTANCE.createRoom(player.server, player, false,
                        DdzRuleSet.STANDARD, true, 0),
                (player, code) -> DdzMemoryManager.INSTANCE.joinRoom(player, code),
                DdzMemoryManager.INSTANCE::spectate,
                DdzMemoryManager.INSTANCE::leaveRoom,
                DdzCommands::invite,
                player -> DdzMemoryManager.INSTANCE.currentRoom(player) != null
                        || DdzMemoryManager.INSTANCE.spectatingRoomId(player) != null,
                DdzMemoryManager.INSTANCE::roomCount,
                DdzMemoryManager.INSTANCE::playerCount,
                () -> DdzMemoryManager.INSTANCE.roomSnapshot().stream()
                        .map(r -> r.id + " · 人数 " + r.size + "/3 · " + DdzCommands.phaseName(r.phase()))
                        .toList(),
                // 房间列表行（/cardgames rooms）：管理员含未公开房间
                includePrivate -> DdzMemoryManager.INSTANCE.roomSnapshot().stream()
                        .filter(r -> includePrivate || r.announce)
                        .map(r -> new RoomBrief(r.id, "玩家 " + r.size + "/3 · " + DdzCommands.phaseName(r.phase()),
                                (byte) (r.phase() == DdzGamePhase.WAITING ? 0
                                        : r.phase() == DdzGamePhase.SETTLED ? 2 : 1)))
                        .toList(),
                DdzCommands::roomDetail,
                DdzMemoryManager.INSTANCE::deleteRoom,
                DdzMemoryManager.INSTANCE::clearAllRooms));
        // 断线/进服回调：Fabric 的 DISCONNECT 在网络线程触发（ClientConnection.channelInactive/
        // handleDisconnection），直接修改房间/对局共享状态会与主线程 tick 竞态——
        // 必须调度到服务器主线程执行；任何意外异常不得导致服务器崩溃，记录日志后继续。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            server.execute(() -> {
                try {
                    DdzMemoryManager.INSTANCE.onPlayerDisconnect(player);
                } catch (Throwable t) {
                    LOGGER.error("处理玩家断线异常", t);
                }
            });
        });
        // 重连兜底：进入服务器时若旧房间仍存在则关闭并通知（JOIN 在服务器主线程触发）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                DdzMemoryManager.INSTANCE.onPlayerJoin(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("处理玩家进服异常", t);
            }
        });
    }
}
