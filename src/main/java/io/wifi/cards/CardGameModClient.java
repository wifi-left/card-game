package io.wifi.cards;

import io.wifi.cards.doudizhu.DdzClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * 伞状客户端入口：载入各卡牌游戏模块的客户端初始化。
 */
public class CardGameModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DdzClient.init();
    }
}
