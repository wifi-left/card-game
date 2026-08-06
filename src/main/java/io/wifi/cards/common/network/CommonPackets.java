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

    /** 菜单刷新查询时间戳（MenuQueryC2S 频率限制，随断线清理）。 */
    private static final Map<UUID, Long> MENU_QUERY_TIMES = new ConcurrentHashMap<>();

    private CommonPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("wifi-card-games", "cg_" + path);
    }

    // ---------------- 包类型 ----------------

    public static final ResourceLocation OPEN_MENU = id("menu_open");
    public static final ResourceLocation MENU_QUERY = id("menu_query");
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

    /** 菜单刷新请求（C2S）：服务端重发最新统计。 */
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
        PayloadTypeRegistry.playC2S().register(OpenGameC2S.TYPE, OpenGameC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenMenuS2C.TYPE, OpenMenuS2C.CODEC);
        registerServerReceivers();
    }

    // 客户端接收器在 io.wifi.cards.common.client.GameMenuClient（客户端类）中注册，
    // 本类不引用任何含 client 的包，保证服务端可正常加载。

    private static void registerServerReceivers() {
        // 所有 C2S 处理统一调度到服务器主线程执行（Fabric 接收器运行在 netty 线程），
        // 处理体统一 guarded 包装：主线程任务抛异常会崩溃整个服务器，任何意外只记录日志。
        ServerPlayNetworking.registerGlobalReceiver(MenuQueryC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> {
                    // 频率限制（最小间隔 500ms/玩家）：恶意客户端高频刷包会占用服务端主线程与带宽
                    long now = System.currentTimeMillis();
                    Long last = MENU_QUERY_TIMES.get(ctx.player().getUUID());
                    if (last != null && now - last < 500) {
                        return;
                    }
                    MENU_QUERY_TIMES.put(ctx.player().getUUID(), now);
                    ServerPlayNetworking.send(ctx.player(), snapshot());
                })));
        ServerPlayNetworking.registerGlobalReceiver(OpenGameC2S.TYPE, (payload, ctx) ->
                ctx.server().execute(() -> guarded(() -> {
                    GameInfo info = GameRegistry.byId(payload.gameId());
                    if (info == null) {
                        ctx.player().sendSystemMessage(Component.literal(
                                "[小游戏] 未知的游戏：" + payload.gameId()));
                        return;
                    }
                    info.opener().accept(ctx.player());
                })));
    }

    /** 玩家断线：清理频率限制记录，防 Map 泄漏（由 CommonMod 的断线事件调用）。 */
    public static void onPlayerDisconnect(UUID uuid) {
        MENU_QUERY_TIMES.remove(uuid);
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
