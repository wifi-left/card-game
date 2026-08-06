package io.wifi.cards.doudizhu.network;

import io.wifi.cards.doudizhu.manager.DdzMemoryManager;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 斗地主全部网络包（1.21.1 新版 CustomPayload API）。
 * <p>C2S：创建/加入/离开房间、叫分、抢地主、出牌、不出、托管、再来一局。</p>
 * <p>S2C：房间状态、发牌、叫分/抢地主广播、地主确定、出牌/不出广播、轮到谁、结算、房间关闭、通知。</p>
 * <p>序列化统一使用 StreamCodec + FriendlyByteBuf；牌只传 id（见 {@code DdzCard}）。</p>
 * <p><b>服务端安全：</b>本类不引用任何含 client 的包（客户端接收器在 DdzClient 中注册）。</p>
 */
public final class DdzPackets {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

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
    public static final ResourceLocation REVEAL_ACTION = id("reveal_action");
    public static final ResourceLocation HISTORY_REQUEST = id("history_request");
    public static final ResourceLocation SPECTATE = id("spectate");
    public static final ResourceLocation SPECTATE_LEAVE = id("spectate_leave");
    public static final ResourceLocation LOBBY_QUERY = id("lobby_query");

    // ---------------- S2C ----------------

    public static final ResourceLocation ROOM_STATE = id("room_state");
    public static final ResourceLocation GAME_START = id("game_start");
    public static final ResourceLocation RECONNECT = id("reconnect");
    public static final ResourceLocation OPEN_LOBBY = id("open_lobby");
    public static final ResourceLocation CALL_BROADCAST = id("call_broadcast");
    public static final ResourceLocation ROB_BROADCAST = id("rob_broadcast");
    public static final ResourceLocation LANDLORD = id("landlord");
    public static final ResourceLocation PLAY_BROADCAST = id("play_broadcast");
    public static final ResourceLocation PASS_BROADCAST = id("pass_broadcast");
    public static final ResourceLocation TURN = id("turn");
    public static final ResourceLocation GAME_RESULT = id("game_result");
    public static final ResourceLocation ROOM_CLOSED = id("room_closed");
    public static final ResourceLocation NOTICE = id("notice");
    public static final ResourceLocation REVEAL = id("reveal");
    public static final ResourceLocation TRUST_STATE = id("trust_state");
    public static final ResourceLocation HISTORY = id("history");
    public static final ResourceLocation SPECTATOR_HANDS = id("spectator_hands");
    public static final ResourceLocation ROOM_LIST = id("room_list");

    // ---------------- Payload 定义 ----------------

