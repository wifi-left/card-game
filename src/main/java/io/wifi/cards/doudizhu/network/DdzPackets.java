package io.wifi.cards.doudizhu.network;

import io.wifi.cards.doudizhu.gui.DdzClientState;
import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 斗地主全部网络包（1.21.1 新版 CustomPayload API）。
 * <p>C2S：创建/加入/离开房间、叫分、抢地主、出牌、不出、托管、再来一局。</p>
 * <p>S2C：房间状态、发牌、叫分/抢地主广播、地主确定、出牌/不出广播、轮到谁、结算、房间关闭、通知。</p>
 * <p>序列化统一使用 StreamCodec + FriendlyByteBuf；牌只传 id（见 {@code DdzCard}）。</p>
 */
public final class DdzPackets {
    private DdzPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("wifi-card-games", "ddz_" + path);
    }

    // ---------------- C2S ----------------

    public static final ResourceLocation CREATE_ROOM = id("create_room");
    public static final ResourceLocation JOIN_ROOM = id("join_room");
    public static final ResourceLocation LEAVE_ROOM = id("leave_room");
    public static final ResourceLocation CALL_SCORE = id("call_score");
    public static final ResourceLocation ROB_ACTION = id("rob_action");
    public static final ResourceLocation PLAY_CARDS = id("play_cards");
    public static final ResourceLocation PASS = id("pass");
    public static final ResourceLocation TOGGLE_TRUST = id("toggle_trust");
    public static final ResourceLocation NEXT_GAME = id("next_game");

    // ---------------- S2C ----------------

    public static final ResourceLocation ROOM_STATE = id("room_state");
    public static final ResourceLocation GAME_START = id("game_start");
    public static final ResourceLocation CALL_BROADCAST = id("call_broadcast");
    public static final ResourceLocation ROB_BROADCAST = id("rob_broadcast");
    public static final ResourceLocation LANDLORD = id("landlord");
    public static final ResourceLocation PLAY_BROADCAST = id("play_broadcast");
    public static final ResourceLocation PASS_BROADCAST = id("pass_broadcast");
    public static final ResourceLocation TURN = id("turn");
    public static final ResourceLocation GAME_RESULT = id("game_result");
    public static final ResourceLocation ROOM_CLOSED = id("room_closed");
    public static final ResourceLocation NOTICE = id("notice");

    // ---------------- Payload 定义 ----------------

    /** 创建房间（C2S）。flowerMode=花牌模式；ruleSet=规则集序号（0 标准 / 1 民间）。 */
    public record CreateRoomC2S(boolean flowerMode, byte ruleSet) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CreateRoomC2S> TYPE = new CustomPacketPayload.Type<>(CREATE_ROOM);
        public static final StreamCodec<FriendlyByteBuf, CreateRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.flowerMode());
                    buf.writeByte(value.ruleSet());
                },
                buf -> new CreateRoomC2S(buf.readBoolean(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 加入房间（C2S），roomCode 为 5 位房间码。 */
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

    /** 叫分（C2S）：0=不叫，1/2/3。 */
    public record CallScoreC2S(byte score) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CallScoreC2S> TYPE = new CustomPacketPayload.Type<>(CALL_SCORE);
        public static final StreamCodec<FriendlyByteBuf, CallScoreC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeByte(value.score()),
                buf -> new CallScoreC2S(buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抢地主表态（C2S）。 */
    public record RobActionC2S(boolean rob) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RobActionC2S> TYPE = new CustomPacketPayload.Type<>(ROB_ACTION);
        public static final StreamCodec<FriendlyByteBuf, RobActionC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeBoolean(value.rob()),
                buf -> new RobActionC2S(buf.readBoolean()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 出牌（C2S），cardIds 为选中牌的 id 列表。 */
    public record PlayCardsC2S(int[] cardIds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayCardsC2S> TYPE = new CustomPacketPayload.Type<>(PLAY_CARDS);
        public static final StreamCodec<FriendlyByteBuf, PlayCardsC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeVarIntArray(value.cardIds()),
                buf -> new PlayCardsC2S(buf.readVarIntArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 不出（C2S）。 */
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

    /** 托管开关（C2S）。 */
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

    /** 房间状态同步（S2C，逐玩家发送，mySeat 为该玩家的座位）。 */
    public record RoomStateS2C(String roomCode, boolean flowerMode, byte phaseOrdinal, byte ruleSet, byte mySeat,
                               String[] names, boolean[] connected) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomStateS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_STATE);
        public static final StreamCodec<FriendlyByteBuf, RoomStateS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.roomCode());
                    buf.writeBoolean(value.flowerMode());
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeByte(value.ruleSet());
                    buf.writeByte(value.mySeat());
                    buf.writeVarInt(value.names().length);
                    for (String s : value.names()) {
                        buf.writeUtf(s == null ? "" : s);
                    }
                    buf.writeVarInt(value.connected().length);
                    for (boolean b : value.connected()) {
                        buf.writeBoolean(b);
                    }
                },
                buf -> {
                    String code = buf.readUtf();
                    boolean flower = buf.readBoolean();
                    byte phase = buf.readByte();
                    byte ruleSet = buf.readByte();
                    byte mySeat = buf.readByte();
                    int n = buf.readVarInt();
                    String[] names = new String[n];
                    for (int i = 0; i < n; i++) {
                        names[i] = buf.readUtf();
                    }
                    int m = buf.readVarInt();
                    boolean[] conn = new boolean[m];
                    for (int i = 0; i < m; i++) {
                        conn[i] = buf.readBoolean();
                    }
                    return new RoomStateS2C(code, flower, phase, ruleSet, mySeat, names, conn);
                });

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 发牌（S2C，逐玩家发送各自手牌）。 */
    public record GameStartS2C(byte mySeat, int[] hand, byte starterSeat, byte bottomCount) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameStartS2C> TYPE = new CustomPacketPayload.Type<>(GAME_START);
        public static final StreamCodec<FriendlyByteBuf, GameStartS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.mySeat());
                    buf.writeVarIntArray(value.hand());
                    buf.writeByte(value.starterSeat());
                    buf.writeByte(value.bottomCount());
                },
                buf -> new GameStartS2C(buf.readByte(), buf.readVarIntArray(), buf.readByte(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 叫分广播（S2C）。score=3 时客户端应切到抢地主阶段。 */
    public record CallBroadcastS2C(String playerName, byte score, byte maxScore) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CallBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(CALL_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, CallBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.playerName());
                    buf.writeByte(value.score());
                    buf.writeByte(value.maxScore());
                },
                buf -> new CallBroadcastS2C(buf.readUtf(), buf.readByte(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 抢地主广播（S2C）。rob=true 且为首位时表示"叫三分成为地主候选"。 */
    public record RobBroadcastS2C(String playerName, boolean rob, int multiplier,
                                  byte consecutivePasses) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RobBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(ROB_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, RobBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.playerName());
                    buf.writeBoolean(value.rob());
                    buf.writeVarInt(value.multiplier());
                    buf.writeByte(value.consecutivePasses());
                },
                buf -> new RobBroadcastS2C(buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 地主确定（S2C）：底牌亮出，进入出牌阶段。 */
    public record LandlordS2C(byte landlordSeat, String landlordName, int[] bottomCards, byte baseScore,
                              int multiplier) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<LandlordS2C> TYPE = new CustomPacketPayload.Type<>(LANDLORD);
        public static final StreamCodec<FriendlyByteBuf, LandlordS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.landlordSeat());
                    buf.writeUtf(value.landlordName());
                    buf.writeVarIntArray(value.bottomCards());
                    buf.writeByte(value.baseScore());
                    buf.writeVarInt(value.multiplier());
                },
                buf -> new LandlordS2C(buf.readByte(), buf.readUtf(), buf.readVarIntArray(), buf.readByte(), buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 出牌广播（S2C）。typeOrdinal/keyValue 为服务端选定的解读，供客户端展示与提示。 */
    public record PlayBroadcastS2C(byte seat, String playerName, int[] cardIds, byte typeOrdinal, int keyValue,
                                   int multiplier, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlayBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(PLAY_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, PlayBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeUtf(value.playerName());
                    buf.writeVarIntArray(value.cardIds());
                    buf.writeByte(value.typeOrdinal());
                    buf.writeVarInt(value.keyValue());
                    buf.writeVarInt(value.multiplier());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new PlayBroadcastS2C(buf.readByte(), buf.readUtf(), buf.readVarIntArray(), buf.readByte(),
                        buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 不出广播（S2C）。 */
    public record PassBroadcastS2C(String playerName, byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PassBroadcastS2C> TYPE = new CustomPacketPayload.Type<>(PASS_BROADCAST);
        public static final StreamCodec<FriendlyByteBuf, PassBroadcastS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.playerName());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new PassBroadcastS2C(buf.readUtf(), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 轮到谁（S2C），seconds 为出牌倒计时。 */
    public record TurnS2C(byte seat, byte seconds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TurnS2C> TYPE = new CustomPacketPayload.Type<>(TURN);
        public static final StreamCodec<FriendlyByteBuf, TurnS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.seat());
                    buf.writeByte(value.seconds());
                },
                buf -> new TurnS2C(buf.readByte(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 结算结果（S2C）。scoreDeltas 按座位排列。 */
    public record GameResultS2C(byte landlordSeat, String landlordName, boolean landlordWin, byte baseScore,
                                int multiplier, int[] scoreDeltas) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<GameResultS2C> TYPE = new CustomPacketPayload.Type<>(GAME_RESULT);
        public static final StreamCodec<FriendlyByteBuf, GameResultS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.landlordSeat());
                    buf.writeUtf(value.landlordName());
                    buf.writeBoolean(value.landlordWin());
                    buf.writeByte(value.baseScore());
                    buf.writeVarInt(value.multiplier());
                    buf.writeVarIntArray(value.scoreDeltas());
                },
                buf -> new GameResultS2C(buf.readByte(), buf.readUtf(), buf.readBoolean(), buf.readByte(),
                        buf.readVarInt(), buf.readVarIntArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 房间关闭（S2C），reason 为空表示玩家主动离开，不提示。 */
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

    /** 通知（S2C）：错误提示或系统消息，客户端显示在聊天栏。 */
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

    // ---------------- 注册 ----------------

    /** 注册全部 payload 类型 + 服务端接收器（主入口调用，客户端也会执行此方法）。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(CreateRoomC2S.TYPE, CreateRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinRoomC2S.TYPE, JoinRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LeaveRoomC2S.TYPE, LeaveRoomC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(CallScoreC2S.TYPE, CallScoreC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RobActionC2S.TYPE, RobActionC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayCardsC2S.TYPE, PlayCardsC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PassC2S.TYPE, PassC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ToggleTrustC2S.TYPE, ToggleTrustC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(NextGameC2S.TYPE, NextGameC2S.CODEC);

        PayloadTypeRegistry.playS2C().register(RoomStateS2C.TYPE, RoomStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameStartS2C.TYPE, GameStartS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CallBroadcastS2C.TYPE, CallBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RobBroadcastS2C.TYPE, RobBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(LandlordS2C.TYPE, LandlordS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayBroadcastS2C.TYPE, PlayBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PassBroadcastS2C.TYPE, PassBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TurnS2C.TYPE, TurnS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameResultS2C.TYPE, GameResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomClosedS2C.TYPE, RoomClosedS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(NoticeS2C.TYPE, NoticeS2C.CODEC);

        registerServerReceivers();
    }

    /** 注册客户端接收器（客户端入口调用）。 */
    public static void registerClient() {
        DdzClientState state = DdzClientState.INSTANCE;
        ClientPlayNetworking.registerGlobalReceiver(RoomStateS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onRoomState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(GameStartS2C.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> state.onGameStart(payload)));
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
    }

    private static void registerServerReceivers() {
        DdzMemoryManager m = DdzMemoryManager.INSTANCE;
        ServerPlayNetworking.registerGlobalReceiver(CreateRoomC2S.TYPE, (payload, ctx) ->
                m.createRoom(ctx.player(), payload.flowerMode(), DdzRuleSet.values()[payload.ruleSet()]));
        ServerPlayNetworking.registerGlobalReceiver(JoinRoomC2S.TYPE, (payload, ctx) ->
                m.joinRoom(ctx.player(), payload.roomCode()));
        ServerPlayNetworking.registerGlobalReceiver(LeaveRoomC2S.TYPE, (payload, ctx) ->
                m.leaveRoom(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(CallScoreC2S.TYPE, (payload, ctx) ->
                m.onCall(ctx.player(), payload.score()));
        ServerPlayNetworking.registerGlobalReceiver(RobActionC2S.TYPE, (payload, ctx) ->
                m.onRob(ctx.player(), payload.rob()));
        ServerPlayNetworking.registerGlobalReceiver(PlayCardsC2S.TYPE, (payload, ctx) ->
                m.onPlayCards(ctx.player(), payload.cardIds()));
        ServerPlayNetworking.registerGlobalReceiver(PassC2S.TYPE, (payload, ctx) ->
                m.onPass(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(ToggleTrustC2S.TYPE, (payload, ctx) ->
                m.setTrust(ctx.player(), payload.enabled()));
        ServerPlayNetworking.registerGlobalReceiver(NextGameC2S.TYPE, (payload, ctx) ->
                m.nextGame(ctx.player()));
    }
}
