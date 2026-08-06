package io.wifi.cards.common;

import io.wifi.cards.common.command.CardGamesCommands;
import io.wifi.cards.common.network.CommonPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 小游戏公共层初始化（由 io.wifi.cards.CardGameMod 在三个游戏模块之后载入，
 * 保证 GameRegistry 已登记全部游戏，/cardgames 命令树才能引用到它们）。
 * 注册：公共网络包（菜单数据）、/cardgames 统一命令、公共状态断线清理。
 */
public final class CommonMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private CommonMod() {
    }

    public static void init() {
        CommonPackets.register();
        CardGamesCommands.registerServer();
        // 断线清理（如菜单刷新频率限制记录）：DISCONNECT 在网络线程触发，
        // 仅清理线程安全的 ConcurrentHashMap，无需调度主线程
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            try {
                CommonPackets.onPlayerDisconnect(handler.getPlayer().getUUID());
            } catch (Throwable t) {
                LOGGER.error("处理小游戏公共层断线清理异常", t);
            }
        });
    }
}
