package io.wifi.cards.board;

import io.wifi.cards.board.gui.BoardClientState;
import io.wifi.cards.board.gui.BoardLobbyScreen;
import io.wifi.cards.board.network.BoardPackets.DebugUiS2C;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.board.network.BoardPackets.GameResultS2C;
import io.wifi.cards.board.network.BoardPackets.GameStartS2C;
import io.wifi.cards.board.network.BoardPackets.MoveBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.NoticeS2C;
import io.wifi.cards.board.network.BoardPackets.OpenLobbyS2C;
import io.wifi.cards.board.network.BoardPackets.PassBroadcastS2C;
import io.wifi.cards.board.network.BoardPackets.ReconnectS2C;
import io.wifi.cards.board.network.BoardPackets.RoomClosedS2C;
import io.wifi.cards.board.network.BoardPackets.RoomListS2C;
import io.wifi.cards.board.network.BoardPackets.RoomStateS2C;
import io.wifi.cards.board.network.BoardPackets.SurrenderS2C;
import io.wifi.cards.board.network.BoardPackets.TurnS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * 棋类模块客户端初始化（黑白棋/五子棋/围棋，由 io.wifi.cards.CardGameModClient 载入）。
 * <p>所有客户端专属逻辑集中于此类（客户端网络接收器、屏幕调度），
 * 服务端可达的类不得引用含 client 的包，否则服务端无法启动。</p>
 * <p>没有客户端命令：打开 UI 统一由服务端命令发 OpenLobbyS2C 驱动，
 * 接收器在客户端主线程执行 setScreen，不存在命令线程被聊天界面覆盖的问题。</p>
 */
public final class BoardClient {
    private BoardClient() {
    }

    public static void init() {
        registerReceivers();
        registerDisconnectCleanup();
        // 注册小游戏菜单会话：菜单/其它大厅关闭时据此恢复棋类界面
        GameMenuClient.registerSession(BoardClientState.INSTANCE);
    }

    /**
     * 离开服务器/世界（断线、退出地图、切换服务器）时清空本地房间缓存：
     * 断开时收不到服务端 RoomClosedS2C，不清空会导致重进后残留旧房间状态。
     * JOIN 同步清理作为兜底：无论退出时发生了什么（踢出/服务器关闭/断线未触发清理），
     * 重进服务器后本地状态一律从零开始，再随服务端包重建。
     */
    private static void registerDisconnectCleanup() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(BoardClientState.INSTANCE::clearAll));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(BoardClientState.INSTANCE::clearAll));
    }

    // ---------------- 客户端网络接收器 ----------------

    private static void registerReceivers() {
        BoardClientState state = BoardClientState.INSTANCE;
        ClientPlayNetworking.registerGlobalReceiver(RoomStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameStartS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onGameStart(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ReconnectS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onReconnect(payload)));
        ClientPlayNetworking.registerGlobalReceiver(MoveBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onMove(payload)));
        ClientPlayNetworking.registerGlobalReceiver(PassBroadcastS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onPass(payload)));
        ClientPlayNetworking.registerGlobalReceiver(SurrenderS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onSurrender(payload)));
        ClientPlayNetworking.registerGlobalReceiver(TurnS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onTurn(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameResultS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(RoomClosedS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomClosed(payload.reason())));
        ClientPlayNetworking.registerGlobalReceiver(NoticeS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onNotice(payload.message())));
        ClientPlayNetworking.registerGlobalReceiver(RoomListS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomList(payload)));
        // 调试旁观界面（/board debug ui 触发）：随机虚拟对局数据，仅供 UI 检查
        ClientPlayNetworking.registerGlobalReceiver(DebugUiS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onDebugUi(payload)));
        // 服务端命令 /board 触发：在主线程打开大厅（同时退出调试旁观模式）
        ClientPlayNetworking.registerGlobalReceiver(OpenLobbyS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> {
                    BoardClientState.INSTANCE.debugMode = false;
                    Minecraft.getInstance().setScreen(new BoardLobbyScreen());
                }));
    }
}
