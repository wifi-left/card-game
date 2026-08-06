package io.wifi.cards.uno;

import io.wifi.cards.uno.gui.UnoLobbyScreen;
import io.wifi.cards.uno.network.UnoPackets.OpenLobbyS2C;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.uno.gui.UnoClientState;
import io.wifi.cards.uno.network.UnoPackets.DrawBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawPenaltyS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawResultS2C;
import io.wifi.cards.uno.network.UnoPackets.DebugSpectatorS2C;
import io.wifi.cards.uno.network.UnoPackets.GameResultS2C;
import io.wifi.cards.uno.network.UnoPackets.GameStartS2C;
import io.wifi.cards.uno.network.UnoPackets.HistoryS2C;
import io.wifi.cards.uno.network.UnoPackets.NoticeS2C;
import io.wifi.cards.uno.network.UnoPackets.PassBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.PlayBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.ReconnectS2C;
import io.wifi.cards.uno.network.UnoPackets.RoomClosedS2C;
import io.wifi.cards.uno.network.UnoPackets.RoomStateS2C;
import io.wifi.cards.uno.network.UnoPackets.SpectatorHandsS2C;
import io.wifi.cards.uno.network.UnoPackets.TrustStateS2C;
import io.wifi.cards.uno.network.UnoPackets.TurnS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoCatchS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoDeclaredS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * UNO 模块客户端初始化（由 io.wifi.cards.CardGameModClient 载入）。
 * <p>所有客户端专属逻辑集中于此类（客户端网络接收器、屏幕调度），
 * 服务端可达的类不得引用含 client 的包，否则服务端无法启动。</p>
  * 接收器在客户端主线程执行 setScreen，不存在命令线程被聊天界面覆盖的问题。</p>
 */
public final class UnoClient {
    private UnoClient() {
    }

    public static void init() {
        registerReceivers();
        registerDisconnectCleanup();
        // 注册小游戏菜单会话：菜单/其它大厅关闭时据此恢复 UNO 界面
        GameMenuClient.registerSession(UnoClientState.INSTANCE);
    }

    /**
     * 离开服务器/世界（断线、退出地图、切换服务器）时清空本地房间缓存：
     * 断开时收不到服务端 RoomClosedS2C，不清空会导致重进后残留旧房间状态。
     * JOIN 同步清理作为兜底：无论退出时发生了什么（踢出/服务器关闭/断线未触发清理），
     * 重进服务器后本地状态一律从零开始，再随服务端包重建。
     */
    private static void registerDisconnectCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(UnoClientState.INSTANCE::clearAll));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(UnoClientState.INSTANCE::clearAll));
    }

    // ---------------- 客户端网络接收器 ----------------

    private static void registerReceivers() {
        UnoClientState state = UnoClientState.INSTANCE;
        ClientPlayNetworking.registerGlobalReceiver(RoomStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameStartS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onGameStart(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ReconnectS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onReconnect(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PlayBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onPlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DrawResultS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onDrawResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DrawBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onDraw(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DrawPenaltyS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onDrawPenalty(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PassBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onPass(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TurnS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onTurn(payload)));
        ClientPlayNetworking.registerGlobalReceiver(UnoDeclaredS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onUnoDeclared(payload)));
        ClientPlayNetworking.registerGlobalReceiver(UnoCatchS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onUnoCatch(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TrustStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onTrustState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(HistoryS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onHistory(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameResultS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RoomClosedS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomClosed(payload.reason())));
        ClientPlayNetworking.registerGlobalReceiver(NoticeS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onNotice(payload.message())));
        ClientPlayNetworking.registerGlobalReceiver(SpectatorHandsS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onSpectatorHands(payload)));
        // 管理员调试命令 /uno debug spectateui：虚拟旁观数据打开"（调试）"旁观界面
        ClientPlayNetworking.registerGlobalReceiver(DebugSpectatorS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onDebugSpectator(payload)));
        // 服务端命令 /xxx 或 /cardgames open 触发：在主线程打开大厅
        ClientPlayNetworking.registerGlobalReceiver(OpenLobbyS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> Minecraft.getInstance().setScreen(new UnoLobbyScreen())));
    }
}