    /** 创建房间（C2S）。flowerMode=花牌模式；ruleSet=规则集序号（0 标准 / 1 民间）；announce=是否公布到聊天栏；botCount=加入机器人数量（0~2）。 */
    public record CreateRoomC2S(boolean flowerMode, byte ruleSet, boolean announce, byte botCount) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CreateRoomC2S> TYPE = new CustomPacketPayload.Type<>(CREATE_ROOM);
        public static final StreamCodec<FriendlyByteBuf, CreateRoomC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeBoolean(value.flowerMode());
                    buf.writeByte(value.ruleSet());
                    buf.writeBoolean(value.announce());
                    buf.writeByte(value.botCount());
                },
                buf -> new CreateRoomC2S(buf.readBoolean(), buf.readByte(), buf.readBoolean(), buf.readByte()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 加入房间（C2S），roomCode 为完整房间码（含前缀，如 DZ-AB12K；也兼容裸码）。 */
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

    /** 地主选择明牌（C2S，出第一手牌前有效）。 */
    public record RevealC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RevealC2S> TYPE = new CustomPacketPayload.Type<>(REVEAL_ACTION);
        public static final StreamCodec<FriendlyByteBuf, RevealC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new RevealC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 房间状态同步（S2C，逐玩家发送，mySeat 为该玩家的座位）。uuids 用于客户端渲染玩家头颅。 */
    public record RoomStateS2C(String roomCode, boolean flowerMode, byte phaseOrdinal, byte ruleSet, byte mySeat,
                               String[] names, String[] uuids, boolean[] connected) implements CustomPacketPayload {
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
                    boolean flower = buf.readBoolean();
                    byte phase = buf.readByte();
                    byte ruleSet = buf.readByte();
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
                    return new RoomStateS2C(code, flower, phase, ruleSet, mySeat, names, uuids, conn);
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

    /** 打开大厅（S2C）：由服务端命令触发，客户端在主线程打开 LobbyScreen。 */
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

    /** 重连快照（S2C，逐人发送）：断线玩家重连后同步当前对局完整状态。 */
    public record ReconnectS2C(byte phaseOrdinal, int[] hand, byte callMaxScore, byte currentSeat, long endGameTime,
                               int multiplier, byte consecutivePasses, byte baseScore, byte landlordSeat,
                               String landlordName, int[] bottomCards, byte lastPlaySeat, String lastPlayName,
                               int[] lastPlayCards, byte lastPlayType, int lastPlayKey,
                               byte[] remainingCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ReconnectS2C> TYPE = new CustomPacketPayload.Type<>(RECONNECT);
        public static final StreamCodec<FriendlyByteBuf, ReconnectS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.phaseOrdinal());
                    buf.writeVarIntArray(value.hand());
                    buf.writeByte(value.callMaxScore());
                    buf.writeByte(value.currentSeat());
                    buf.writeLong(value.endGameTime());
                    buf.writeVarInt(value.multiplier());
                    buf.writeByte(value.consecutivePasses());
                    buf.writeByte(value.baseScore());
                    buf.writeByte(value.landlordSeat());
                    buf.writeUtf(value.landlordName());
                    buf.writeVarIntArray(value.bottomCards());
                    buf.writeByte(value.lastPlaySeat());
                    buf.writeUtf(value.lastPlayName());
                    buf.writeVarIntArray(value.lastPlayCards());
                    buf.writeByte(value.lastPlayType());
                    buf.writeVarInt(value.lastPlayKey());
                    buf.writeByteArray(value.remainingCounts());
                },
                buf -> new ReconnectS2C(buf.readByte(), buf.readVarIntArray(), buf.readByte(), buf.readByte(),
                        buf.readLong(), buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readByte(),
                        buf.readUtf(), buf.readVarIntArray(), buf.readByte(), buf.readUtf(), buf.readVarIntArray(),
                        buf.readByte(), buf.readVarInt(), buf.readByteArray()));

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

    /**
     * 轮到谁（S2C）。endGameTime 为行动截止的游戏刻（level.getGameTime() + 15*20），
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

    /** 明牌广播（S2C）：地主公开全部手牌，所有玩家可见。 */
    public record RevealS2C(byte landlordSeat, int[] handIds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RevealS2C> TYPE = new CustomPacketPayload.Type<>(REVEAL);
        public static final StreamCodec<FriendlyByteBuf, RevealS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeByte(value.landlordSeat());
                    buf.writeVarIntArray(value.handIds());
                },
                buf -> new RevealS2C(buf.readByte(), buf.readVarIntArray()));

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

    /** 请求出牌历史（C2S）：客户端打开历史界面时请求，服务端回 HistoryS2C。 */
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

    /** 出牌历史（S2C）：并行数组（最新在前）。types/cards 与 names 等长；"不出"行 typeName="不出"、cardsText 为空。 */
    public record HistoryS2C(int[] seats, String[] names, String[] types, String[] cards) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HistoryS2C> TYPE = new CustomPacketPayload.Type<>(HISTORY);
        public static final StreamCodec<FriendlyByteBuf, HistoryS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarIntArray(value.seats());
                    buf.writeCollection(java.util.Arrays.asList(value.names()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(java.util.Arrays.asList(value.types()), (b, s) -> b.writeUtf(s));
                    buf.writeCollection(java.util.Arrays.asList(value.cards()), (b, s) -> b.writeUtf(s));
                },
                buf -> new HistoryS2C(
                        buf.readVarIntArray(),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0]),
                        buf.readCollection(java.util.ArrayList::new, b -> b.readUtf()).toArray(new String[0])));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 三家完整手牌（S2C，仅发给旁观者）：旁观者透视视角，随时同步三家手牌。 */
    public record SpectatorHandsS2C(int[] hand0, int[] hand1, int[] hand2) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SpectatorHandsS2C> TYPE = new CustomPacketPayload.Type<>(SPECTATOR_HANDS);
        public static final StreamCodec<FriendlyByteBuf, SpectatorHandsS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeVarIntArray(value.hand0());
                    buf.writeVarIntArray(value.hand1());
                    buf.writeVarIntArray(value.hand2());
                },
                buf -> new SpectatorHandsS2C(buf.readVarIntArray(), buf.readVarIntArray(), buf.readVarIntArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- 注册 ----------------

    /** 大厅房间列表请求（C2S）：打开大厅时轮询（每 20 tick），服务端回发公开房间列表。 */
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

    /** 大厅房间列表（S2C）：公开房间的平行数组（codes/lines/statuses 长度一致）。
     *  status：0=等待中可加入 1=对局中可旁观 2=已结束。 */
    public record RoomListS2C(String[] codes, String[] lines, byte[] statuses) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RoomListS2C> TYPE = new CustomPacketPayload.Type<>(ROOM_LIST);
        public static final StreamCodec<FriendlyByteBuf, RoomListS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    writeStrings(buf, value.codes());
                    writeStrings(buf, value.lines());
                    buf.writeByteArray(value.statuses());
                },
                buf -> new RoomListS2C(readStrings(buf), readStrings(buf), buf.readByteArray()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

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
        PayloadTypeRegistry.playC2S().register(RevealC2S.TYPE, RevealC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(HistoryC2S.TYPE, HistoryC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateC2S.TYPE, SpectateC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SpectateLeaveC2S.TYPE, SpectateLeaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(LobbyQueryC2S.TYPE, LobbyQueryC2S.CODEC);

        PayloadTypeRegistry.playS2C().register(RoomStateS2C.TYPE, RoomStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameStartS2C.TYPE, GameStartS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ReconnectS2C.TYPE, ReconnectS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenLobbyS2C.TYPE, OpenLobbyS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CallBroadcastS2C.TYPE, CallBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RobBroadcastS2C.TYPE, RobBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(LandlordS2C.TYPE, LandlordS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayBroadcastS2C.TYPE, PlayBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(PassBroadcastS2C.TYPE, PassBroadcastS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TurnS2C.TYPE, TurnS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(GameResultS2C.TYPE, GameResultS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomClosedS2C.TYPE, RoomClosedS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(NoticeS2C.TYPE, NoticeS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RevealS2C.TYPE, RevealS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(TrustStateS2C.TYPE, TrustStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(HistoryS2C.TYPE, HistoryS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(SpectatorHandsS2C.TYPE, SpectatorHandsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RoomListS2C.TYPE, RoomListS2C.CODEC);

        registerServerReceivers();
    }

    // 客户端接收器在 io.wifi.cards.doudizhu.DdzClient（客户端类）中注册，
    // 本类不引用任何含 client 的包，保证服务端可正常加载。

    private static void registerServerReceivers() {
        DdzMemoryManager m = DdzMemoryManager.INSTANCE;
        // 所有 C2S 处理统一调度到服务器主线程执行：Fabric 接收器运行在 netty 线程，
        // 直接修改房间/对局共享状态会与主线程 tick 产生竞态（members/size/spectators 非线程安全）。
        // ctx.player() 引用在 execute 后依然有效（断线由 isConnected 兜底）。
        // 处理体统一 guarded 包装：主线程任务抛异常会崩溃整个服务器，任何意外只记录日志。
        ServerPlayNetworking.registerGlobalReceiver(CreateRoomC2S.TYPE, (payload, ctx) -> ctx.server().execute(() -> guarded(() -> {
            // 防御：规则集序号越界（恶意客户端可发送任意 byte）时回退标准规则；机器人数量钳制 0~2
            byte rs = payload.ruleSet();
            DdzRuleSet ruleSet = rs >= 0 && rs < DdzRuleSet.values().length
                    ? DdzRuleSet.values()[rs] : DdzRuleSet.STANDARD;
            m.createRoom(ctx.server(), ctx.player(), payload.flowerMode(), ruleSet, payload.announce(),
                    Math.max(0, Math.min(payload.botCount(), 2)));
        })));
        ServerPlayNetworking.registerGlobalReceiver(JoinRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.joinRoom(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(LeaveRoomC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveRoom(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(CallScoreC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onCall(ctx.player(), payload.score()))));
        ServerPlayNetworking.registerGlobalReceiver(RobActionC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onRob(ctx.player(), payload.rob()))));
        ServerPlayNetworking.registerGlobalReceiver(PlayCardsC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onPlayCards(ctx.player(), payload.cardIds()))));
        ServerPlayNetworking.registerGlobalReceiver(PassC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onPass(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(ToggleTrustC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.setTrust(ctx.player(), payload.enabled()))));
        ServerPlayNetworking.registerGlobalReceiver(NextGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.nextGame(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(RevealC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onReveal(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(HistoryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.onHistoryRequest(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.spectate(ctx.player(), payload.roomCode()))));
        ServerPlayNetworking.registerGlobalReceiver(SpectateLeaveC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.leaveSpectate(ctx.player()))));
        ServerPlayNetworking.registerGlobalReceiver(LobbyQueryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> m.sendRoomList(ctx.player()))));
    }

    /** 主线程任务防护：意外异常只记录日志，绝不让服务器崩溃。 */
    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("处理斗地主网络包异常", t);
        }
    }

    // ---------------- 序列化辅助 ----------------

    private static void writeStrings(FriendlyByteBuf buf, String[] arr) {
        buf.writeVarInt(arr.length);
        for (String s : arr) {
            buf.writeUtf(s);
        }
    }

    private static String[] readStrings(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        String[] arr = new String[n];
        for (int i = 0; i < n; i++) {
            arr[i] = buf.readUtf();
        }
        return arr;
    }
}
