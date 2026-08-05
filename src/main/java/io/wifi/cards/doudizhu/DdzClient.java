package io.wifi.cards.doudizhu;

import io.wifi.cards.doudizhu.gui.DdzClientState;
import io.wifi.cards.doudizhu.gui.DdzGameScreen;
import io.wifi.cards.doudizhu.gui.DdzLobbyScreen;
import io.wifi.cards.doudizhu.network.DdzPackets.CallBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameResultS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameStartS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.HistoryS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.LandlordS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.OpenLobbyS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PassBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.ReconnectS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RevealS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RobBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomClosedS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RoomStateS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.SpectatorHandsS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TurnS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TrustStateS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * 斗地主模块客户端初始化（由 io.wifi.cards.CardGameModClient 载入）。
 * <p>所有客户端专属逻辑集中于此类（客户端网络接收器、屏幕调度），
 * 服务端可达的类不得引用含 client 的包，否则服务端无法启动。</p>
 * <p>没有客户端命令：打开 UI 统一由服务端命令发 OpenLobbyS2C 驱动，
 * 接收器在客户端主线程执行 setScreen，不存在命令线程被聊天界面覆盖的问题。</p>
 */
public final class DdzClient {
    private DdzClient() {
    }

    public static void init() {
        registerReceivers();
        registerDisconnectCleanup();
        registerBgmTick();
    }

    /**
     * 背景音乐每 tick 驱动：当前处于打牌上下文（打牌界面或其子界面）时保持循环播放
     * （已在播放不重启），离开才停止。
     */
    private static void registerBgmTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                DdzGameScreen.tickBgm(client.screen));
    }

    /**
     * 离开服务器/世界（断线、退出地图、切换服务器）时清空本地房间缓存：
     * 断开时收不到服务端 RoomClosedS2C，不清空会导致重进后残留旧房间状态。
     */
    private static void registerDisconnectCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(DdzClientState.INSTANCE::clearAll));
    }

    // ---------------- 客户端网络接收器 ----------------

    private static void registerReceivers() {
        DdzClientState state = DdzClientState.INSTANCE;
        ClientPlayNetworking.registerGlobalReceiver(RoomStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameStartS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onGameStart(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ReconnectS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onReconnect(payload)));
        ClientPlayNetworking.registerGlobalReceiver(CallBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onCall(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RobBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRob(payload)));
        ClientPlayNetworking.registerGlobalReceiver(LandlordS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onLandlord(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PlayBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onPlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PassBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onPass(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TurnS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onTurn(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameResultS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RoomClosedS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomClosed(payload.reason())));
        ClientPlayNetworking.registerGlobalReceiver(NoticeS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onNotice(payload.message())));
        ClientPlayNetworking.registerGlobalReceiver(RevealS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onReveal(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TrustStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onTrustState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(HistoryS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onHistory(payload)));
        ClientPlayNetworking.registerGlobalReceiver(SpectatorHandsS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onSpectatorHands(payload)));
        // 服务端命令 /doudizhu 触发：在主线程打开大厅
        ClientPlayNetworking.registerGlobalReceiver(OpenLobbyS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> Minecraft.getInstance().setScreen(new DdzLobbyScreen())));
    }
}
