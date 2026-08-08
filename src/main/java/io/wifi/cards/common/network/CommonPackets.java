package io.wifi.cards.common.network;

import io.wifi.cards.common.GameInfo;
import io.wifi.cards.common.GameRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小游戏公共网络包（1.21.1 CustomPayload API）：
 * <ul>
 *   <li>S2C {@code OpenMenuS2C}：小游戏菜单数据（各游戏条目 + 实时房间/在线统计），
 *       /cardgames 命令与菜单"刷新"按钮触发</li>
 *   <li>C2S {@code MenuQueryC2S}：请求最新菜单数据（打开菜单/刷新时发送）</li>
 *   <li>C2S {@code OpenGameC2S}：菜单点击条目 → 服务端按注册表路由到该游戏的 opener
 *       （打开其大厅 / 对局中恢复界面）</li>
 * </ul>
 * <p><b>服务端安全：</b>本类不引用任何含 client 的包（客户端接收器在
 * io.wifi.cards.common.client.GameMenuClient 中注册）。</p>
 */
public final class CommonPackets {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    /** 菜单刷新查询时间戳（服务端限频 500ms：客户端刷新按钮另有 1s 冷却，
     *  本限频兜底绕过客户端直接发包的恶意客户端，随断线清理）。 */
    private static final Map<UUID, Long> MENU_QUERY_TIMES = new ConcurrentHashMap<>();
    /** 每个玩家上次下发的菜单快照签名（仅统计：房间/在线数；无变化时刷新零发包）。 */
    private static final Map<UUID, String> MENU_SIGS = new ConcurrentHashMap<>();

