package io.wifi.cards.common.client;

import io.wifi.cards.common.network.CommonPackets.OpenMenuS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 小游戏菜单客户端装配（由 io.wifi.cards.CardGameModClient 载入）：
 * <ul>
 *   <li>注册 OpenMenuS2C 接收器：缓存菜单数据并打开 {@link GameMenuScreen}</li>
 *   <li>会话注册表：各游戏 ClientState 在各自客户端 init 中调用
 *       {@link #registerSession} 注册；菜单/大厅关闭时据此恢复进行中的游戏界面，
 *       防止"开着斗地主牌局打开菜单后回不去"</li>
 * </ul>
 */
public final class GameMenuClient {
    private static final List<GameClientSession> SESSIONS = new CopyOnWriteArrayList<>();

    private GameMenuClient() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(OpenMenuS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> openMenu(payload)));
    }

    public static void registerSession(GameClientSession session) {
        // 按 gameId 去重：热重载/重复 init 时避免同一游戏注册多份（activeGameId/恢复会重复遍历）
        SESSIONS.removeIf(s -> s.gameId().equals(session.gameId()));
        SESSIONS.add(session);
    }

    /** 当前有会话的游戏 id（无则 null），菜单用于标记"当前"条目。 */
    public static String activeGameId() {
        for (GameClientSession s : SESSIONS) {
            if (s.hasSession()) {
                return s.gameId();
            }
        }
        return null;
    }

    /** 菜单关闭时恢复进行中的游戏会话界面；返回是否恢复（未恢复则正常关闭到桌面）。 */
    public static boolean tryRestoreSession() {
        for (GameClientSession s : SESSIONS) {
            if (s.hasSession()) {
                s.restoreScreen();
                return true;
            }
        }
        return false;
    }

    /** 关闭某游戏界面时，若玩家在其它游戏中有会话则恢复其界面（防从菜单进入后回不去）。 */
    public static boolean tryRestoreOtherSession(String excludeGameId) {
        for (GameClientSession s : SESSIONS) {
            if (!s.gameId().equals(excludeGameId) && s.hasSession()) {
                s.restoreScreen();
                return true;
            }
        }
        return false;
    }

    /** 菜单数据到达：缓存并打开菜单界面。 */
    private static void openMenu(OpenMenuS2C payload) {
        int n = Math.min(payload.gameIds().length,
                Math.min(payload.names().length, Math.min(payload.icons().length, payload.descs().length)));
        n = Math.min(n, Math.min(payload.colors().length,
                Math.min(payload.roomCounts().length, payload.playerCounts().length)));
        List<GameMenuScreen.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new GameMenuScreen.Entry(payload.gameIds()[i], payload.names()[i], payload.icons()[i],
                    payload.descs()[i], payload.colors()[i], payload.roomCounts()[i], payload.playerCounts()[i]));
        }
        Minecraft.getInstance().setScreen(new GameMenuScreen(entries));
    }
}
