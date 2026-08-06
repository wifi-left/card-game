package io.wifi.cards.common;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 小游戏注册表：所有小游戏在此登记（元信息 + 房间操作路由），
 * 供小游戏菜单（/cardgames）、跨游戏防护与统一房间码前缀解析使用。
 * <p>扩展方式：新游戏在自身 XxxMod.init() 中调用 {@link #register(GameInfo)} 登记，
 * 菜单自动出现、/cardgames 自动路由、跨游戏防护自动生效，无需改动本类。</p>
 * <p>统一房间号：格式为 "前缀-5位码"（如 DZ-AB12K / UN-Q2M8X / BD-F9D4W），
 * 前缀唯一对应游戏，{@link #gameOfCode} 据此把 /cardgames 的加入/旁观路由到正确游戏。</p>
 */
public final class GameRegistry {
    // ---- 游戏 id（跨模块统一引用） ----

    public static final String GAME_DOUDIZHU = "doudizhu";
    public static final String GAME_UNO = "uno";
    public static final String GAME_BOARD = "board";

    // ---- 房间码前缀（统一格式：前缀-5位码） ----

    public static final String PREFIX_DOUDIZHU = "DZ";
    public static final String PREFIX_UNO = "UN";
    public static final String PREFIX_BOARD = "BD";

    private static final List<GameInfo> GAMES = new CopyOnWriteArrayList<>();

    private GameRegistry() {
    }

    public static void register(GameInfo info) {
        GAMES.add(info);
    }

    /** 已登记的全部游戏（菜单/命令按登记顺序展示）。 */
    public static List<GameInfo> all() {
        return GAMES;
    }

    /** 按游戏 id 查找，未登记返回 null。 */
    public static GameInfo byId(String gameId) {
        for (GameInfo info : GAMES) {
            if (info.gameId().equals(gameId)) {
                return info;
            }
        }
        return null;
    }

    /** 按完整房间码（含前缀，如 "DZ-AB12K"）解析所属游戏；无前缀或前缀未知返回 null。 */
    public static GameInfo gameOfCode(String code) {
        if (code == null) {
            return null;
        }
        String norm = code.trim().toUpperCase();
        int dash = norm.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        String prefix = norm.substring(0, dash);
        for (GameInfo info : GAMES) {
            if (info.prefix().equals(prefix)) {
                return info;
            }
        }
        return null;
    }

    /** 玩家当前所在的小游戏（房间成员或旁观）；无则 null。 */
    public static GameInfo currentGame(ServerPlayer player) {
        for (GameInfo info : GAMES) {
            if (info.busy().test(player)) {
                return info;
            }
        }
        return null;
    }

    /**
     * 跨游戏防护：玩家是否在其它小游戏中有会话（房间成员或旁观）。
     * 返回占用中的游戏（用于拒绝消息"你正在【X】中…"），空闲返回 null。
     * <p>基于查询各游戏管理器（各自维护成员/旁观映射），无额外共享状态、无同步问题。</p>
     */
    public static GameInfo busyInOtherGame(ServerPlayer player, String gameId) {
        for (GameInfo info : GAMES) {
            if (!info.gameId().equals(gameId) && info.busy().test(player)) {
                return info;
            }
        }
        return null;
    }

    /** 可用游戏 id 列表文案（命令错误提示用）。 */
    public static String gameIdsText() {
        StringBuilder sb = new StringBuilder();
        for (GameInfo info : GAMES) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(info.gameId());
        }
        return sb.toString();
    }

    /** 房间码示例文案（命令错误提示用），如 "DZ-XXXXX"。 */
    public static String exampleCode() {
        return GAMES.isEmpty() ? "XX-XXXXX" : GAMES.get(0).prefix() + "-XXXXX";
    }
}