    private CommonPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("wifi-card-games", "cg_" + path);
    }

    // ---------------- 包类型 ----------------

    public static final ResourceLocation OPEN_MENU = id("menu_open");
    public static final ResourceLocation MENU_QUERY = id("menu_query");
    public static final ResourceLocation OPEN_MENU_CMD = id("menu_open_cmd");
    public static final ResourceLocation OPEN_GAME = id("game_open");

    // ---------------- Payload 定义 ----------------

    /** 小游戏菜单数据（S2C）：各游戏条目数组（平行数组，长度一致）。 */
    public record OpenMenuS2C(String[] gameIds, String[] names, String[] icons, String[] descs,
                              int[] colors, int[] roomCounts, int[] playerCounts) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenMenuS2C> TYPE = new CustomPacketPayload.Type<>(OPEN_MENU);
        public static final StreamCodec<FriendlyByteBuf, OpenMenuS2C> CODEC = StreamCodec.of(
                (buf, value) -> {
                    writeStrings(buf, value.gameIds());
                    writeStrings(buf, value.names());
                    writeStrings(buf, value.icons());
                    writeStrings(buf, value.descs());
                    writeInts(buf, value.colors());
                    writeInts(buf, value.roomCounts());
                    writeInts(buf, value.playerCounts());
                },
                buf -> new OpenMenuS2C(readStrings(buf), readStrings(buf), readStrings(buf), readStrings(buf),
                        readInts(buf), readInts(buf), readInts(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 菜单刷新请求（C2S）：服务端重发最新统计（签名对比：统计无变化时不发，刷新专用）。 */
    public record MenuQueryC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MenuQueryC2S> TYPE = new CustomPacketPayload.Type<>(MENU_QUERY);
        public static final StreamCodec<FriendlyByteBuf, MenuQueryC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new MenuQueryC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 打开菜单请求（C2S，大厅"主菜单"按钮）：服务端**总是**下发最新菜单数据
     *  （不走签名对比——打开菜单必须响应，否则从大厅回不去菜单）。 */
    public record OpenMenuC2S() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenMenuC2S> TYPE = new CustomPacketPayload.Type<>(OPEN_MENU_CMD);
        public static final StreamCodec<FriendlyByteBuf, OpenMenuC2S> CODEC = StreamCodec.of(
                (buf, value) -> {
                },
                buf -> new OpenMenuC2S());

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 打开指定游戏（C2S，菜单点击）：服务端按注册表路由到该游戏的 opener。 */
    public record OpenGameC2S(String gameId) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenGameC2S> TYPE = new CustomPacketPayload.Type<>(OPEN_GAME);
        public static final StreamCodec<FriendlyByteBuf, OpenGameC2S> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeUtf(value.gameId()),
                buf -> new OpenGameC2S(buf.readUtf()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---------------- 注册 ----------------

    /** 注册全部 payload 类型 + 服务端接收器（主入口调用，客户端也会执行此方法）。 */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(MenuQueryC2S.TYPE, MenuQueryC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenMenuC2S.TYPE, OpenMenuC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(OpenGameC2S.TYPE, OpenGameC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenMenuS2C.TYPE, OpenMenuS2C.CODEC);
        registerServerReceivers();
    }

    // 客户端接收器在 io.wifi.cards.common.client.GameMenuClient（客户端类）中注册，
    // 本类不引用任何含 client 的包，保证服务端可正常加载。

    private static void registerServerReceivers() {
        // 所有 C2S 处理统一调度到服务器主线程执行（Fabric 接收器运行在 netty 线程），
        // 处理体统一 guarded 包装：主线程任务抛异常会崩溃整个服务器，任何意外只记录日志。
        // 打开菜单（大厅"主菜单"按钮）：总是下发（无签名对比/无刷新限频——打开必须响应）
        ServerPlayNetworking.registerGlobalReceiver(OpenMenuC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() ->
                        ServerPlayNetworking.send(ctx.player(), snapshot()))));
        ServerPlayNetworking.registerGlobalReceiver(MenuQueryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> {
                    // 频率限制（最小间隔 500ms/玩家）：恶意客户端高频刷包会占用服务端主线程与带宽
                    long now = System.currentTimeMillis();
                    Long last = MENU_QUERY_TIMES.get(ctx.player().getUUID());
                    if (last != null && now - last < 500) {
                        return;
                    }
                    MENU_QUERY_TIMES.put(ctx.player().getUUID(), now);
                    // 签名对比：统计（房间/在线数）无变化时直接不发，客户端沿用现有菜单数据
                    OpenMenuS2C snap = snapshot();
                    String sig = menuSig(snap);
                    if (sig.equals(MENU_SIGS.get(ctx.player().getUUID()))) {
                        return;
                    }
                    MENU_SIGS.put(ctx.player().getUUID(), sig);
                    ServerPlayNetworking.send(ctx.player(), snap);
                })));
        ServerPlayNetworking.registerGlobalReceiver(OpenGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> {
                    GameInfo info = GameRegistry.byId(payload.gameId());
                    if (info == null) {
                        ctx.player().sendSystemMessage(Component.translatable(
                                "wifi_card_games.common.error.unknown_game", payload.gameId()));
                        return;
                    }
                    info.opener().accept(ctx.player());
                })));
    }

    /** 玩家断线：清理频率限制与签名记录，防 Map 泄漏（由 CommonMod 的断线事件调用）。 */
    public static void onPlayerDisconnect(UUID uuid) {
        MENU_QUERY_TIMES.remove(uuid);
        MENU_SIGS.remove(uuid);
    }

    /** 菜单快照签名（仅统计部分：游戏条目静态不变，统计变化才需重新下发）。 */
    private static String menuSig(OpenMenuS2C snap) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snap.gameIds().length; i++) {
            sb.append(snap.gameIds()[i]).append('|')
                    .append(snap.roomCounts()[i]).append('|')
                    .append(snap.playerCounts()[i]).append(';');
        }
        return sb.toString();
    }

    /** 主线程任务防护：意外异常只记录日志，绝不让服务器崩溃。 */
    private static void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("处理小游戏公共网络包异常", t);
        }
    }

    // ---------------- 菜单数据构建 ----------------

    /** 从注册表构建当前菜单快照（含实时统计）。 */
    public static OpenMenuS2C snapshot() {
        List<GameInfo> games = GameRegistry.all();
        int n = games.size();
        String[] ids = new String[n];
        String[] names = new String[n];
        String[] icons = new String[n];
        String[] descs = new String[n];
        int[] colors = new int[n];
        int[] roomCounts = new int[n];
        int[] playerCounts = new int[n];
        for (int i = 0; i < n; i++) {
            GameInfo info = games.get(i);
            ids[i] = info.gameId();
            names[i] = info.displayName();
            icons[i] = info.iconText();
            descs[i] = info.description();
            colors[i] = info.iconColor();
            roomCounts[i] = info.roomCount().getAsInt();
            playerCounts[i] = info.playerCount().getAsInt();
        }
        return new OpenMenuS2C(ids, names, icons, descs, colors, roomCounts, playerCounts);
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

    private static void writeInts(FriendlyByteBuf buf, int[] arr) {
        buf.writeVarInt(arr.length);
        for (int v : arr) {
            buf.writeInt(v);
        }
    }

    private static int[] readInts(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        int[] arr = new int[n];
        for (int i = 0; i < n; i++) {
            arr[i] = buf.readInt();
        }
        return arr;
    }
}
