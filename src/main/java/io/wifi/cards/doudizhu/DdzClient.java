package io.wifi.cards.doudizhu;

import io.wifi.cards.doudizhu.command.DdzCommands;
import io.wifi.cards.doudizhu.network.DdzPackets;

/**
 * 斗地主模块客户端初始化（由 io.wifi.cards.CardGameModClient 载入）。
 * 注册：客户端网络接收器、客户端命令。
 */
public final class DdzClient {
    private DdzClient() {
    }

    public static void init() {
        DdzPackets.registerClient();
        DdzCommands.registerClient();
    }
}
