package io.wifi.cards;

import io.wifi.cards.board.BoardMod;
import io.wifi.cards.common.CommonMod;
import io.wifi.cards.doudizhu.DdzMod;
import io.wifi.cards.uno.UnoMod;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 伞状主入口：所有卡牌小游戏模块从这里被载入。
 * 目前已载入斗地主（io.wifi.cards.doudizhu）、棋类游戏（io.wifi.cards.board：
 * 黑白棋/五子棋/围棋）与 UNO（io.wifi.cards.uno），后续其他牌类游戏以同样方式挂载。
 * 小游戏公共层（io.wifi.cards.common：菜单 /cardgames 统一命令 / 跨游戏防护）
 * 在三个游戏模块之后载入（保证注册表已登记全部游戏）。
 * 模组 id 与 entrypoint 在 fabric.mod.json 中声明。
 */
public class CardGameMod implements ModInitializer {
    public static final String MOD_ID = "wifi-card-games";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DdzMod.init();
        LOGGER.info("[WifiCardGames] 已载入斗地主模块");
        BoardMod.init();
        LOGGER.info("[WifiCardGames] 已载入棋类模块（黑白棋/五子棋/围棋）");
        UnoMod.init();
        LOGGER.info("[WifiCardGames] 已载入 UNO 模块");
        CommonMod.init();
        LOGGER.info("[WifiCardGames] 已载入小游戏公共层（菜单 /cardgames / 跨游戏防护）");
    }
}
