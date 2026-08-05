package io.wifi.cards.doudizhu;

import io.wifi.cards.doudizhu.command.DdzCommands;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.network.DdzPackets;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
        // 断线/进服回调在服务器主线程执行：任何意外异常不得导致服务器崩溃，记录日志后继续
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            try {
                DdzMemoryManager.INSTANCE.onPlayerDisconnect(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("处理玩家断线异常", t);
            }
        });
        // 重连兜底：进入服务器时若旧房间仍存在则关闭并通知
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                DdzMemoryManager.INSTANCE.onPlayerJoin(handler.getPlayer());
            } catch (Throwable t) {
                LOGGER.error("处理玩家进服异常", t);
            }
        });
    }
}
