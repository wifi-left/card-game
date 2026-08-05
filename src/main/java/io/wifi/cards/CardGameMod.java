package io.wifi.cards;

import io.wifi.cards.doudizhu.DdzMod;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 伞状主入口：所有卡牌小游戏模块从这里被载入。
 * 目前仅载入斗地主（io.wifi.cards.doudizhu），后续其他牌类游戏以同样方式挂载。
 * 模组 id 与 entrypoint 在 fabric.mod.json 中声明。
 */
public class CardGameMod implements ModInitializer {
    public static final String MOD_ID = "wifi-card-games";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DdzMod.init();
        LOGGER.info("[WifiCardGames] 已载入斗地主模块");
    }
}
