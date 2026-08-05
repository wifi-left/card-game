package io.wifi.cards.doudizhu;

import io.wifi.cards.doudizhu.command.DdzCommands;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.network.DdzPackets;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 斗地主模块初始化（由 io.wifi.cards.CardGameMod 载入）。
 * 注册：网络包、命令、服务端 tick、断线事件、重连兜底、语音音效。
 */
public final class DdzMod {
    private DdzMod() {
    }

    public static void init() {
        DdzPackets.register();
        DdzSounds.init();
        DdzCommands.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(DdzMemoryManager.INSTANCE::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                DdzMemoryManager.INSTANCE.onPlayerDisconnect(handler.getPlayer()));
        // 重连兜底：进入服务器时若旧房间仍存在则关闭并通知
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                DdzMemoryManager.INSTANCE.onPlayerJoin(handler.getPlayer()));
    }
}
