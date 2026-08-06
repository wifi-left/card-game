package io.wifi.cards.board.network;

import io.wifi.cards.board.manager.BoardMemoryManager;
import io.wifi.cards.board.model.BoardGameType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 棋类游戏全部网络包（黑白棋/五子棋/围棋共用一套协议，1.21.1 CustomPayload API）。
 * <p>C2S：创建/加入/离开房间、落子、停一手、认输、再来一局、旁观、大厅查询。</p>
 * <p>S2C：房间状态、开局、落子广播（完整棋盘）、停手广播、认输、轮到谁、结算、房间关闭、通知、重连快照、房间列表。</p>
 * <p>棋盘统一序列化为 byte[]（行优先，0=空 1=黑 2=白，最大 19×19=361 字节），
 * 三个游戏共用同一协议，客户端按游戏类型渲染。</p>
 * <p><b>服务端安全：</b>本类不引用任何含 client 的包（客户端接收器在 BoardClient 中注册）。</p>
 */
public final class BoardPackets {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private BoardPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("wifi-card-games", "board_" + path);
    }

    // ---------------- C2S ----------------

    public static final ResourceLocation CREATE_ROOM = id("create_room");
    public static final ResourceLocation JOIN_ROOM = id("join_room");
    public static final ResourceLocation LEAVE_ROOM = id("leave_room");
    public static final ResourceLocation MOVE = id("move");
    public static final ResourceLocation PASS = id("pass");
    public static final ResourceLocation SURRENDER = id("surrender");
    public static final ResourceLocation NEXT_GAME = id("next_game");
    public static final ResourceLocation SPECTATE = id("spectate");
    public static final ResourceLocation SPECTATE_LEAVE = id("spectate_leave");
    public static final ResourceLocation LOBBY_QUERY = id("lobby_query");

    // ---------------- S2C ----------------

    public static final ResourceLocation ROOM_STATE = id("room_state");
    public static final ResourceLocation GAME_START = id("game_start");
    public static final ResourceLocation MOVE_BROADCAST = id("move_broadcast");
    public static final ResourceLocation PASS_BROADCAST = id("pass_broadcast");
    public static final ResourceLocation SURRENDER_BROADCAST = id("surrender_broadcast");
    public static final ResourceLocation TURN = id("turn");
    public static final ResourceLocation GAME_RESULT = id("game_result");
    public static final ResourceLocation ROOM_CLOSED = id("room_closed");
    public static final ResourceLocation NOTICE = id("notice");
    public static final ResourceLocation RECONNECT = id("reconnect");
    public static final ResourceLocation OPEN_LOBBY = id("open_lobby");
    public static final ResourceLocation ROOM_LIST = id("room_list");
    public static final ResourceLocation DEBUG_UI = id("debug_ui");

    // ---------------- Payload 定义 ----------------

    /** 创建房间（C2S）。gameType=游戏类型序号；size=棋盘边长（仅围棋 9/19 生效）；announce=是否公布到聊天栏；botCount=机器人数量（围棋忽略）。 */
    public record CreateRoomC2S(byte gameType, byte size, boolean announce, byte botCount) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CreateRoomC2S> TYPE = new CustomPacketPayload.Type<>(CREATE_ROOM);
        public static final StreamCodec<FriendlyByteBuf, CreateRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.gameType());
                    buf.writeByte(value.size());
                    buf.writeBoolean(value.announce());
                    buf.writeByte(value.botCount());
                },
                buf -> new CreateRoomC2S(buf.readByte(), buf.readByte(), buf.readBoolean(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 加入房间（C2S），roomCode 为完整房间码（含前缀，如 BD-AB12K；也兼容裸码）。 */
    public record JoinRoomC2S(String roomCode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<JoinRoomC2S> TYPE = new CustomPacketPayload.Type<>(JOIN_ROOM);
        public static final StreamCodec<FriendlyByteBuf, JoinRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.roomCode()),
                buf -> new JoinRoomC2S(buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 离开房间（C2S）。 */
    public record LeaveRoomC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LeaveRoomC2S> TYPE = new CustomPacketPayload.Type<>(LEAVE_ROOM);
        public static final StreamCodec<FriendlyByteBuf, LeaveRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new LeaveRoomC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 落子（C2S），x/y 为格坐标（围棋为交叉点坐标）。 */
    public record MoveC2S(byte x, byte y) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MoveC2S> TYPE = new CustomPacketPayload.Type<>(MOVE);
        public static final StreamCodec<FriendlyByteBuf, MoveC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.x());
                    buf.writeByte(value.y());
                },
                buf -> new MoveC2S(buf.readByte(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 停一手（C2S，仅围棋可用）。 */
    public record PassC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PassC2S> TYPE = new CustomPacketPayload.Type<>(PASS);
        public static final StreamCodec<FriendlyByteBuf, PassC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new PassC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 认输（C2S）。 */
    public record SurrenderC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SurrenderC2S> TYPE = new CustomPacketPayload.Type<>(SURRENDER);
        public static final StreamCodec<FriendlyByteBuf, SurrenderC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new SurrenderC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 再来一局（C2S，仅结算阶段有效）。 */
    public record NextGameC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<NextGameC2S> TYPE = new CustomPacketPayload.Type<>(NEXT_GAME);
        public static final StreamCodec<FriendlyByteBuf, NextGameC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new NextGameC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 旁观房间（C2S）。 */
    public record SpectateC2S(String roomCode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SpectateC2S> TYPE = new CustomPacketPayload.Type<>(SPECTATE);
        public static final StreamCodec<FriendlyByteBuf, SpectateC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.roomCode()),
                buf -> new SpectateC2S(buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 退出旁观（C2S）。 */
    public record SpectateLeaveC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SpectateLeaveC2S> TYPE = new CustomPacketPayload.Type<>(SPECTATE_LEAVE);
        public static final StreamCodec<FriendlyByteBuf, SpectateLeaveC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new SpectateLeaveC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 大厅房间列表请求（C2S，大厅界面打开时与定期刷新）。 */
    public record LobbyQueryC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LobbyQueryC2S> TYPE = new CustomPacketPayload.Type<>(LOBBY_QUERY);
        public static final StreamCodec<FriendlyByteBuf, LobbyQueryC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new LobbyQueryC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 房间状态（S2C，逐人发送；旁观者 mySeat=-1）。 */
    public record RoomStateS2C(String roomCode, byte gameType, byte size, byte phaseOrdinal, byte mySeat,
                              String[] names, String[] uuids, boolean[] connected) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomStateS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_STATE);
        public static final StreamCodec<FriendlyByteBuf, RoomStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.roomCode());
                    buf.writeByte(value.gameType());
                    buf.writeByte(value.size());
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeByte(value.mySeat());
                    buf.writeCollection(Arrays.asList(value.names()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(Arrays.asList(value.uuids()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(boxBooleans(value.connected()), (b, v) -> b.writeBoolean(v));
                },
                buf -> new RoomStateS2C(
                        buf.readUtf(),
                        buf.readByte(),
                        buf.readByte(),
                        buf.readByte(),
                        buf.readByte(),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        readBooleans(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 开局（S2C）：初始棋盘 + 先手座位（黑方 = 座位 0）。 */
    public record GameStartS2C(byte[] board, byte firstSeat) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameStartS2C> TYPE = new CustomPacketPayload.Type<>(GAME_START);
        public static final StreamCodec<FriendlyByteBuf, GameStartS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByteArray(value.board());
                    buf.writeByte(value.firstSeat());
                },
                buf -> new GameStartS2C(buf.readByteArray(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 落子广播（S2C）：座位 + 落点 + 完整棋盘（客户端直接覆盖渲染）。 */
    public record MoveBroadcastS2C(byte seat, byte x, byte y, byte[] board) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MoveBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(MOVE_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, MoveBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeByte(value.x());
                    buf.writeByte(value.y());
                    buf.writeByteArray(value.board());
                },
                buf -> new MoveBroadcastS2C(buf.readByte(), buf.readByte(), buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 停一手广播（S2C）。 */
    public record PassBroadcastS2C(byte seat, String name) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PassBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(PASS_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, PassBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeUtf(value.name());
                },
                buf -> new PassBroadcastS2C(buf.readByte(), buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 认输广播（S2C）：胜者座位与名字（随后跟 GameResultS2C 结算）。 */
    public record SurrenderS2C(byte winnerSeat, String winnerName) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SurrenderS2C> TYPE = new CustomPacketPayload.Type<>(SURRENDER_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, SurrenderS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.winnerSeat());
                    buf.writeUtf(value.winnerName());
                },
                buf -> new SurrenderS2C(buf.readByte(), buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 轮到谁（S2C）：座位 + 行动截止游戏刻（两端共用 level.getGameTime() 基准）。 */
    public record TurnS2C(byte seat, long endGameTime) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TurnS2C> TYPE = new CustomPacketPayload.Type<>(TURN);
        public static final StreamCodec<FriendlyByteBuf, TurnS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeLong(value.endGameTime());
                },
                buf -> new TurnS2C(buf.readByte(), buf.readLong()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 结算（S2C）：胜者座位（-1=平局）+ 分数 + 原因（0=终局 1=认输）。 */
    public record GameResultS2C(byte winSeat, String winName, int blackScore, int whiteScore, byte reason) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameResultS2C> TYPE = new CustomPacketPayload.Type<>(GAME_RESULT);
        public static final StreamCodec<FriendlyByteBuf, GameResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.winSeat());
                    buf.writeUtf(value.winName());
                    buf.writeInt(value.blackScore());
                    buf.writeInt(value.whiteScore());
                    buf.writeByte(value.reason());
                },
                buf -> new GameResultS2C(buf.readByte(), buf.readUtf(), buf.readInt(), buf.readInt(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 房间关闭（S2C），reason 为空表示无提示（正常返回大厅）。 */
    public record RoomClosedS2C(String reason) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomClosedS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_CLOSED);
        public static final StreamCodec<FriendlyByteBuf, RoomClosedS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.reason()),
                buf -> new RoomClosedS2C(buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 通知（S2C）：错误提示等，显示到聊天栏。 */
    public record NoticeS2C(String message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<NoticeS2C> TYPE = new CustomPacketPayload.Type<>(NOTICE);
        public static final StreamCodec<FriendlyByteBuf, NoticeS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.message()),
                buf -> new NoticeS2C(buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 重连快照（S2C）：完整棋盘 + 阶段 + 当前座位 + 截止刻 + 最近动作文本（房间信息由 RoomStateS2C 先行同步）。 */
    public record ReconnectS2C(byte[] board, byte phaseOrdinal, byte currentSeat, long endGameTime, String lastAction) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ReconnectS2C> TYPE = new CustomPacketPayload.Type<>(RECONNECT);
        public static final StreamCodec<FriendlyByteBuf, ReconnectS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByteArray(value.board());
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeByte(value.currentSeat());
                    buf.writeLong(value.endGameTime());
                    buf.writeUtf(value.lastAction());
                },
                buf -> new ReconnectS2C(buf.readByteArray(), buf.readByte(), buf.readByte(), buf.readLong(), buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 打开大厅（S2C，由 /board 命令触发）。 */
    public record OpenLobbyS2C() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenLobbyS2C> TYPE = new CustomPacketPayload.Type<>(OPEN_LOBBY);
        public static final StreamCodec<FriendlyByteBuf, OpenLobbyS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new OpenLobbyS2C());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 大厅房间列表（S2C，响应 LobbyQueryC2S）。statuses：0=等待中可加入 1=对局中可旁观 2=已结束。 */
    public record RoomListS2C(String[] codes, String[] lines, byte[] statuses) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomListS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_LIST);
        public static final StreamCodec<FriendlyByteBuf, RoomListS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeCollection(Arrays.asList(value.codes()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(Arrays.asList(value.lines()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(boxBytes(value.statuses()), (b, v) -> b.writeByte(v));
                },
                buf -> new RoomListS2C(
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        readBytes(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 调试旁观界面（S2C，OP 命令 /board debug ui 触发）：随机虚拟对局数据，无房间，仅供 UI 检查。 */
    public record DebugUiS2C(byte gameType, byte size, byte[] board, String[] names, byte currentSeat) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DebugUiS2C> TYPE = new CustomPacketPayload.Type<>(DEBUG_UI);
        public static final StreamCodec<FriendlyByteBuf, DebugUiS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.gameType());
                    buf.writeByte(value.size());
                    buf.writeByteArray(value.board());
                    buf.writeCollection(Arrays.asList(value.names()), (b, s) -> b.writeUtf(s));
                    buf.writeByte(value.currentSeat());
                },
                buf -> new DebugUiS2C(buf.readByte(), buf.readByte(), buf.readByteArray(),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- 注册 ----------------

    /** 注册全部 payload 类型 + 服务端接收器（主入口调用，客户端也会执行此方法）。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(CreateRoomC2S.TYPE, CreateRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinRoomC2S.TYPE, JoinRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveRoomC2S.TYPE, LeaveRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MoveC2S.TYPE, MoveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PassC2S.TYPE, PassC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SurrenderC2S.TYPE, SurrenderC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(NextGameC2S.TYPE, NextGameC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateC2S.TYPE, SpectateC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateLeaveC2S.TYPE, SpectateLeaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LobbyQueryC2S.TYPE, LobbyQueryC2S.CODEC);

        PayloadTypeRegistry.playS2C().register(RoomStateS2C.TYPE, RoomStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameStartS2C.TYPE, GameStartS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MoveBroadcastS2C.TYPE, MoveBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PassBroadcastS2C.TYPE, PassBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(SurrenderS2C.TYPE, SurrenderS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TurnS2C.TYPE, TurnS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameResultS2C.TYPE, GameResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomClosedS2C.TYPE, RoomClosedS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(NoticeS2C.TYPE, NoticeS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ReconnectS2C.TYPE, ReconnectS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenLobbyS2C.TYPE, OpenLobbyS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomListS2C.TYPE, RoomListS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(DebugUiS2C.TYPE, DebugUiS2C.CODEC);

        registerServerReceivers();
    }

    // 客户端接收器在 io.wifi.cards.board.BoardClient（客户端类）中注册，
    // 本类不引用任何含 client 的包，保证服务端可正常加载。

    private static void registerServerReceivers() {
        BoardMemoryManager m = BoardMemoryManager.INSTANCE;
        // 所有 C2S 处理统一调度到服务器主线程执行：Fabric 接收器运行在 netty 线程，
        // 直接修改房间/对局共享状态会与主线程 tick 产生竞态（members/count/spectators 非线程安全）。
        // 处理体统一 guarded 包装：主线程任务抛异常会崩溃整个服务器，任何意外只记录日志。
        ServerPlayNetworking.registerGlobalReceiver(CreateRoomC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> guarded(() -> {
            // 防御：游戏类型序号越界（恶意客户端可发送任意 byte）时回退黑白棋；
            // 机器人数量钳制 0~1（围棋由 createRoom 内部忽略）
            byte gt = payload.gameType();
            BoardGameType gameType = gt >= 0 && gt < BoardGameType.values().length
                    ? BoardGameType.values()[gt] : BoardGameType.OTHELLO;
            m.createRoom(ctx.server(), ctx.player(), gameType, payload.size(), payload.announce(),
                    Math.max(0, Math.min(payload.botCount(), 1)));
        })));
        ServerPlayNetworking.registerGlobalReceiver(JoinRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.joinRoom(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(LeaveRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveRoom(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(MoveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onMove(ctx.player(), payload.x(), payload.y()))));
        ServerPlayNetworking.registerGlobalReceiver(PassC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onPass(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(SurrenderC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onSurrender(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(NextGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.nextGame(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.spectate(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateLeaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveSpectate(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(LobbyQueryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.sendRoomList(ctx.player()))));
    }

    // ---------------- 序列化辅助 ----------------

    /** boolean[] → List&lt;Boolean&gt;（StreamCodec 的 writeCollection 只接受装箱集合）。 */
    private static java.util.List<Boolean> boxBooleans(boolean[] arr) {
        java.util.List<Boolean> list = new java.util.ArrayList<>(arr.length);
        for (boolean v : arr) {
            list.add(v);
        }
        return list;
    }

    /** byte[] → List&lt;Byte&gt;。 */
    private static java.util.List<Byte> boxBytes(byte[] arr) {
        java.util.List<Byte> list = new java.util.ArrayList<>(arr.length);
        for (byte v : arr) {
            list.add(v);
        }
        return list;
    }

    /** 读取 boolean 列表为原始数组（StreamCodec 的 readCollection 只能产出装箱类型）。 */
    private static boolean[] readBooleans(FriendlyByteBuf buf) {
        java.util.List<Boolean> list = buf.readCollection(java.util.ArrayList::new, FriendlyByteBuf::readBoolean);
        boolean[] arr = new boolean[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /** 读取 byte 列表为原始数组。 */
    private static byte[] readBytes(FriendlyByteBuf buf) {
        java.util.List<Byte> list = buf.readCollection(java.util.ArrayList::new, FriendlyByteBuf::readByte);
        byte[] arr = new byte[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /** 主线程任务防护：意外异常只记录日志，绝不让服务器崩溃。 */
    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("处理棋牌网络包异常", t);
        }
    }
}
