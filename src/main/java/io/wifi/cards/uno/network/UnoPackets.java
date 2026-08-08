package io.wifi.cards.uno.network;

import io.wifi.cards.uno.manager.UnoMemoryManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UNO 全部网络包（1.21.1 新版 CustomPayload API）。
 * <p>C2S：创建/加入/离开房间、开始游戏、出牌（含万能牌选色）、抽牌、跳过、喊 UNO、抓 UNO、再来一局。</p>
 * <p>S2C：房间状态、发牌、出牌/抽牌/罚牌/跳过广播、轮到谁、UNO 喊牌/抓捕、结算、房间关闭、通知。</p>
 * <p>序列化统一使用 StreamCodec + FriendlyByteBuf；牌只传 id（见 {@code UnoCard}）。
 * 手牌保密：抽牌结果只私发给本人（DrawResultS2C），其余玩家只见张数变化。</p>
 * <p><b>服务端安全：</b>本类不引用任何含 client 的包（客户端接收器在 UnoClient 中注册）。</p>
 */
public final class UnoPackets {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private UnoPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("wifi-card-games", "uno_" + path);
    }

    // ---------------- C2S ----------------

    public static final ResourceLocation CREATE_ROOM = id("create_room");
    public static final ResourceLocation JOIN_ROOM = id("join_room");
    public static final ResourceLocation LEAVE_ROOM = id("leave_room");
    public static final ResourceLocation START_GAME = id("start_game");
    public static final ResourceLocation PLAY_CARD = id("play_card");
    public static final ResourceLocation DRAW = id("draw");
    public static final ResourceLocation PASS = id("pass");
    public static final ResourceLocation DECLARE_UNO = id("declare_uno");
    public static final ResourceLocation CATCH_UNO = id("catch_uno");
    public static final ResourceLocation TOGGLE_TRUST = id("toggle_trust");
    public static final ResourceLocation HISTORY_REQUEST = id("history_request");
    public static final ResourceLocation NEXT_GAME = id("next_game");
    public static final ResourceLocation SPECTATE = id("spectate");
    public static final ResourceLocation SPECTATE_LEAVE = id("spectate_leave");

    // ---------------- S2C ----------------

    public static final ResourceLocation ROOM_STATE = id("room_state");
    public static final ResourceLocation GAME_START = id("game_start");
    public static final ResourceLocation RECONNECT = id("reconnect");
    public static final ResourceLocation OPEN_LOBBY = id("open_lobby");
    public static final ResourceLocation PLAY_BROADCAST = id("play_broadcast");
    public static final ResourceLocation DRAW_RESULT = id("draw_result");
    public static final ResourceLocation DRAW_BROADCAST = id("draw_broadcast");
    public static final ResourceLocation DRAW_PENALTY = id("draw_penalty");
    public static final ResourceLocation PASS_BROADCAST = id("pass_broadcast");
    public static final ResourceLocation TURN = id("turn");
    public static final ResourceLocation UNO_DECLARED = id("uno_declared");
    public static final ResourceLocation UNO_CATCH = id("uno_catch");
    public static final ResourceLocation TRUST_STATE = id("trust_state");
    public static final ResourceLocation HISTORY = id("history");
    public static final ResourceLocation GAME_RESULT = id("game_result");
    public static final ResourceLocation ROOM_CLOSED = id("room_closed");
    public static final ResourceLocation NOTICE = id("notice");
    public static final ResourceLocation SPECTATOR_HANDS = id("spectator_hands");
    public static final ResourceLocation DEBUG_SPECTATOR = id("debug_spectator");

    // ---------------- Payload 定义 ----------------

    /** 打开大厅（S2C）：由服务端命令触发，客户端在主线程打开大厅 UI。 */
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


    /** 创建房间（C2S）。announce=是否公布到聊天栏；botCount=加入机器人数量（0~9）。 */
    public record CreateRoomC2S(boolean announce, byte botCount) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CreateRoomC2S> TYPE = new CustomPacketPayload.Type<>(CREATE_ROOM);
        public static final StreamCodec<FriendlyByteBuf, CreateRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.announce());
                    buf.writeByte(value.botCount());
                },
                buf -> new CreateRoomC2S(buf.readBoolean(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 加入房间（C2S），roomCode 为完整房间码（含前缀，如 UN-AB12K；也兼容裸码）。 */
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

    /** 开始游戏（C2S，仅房主座位 0 有效，至少 2 人）。 */
    public record StartGameC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StartGameC2S> TYPE = new CustomPacketPayload.Type<>(START_GAME);
        public static final StreamCodec<FriendlyByteBuf, StartGameC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new StartGameC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 出牌（C2S）。cardId 为牌 id；colorOrdinal 仅万能牌有效（0~3 选色），普通牌填 0。 */
    public record PlayCardC2S(int cardId, byte colorOrdinal) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayCardC2S> TYPE = new CustomPacketPayload.Type<>(PLAY_CARD);
        public static final StreamCodec<FriendlyByteBuf, PlayCardC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.cardId());
                    buf.writeByte(value.colorOrdinal());
                },
                buf -> new PlayCardC2S(buf.readVarInt(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抽牌（C2S）。 */
    public record DrawC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DrawC2S> TYPE = new CustomPacketPayload.Type<>(DRAW);
        public static final StreamCodec<FriendlyByteBuf, DrawC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new DrawC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 跳过（C2S，仅抽到可打的牌后可选不打）。 */
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

    /** 喊 UNO（C2S，手牌剩 1 张时须喊）。 */
    public record DeclareUnoC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DeclareUnoC2S> TYPE = new CustomPacketPayload.Type<>(DECLARE_UNO);
        public static final StreamCodec<FriendlyByteBuf, DeclareUnoC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new DeclareUnoC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抓未喊 UNO 的玩家（C2S）。 */
    public record CatchUnoC2S(byte targetSeat) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CatchUnoC2S> TYPE = new CustomPacketPayload.Type<>(CATCH_UNO);
        public static final StreamCodec<FriendlyByteBuf, CatchUnoC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeByte(value.targetSeat()),
                buf -> new CatchUnoC2S(buf.readByte()));

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

    /** 托管开关（C2S）：开启后由机器人引擎自动出牌（超时/断线也会自动开启）。 */
    public record ToggleTrustC2S(boolean enabled) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ToggleTrustC2S> TYPE = new CustomPacketPayload.Type<>(TOGGLE_TRUST);
        public static final StreamCodec<FriendlyByteBuf, ToggleTrustC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBoolean(value.enabled()),
                buf -> new ToggleTrustC2S(buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求旁观房间（C2S）：点击「点击旁观」消息触发。 */
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

    /** 房间状态同步（S2C，逐玩家发送，mySeat 为该玩家的座位，旁观者为 -1）。
     * 座位 0 即房主（最早加入者，等待中离开后自动顺延）。 */
    public record RoomStateS2C(String roomCode, byte phaseOrdinal, byte mySeat,
                               String[] names, String[] uuids, boolean[] connected) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomStateS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_STATE);
        public static final StreamCodec<FriendlyByteBuf, RoomStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.roomCode());
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeByte(value.mySeat());
                    buf.writeVarInt(value.names().length);
                    for (String s : value.names()) {
                        buf.writeUtf(s == null ? "" : s);
                    }
                    buf.writeVarInt(value.uuids().length);
                    for (String s : value.uuids()) {
                        buf.writeUtf(s == null ? "" : s);
                    }
                    buf.writeVarInt(value.connected().length);
                    for (boolean b : value.connected()) {
                        buf.writeBoolean(b);
                    }
                },
                buf -> {
                    String code = buf.readUtf();
                    byte phase = buf.readByte();
                    byte mySeat = buf.readByte();
                    int n = buf.readVarInt();
                    String[] names = new String[n];
                    for (int i = 0; i < n; i++) {
                        names[i] = buf.readUtf();
                    }
                    int u = buf.readVarInt();
                    String[] uuids = new String[u];
                    for (int i = 0; i < u; i++) {
                        uuids[i] = buf.readUtf();
                    }
                    int m = buf.readVarInt();
                    boolean[] conn = new boolean[m];
                    for (int i = 0; i < m; i++) {
                        conn[i] = buf.readBoolean();
                    }
                    return new RoomStateS2C(code, phase, mySeat, names, uuids, conn);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 发牌（S2C，逐玩家发送各自手牌）。 */
    public record GameStartS2C(byte mySeat, int[] hand, byte starterSeat, int topCardId,
                               byte topColorOrdinal, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameStartS2C> TYPE = new CustomPacketPayload.Type<>(GAME_START);
        public static final StreamCodec<FriendlyByteBuf, GameStartS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.mySeat());
                    buf.writeVarIntArray(value.hand());
                    buf.writeByte(value.starterSeat());
                    buf.writeVarInt(value.topCardId());
                    buf.writeByte(value.topColorOrdinal());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new GameStartS2C(buf.readByte(), buf.readVarIntArray(), buf.readByte(), buf.readVarInt(),
                        buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 重连快照（S2C，逐人发送）：断线玩家重连后同步当前对局完整状态。 */
    public record ReconnectS2C(byte phaseOrdinal, int[] hand, byte currentSeat, long endGameTime, byte direction,
                               int topCardId, byte topColorOrdinal, byte[] remainingCounts, boolean drawnPlayable,
                               boolean[] unoCatchable, boolean[] declaredUno,
                               byte winnerSeat, String winnerName) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ReconnectS2C> TYPE = new CustomPacketPayload.Type<>(RECONNECT);
        public static final StreamCodec<FriendlyByteBuf, ReconnectS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeVarIntArray(value.hand());
                    buf.writeByte(value.currentSeat());
                    buf.writeLong(value.endGameTime());
                    buf.writeByte(value.direction());
                    buf.writeVarInt(value.topCardId());
                    buf.writeByte(value.topColorOrdinal());
                    buf.writeByteArray(value.remainingCounts());
                    buf.writeBoolean(value.drawnPlayable());
                    writeBooleanArray(buf, value.unoCatchable());
                    writeBooleanArray(buf, value.declaredUno());
                    buf.writeByte(value.winnerSeat());
                    buf.writeUtf(value.winnerName());
                },
                buf -> new ReconnectS2C(buf.readByte(), buf.readVarIntArray(), buf.readByte(), buf.readLong(),
                        buf.readByte(), buf.readVarInt(), buf.readByte(), buf.readByteArray(), buf.readBoolean(),
                        readBooleanArray(buf), readBooleanArray(buf), buf.readByte(), buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }


    /** 出牌广播（S2C）。colorOrdinal 为万能牌所选颜色（普通牌即顶牌颜色）。 */
    public record PlayBroadcastS2C(byte seat, String playerName, int cardId, byte colorOrdinal,
                                   byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(PLAY_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, PlayBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeUtf(value.playerName());
                    buf.writeVarInt(value.cardId());
                    buf.writeByte(value.colorOrdinal());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new PlayBroadcastS2C(buf.readByte(), buf.readUtf(), buf.readVarInt(), buf.readByte(),
                        buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抽牌结果（S2C，仅发给抽牌者本人）：抽到的牌 + 能否打出（可打则可打出或跳过）。 */
    public record DrawResultS2C(int[] cardIds, boolean playable) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DrawResultS2C> TYPE = new CustomPacketPayload.Type<>(DRAW_RESULT);
        public static final StreamCodec<FriendlyByteBuf, DrawResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarIntArray(value.cardIds());
                    buf.writeBoolean(value.playable());
                },
                buf -> new DrawResultS2C(buf.readVarIntArray(), buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抽牌广播（S2C）：某玩家主动抽了一张牌（牌面保密，只见张数变化）。 */
    public record DrawBroadcastS2C(byte seat, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DrawBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(DRAW_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, DrawBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new DrawBroadcastS2C(buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 罚牌广播（S2C）：+2/+4 功能牌生效，目标玩家被罚抽 count 张并跳过。 */
    public record DrawPenaltyS2C(byte seat, byte count, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DrawPenaltyS2C> TYPE = new CustomPacketPayload.Type<>(DRAW_PENALTY);
        public static final StreamCodec<FriendlyByteBuf, DrawPenaltyS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeByte(value.count());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new DrawPenaltyS2C(buf.readByte(), buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 跳过广播（S2C）：主动跳过（抽到可打的牌后选择不打）或抽到不可打的牌自动跳过。 */
    public record PassBroadcastS2C(byte seat, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PassBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(PASS_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, PassBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new PassBroadcastS2C(buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 轮到谁（S2C）。endGameTime 为行动截止的游戏刻（level.getGameTime() + 30*20），
     * 客户端用本地的 level.getGameTime() 计算剩余时间，两端时间基准一致。
     */
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

    /** UNO 喊牌广播（S2C）：某玩家手牌剩 1 张并已喊 UNO。 */
    public record UnoDeclaredS2C(byte seat) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UnoDeclaredS2C> TYPE = new CustomPacketPayload.Type<>(UNO_DECLARED);
        public static final StreamCodec<FriendlyByteBuf, UnoDeclaredS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeByte(value.seat()),
                buf -> new UnoDeclaredS2C(buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抓 UNO 广播（S2C）：目标玩家未喊 UNO 被抓住，罚 2 张（牌面已私发）。 */
    public record UnoCatchS2C(byte catcherSeat, byte targetSeat, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UnoCatchS2C> TYPE = new CustomPacketPayload.Type<>(UNO_CATCH);
        public static final StreamCodec<FriendlyByteBuf, UnoCatchS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.catcherSeat());
                    buf.writeByte(value.targetSeat());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new UnoCatchS2C(buf.readByte(), buf.readByte(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 请求事件历史（C2S）：客户端打开历史界面时请求，服务端回 HistoryS2C。 */
    public record HistoryC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HistoryC2S> TYPE = new CustomPacketPayload.Type<>(HISTORY_REQUEST);
        public static final StreamCodec<FriendlyByteBuf, HistoryC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new HistoryC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 事件历史（S2C）：并行数组（最新在前），names 与 texts 等长。
     *  events 如："打出 红9" / "抽牌" / "跳过" / "被罚抽 2 张" / "喊了 UNO!" / "抓住 XX 没喊 UNO"。 */
    public record HistoryS2C(String[] names, String[] texts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HistoryS2C> TYPE = new CustomPacketPayload.Type<>(HISTORY);
        public static final StreamCodec<FriendlyByteBuf, HistoryS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeCollection(java.util.Arrays.asList(value.names()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(java.util.Arrays.asList(value.texts()), (b, s) -> b.writeUtf(s));
                },
                buf -> new HistoryS2C(
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0])));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 托管状态（S2C）：托管开启/关闭时回传，客户端按钮与服务端保持一致。 */
    public record TrustStateS2C(boolean enabled) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TrustStateS2C> TYPE = new CustomPacketPayload.Type<>(TRUST_STATE);
        public static final StreamCodec<FriendlyByteBuf, TrustStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBoolean(value.enabled()),
                buf -> new TrustStateS2C(buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 结算结果（S2C）：先出完手牌者获胜（单局制）。 */
    public record GameResultS2C(byte winnerSeat, String winnerName) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameResultS2C> TYPE = new CustomPacketPayload.Type<>(GAME_RESULT);
        public static final StreamCodec<FriendlyByteBuf, GameResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.winnerSeat());
                    buf.writeUtf(value.winnerName());
                },
                buf -> new GameResultS2C(buf.readByte(), buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 房间关闭（S2C），reason 为空表示玩家主动离开，不提示。 */
    /** 房间关闭（S2C）：原因组件（空组件=正常关闭不提示），客户端显示在聊天栏并清理本地状态。 */
    public record RoomClosedS2C(Component reason) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomClosedS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_CLOSED);
        public static final StreamCodec<FriendlyByteBuf, RoomClosedS2C> CODEC = StreamCodec.of(
                (buf, value) -> ComponentSerialization.STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, value.reason()),
                buf -> new RoomClosedS2C(ComponentSerialization.STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 通知（S2C）：错误提示或系统消息，客户端显示在聊天栏。 */
    /** 通知（S2C）：错误提示或系统消息组件（翻译键 + 参数），客户端显示在聊天栏。 */
    public record NoticeS2C(Component message) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<NoticeS2C> TYPE = new CustomPacketPayload.Type<>(NOTICE);
        public static final StreamCodec<FriendlyByteBuf, NoticeS2C> CODEC = StreamCodec.of(
                (buf, value) -> ComponentSerialization.STREAM_CODEC.encode((RegistryFriendlyByteBuf) buf, value.message()),
                buf -> new NoticeS2C(ComponentSerialization.STREAM_CODEC.decode((RegistryFriendlyByteBuf) buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 各家完整手牌（S2C，仅发给旁观者）：旁观者透视视角，随时同步各家手牌。 */
    public record SpectatorHandsS2C(int[][] hands) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SpectatorHandsS2C> TYPE = new CustomPacketPayload.Type<>(SPECTATOR_HANDS);
        public static final StreamCodec<FriendlyByteBuf, SpectatorHandsS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.hands().length);
                    for (int[] hand : value.hands()) {
                        buf.writeVarIntArray(hand);
                    }
                },
                buf -> {
                    int n = buf.readVarInt();
                    int[][] hands = new int[n][];
                    for (int i = 0; i < n; i++) {
                        hands[i] = buf.readVarIntArray();
                    }
                    return new SpectatorHandsS2C(hands);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 旁观 UI 调试快照（S2C，仅管理员调试命令触发）：无房间的虚拟旁观数据，标题显示"（调试）"。 */
    public record DebugSpectatorS2C(String[] names, int[][] hands, byte currentSeat, byte direction,
                                    int topCardId, byte topColorOrdinal, boolean[] unoCatchable,
                                    boolean[] declaredUno) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DebugSpectatorS2C> TYPE = new CustomPacketPayload.Type<>(DEBUG_SPECTATOR);
        public static final StreamCodec<FriendlyByteBuf, DebugSpectatorS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarInt(value.names().length);
                    for (String s : value.names()) {
                        buf.writeUtf(s);
                    }
                    buf.writeVarInt(value.hands().length);
                    for (int[] hand : value.hands()) {
                        buf.writeVarIntArray(hand);
                    }
                    buf.writeByte(value.currentSeat());
                    buf.writeByte(value.direction());
                    buf.writeVarInt(value.topCardId());
                    buf.writeByte(value.topColorOrdinal());
                    writeBooleanArray(buf, value.unoCatchable());
                    writeBooleanArray(buf, value.declaredUno());
                },
                buf -> {
                    int n = buf.readVarInt();
                    String[] names = new String[n];
                    for (int i = 0; i < n; i++) {
                        names[i] = buf.readUtf();
                    }
                    int h = buf.readVarInt();
                    int[][] hands = new int[h][];
                    for (int i = 0; i < h; i++) {
                        hands[i] = buf.readVarIntArray();
                    }
                    return new DebugSpectatorS2C(names, hands, buf.readByte(), buf.readByte(), buf.readVarInt(),
                            buf.readByte(), readBooleanArray(buf), readBooleanArray(buf));
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- 注册 ----------------


    /** 大厅房间列表（S2C）：公开房间的平行数组（codes/lines/statuses 长度一致）。
     *  status：0=等待中可加入 1=对局中可旁观 2=已结束。 */

    /** boolean[] 序列化（FriendlyByteBuf 无现成方法，定长 varint + 逐位写入）。 */
    private static void writeBooleanArray(FriendlyByteBuf buf, boolean[] values) {
        buf.writeVarInt(values.length);
        for (boolean b : values) {
            buf.writeBoolean(b);
        }
    }

    private static boolean[] readBooleanArray(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        boolean[] values = new boolean[n];
        for (int i = 0; i < n; i++) {
            values[i] = buf.readBoolean();
        }
        return values;
    }

    /** 注册全部 payload 类型 + 服务端接收器（主入口调用，客户端也会执行此方法）。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(CreateRoomC2S.TYPE, CreateRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinRoomC2S.TYPE, JoinRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveRoomC2S.TYPE, LeaveRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(StartGameC2S.TYPE, StartGameC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayCardC2S.TYPE, PlayCardC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(DrawC2S.TYPE, DrawC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PassC2S.TYPE, PassC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(DeclareUnoC2S.TYPE, DeclareUnoC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CatchUnoC2S.TYPE, CatchUnoC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ToggleTrustC2S.TYPE, ToggleTrustC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(HistoryC2S.TYPE, HistoryC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(NextGameC2S.TYPE, NextGameC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateC2S.TYPE, SpectateC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateLeaveC2S.TYPE, SpectateLeaveC2S.CODEC);

        PayloadTypeRegistry.playS2C().register(OpenLobbyS2C.TYPE, OpenLobbyS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomStateS2C.TYPE, RoomStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameStartS2C.TYPE, GameStartS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ReconnectS2C.TYPE, ReconnectS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayBroadcastS2C.TYPE, PlayBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(DrawResultS2C.TYPE, DrawResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(DrawBroadcastS2C.TYPE, DrawBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(DrawPenaltyS2C.TYPE, DrawPenaltyS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PassBroadcastS2C.TYPE, PassBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TurnS2C.TYPE, TurnS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(UnoDeclaredS2C.TYPE, UnoDeclaredS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(UnoCatchS2C.TYPE, UnoCatchS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TrustStateS2C.TYPE, TrustStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(HistoryS2C.TYPE, HistoryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameResultS2C.TYPE, GameResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomClosedS2C.TYPE, RoomClosedS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(NoticeS2C.TYPE, NoticeS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(SpectatorHandsS2C.TYPE, SpectatorHandsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(DebugSpectatorS2C.TYPE, DebugSpectatorS2C.CODEC);

        registerServerReceivers();
    }

    // 客户端接收器在 io.wifi.cards.uno.UnoClient（客户端类）中注册，
    // 本类不引用任何含 client 的包，保证服务端可正常加载。

    private static void registerServerReceivers() {
        UnoMemoryManager m = UnoMemoryManager.INSTANCE;
        // 所有 C2S 处理统一调度到服务器主线程执行：Fabric 接收器运行在 netty 线程，
        // 直接修改房间/对局共享状态会与主线程 tick 产生竞态（members/spectators 非线程安全）。
        // ctx.player() 引用在 execute 后依然有效（断线由 isConnected 兜底）。
        // 处理体统一 guarded 包装：主线程任务抛异常会崩溃整个服务器，任何意外只记录日志。
        ServerPlayNetworking.registerGlobalReceiver(CreateRoomC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> guarded(() -> {
            // 防御：机器人数量钳制 0~9
            m.createRoom(ctx.server(), ctx.player(), payload.announce(),
                    Math.max(0, Math.min(payload.botCount(), 9)));
        })));
        ServerPlayNetworking.registerGlobalReceiver(JoinRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.joinRoom(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(LeaveRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveRoom(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(StartGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.startGame(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(PlayCardC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onPlayCard(ctx.player(), payload.cardId(), payload.colorOrdinal()))));
        ServerPlayNetworking.registerGlobalReceiver(DrawC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onDraw(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(PassC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onPass(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(DeclareUnoC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onDeclareUno(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(CatchUnoC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onCatchUno(ctx.player(), payload.targetSeat()))));
        ServerPlayNetworking.registerGlobalReceiver(ToggleTrustC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.setTrust(ctx.player(), payload.enabled()))));
        ServerPlayNetworking.registerGlobalReceiver(HistoryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onHistoryRequest(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(NextGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.nextGame(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.spectate(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateLeaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveSpectate(ctx.player()))));
    }

    /** 主线程任务防护：意外异常只记录日志，绝不让服务器崩溃。 */
    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("处理 UNO 网络包异常", t);
        }
    }

    // ---------------- 序列化辅助 ----------------

}
