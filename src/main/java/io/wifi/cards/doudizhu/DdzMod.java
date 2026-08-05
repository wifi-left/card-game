package io.wifi.cards.doudizhu;

import io.wifi.cards.doudizhu.command.DdzCommands;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.network.DdzPackets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * 斗地主模块初始化（由 io.wifi.cards.CardGameMod 载入）。
 * 注册：网络包、命令、服务端 tick、断线事件。
 */
public final class DdzMod {
    private DdzMod() {
    }

    public static void init() {
        DdzPackets.register();
        DdzCommands.registerServer();
        ServerTickEvents.END_SERVER_TICK.register(DdzMemoryManager.INSTANCE::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                DdzMemoryManager.INSTANCE.onPlayerDisconnect(handler.getPlayer()));
    }
}
