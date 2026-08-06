package io.wifi.cards.common;

import io.wifi.cards.common.network.CommonPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 小游戏房间公共基类：所有小游戏房间（斗地主 / UNO / 棋类）继承本类，
 * 统一房间标识、公开标记、结算保留时间与旁观者管理。
 * <ul>
 * <li>id：房间号（统一格式"前缀-5位码"，前缀见 {@link GameRegistry}），创建时生成</li>
 * <li>announce：创建时是否公开（"公布房间"开启）——公开房间才出现在大厅房间列表，
 * 才能被其他玩家直接加入/旁观</li>
 * <li>settledAtMillis：结算完成时刻，用于空闲房间自动销毁</li>
 * <li>spectators：旁观者列表（对局开始后可旁观，只读观看，不占座位）</li>
 * </ul>
 * 座位布局（members/botNames/game）因游戏而异，留在各子类。
 */
public abstract class Room {
    public final String id;

    /** 是否公开房间（创建时"公布房间"开启）：公开房间出现在大厅房间列表，可被加入/旁观。 */
    public final boolean announce;

    /** 结算完成时刻（毫秒），用于空闲房间自动销毁。 */
    public long settledAtMillis = -1;

    /** 旁观者（对局开始后可旁观，只读观看，不占座位）。 */
    public final List<ServerPlayer> spectators = new ArrayList<>();

    protected Room(String id, boolean announce) {
        this.id = id;
        this.announce = announce;
    }

    public void addSpectator(ServerPlayer player) {
        if (!spectators.contains(player)) {
            spectators.add(player);
        }
    }

    public void removeSpectator(ServerPlayer player) {
        spectators.remove(player);
    }

    /**
     * 玩家连接是否仍可用（1.21.1 无公开的断线查询方法，用 fabric 的 canSend 判定）。
     * 以公共层已注册的 S2C 包类型判活（客户端必然注册，语义与各游戏包一致）。
     */
    public static boolean isConnected(ServerPlayer player) {
        return player != null && ServerPlayNetworking.canSend(player, CommonPackets.OpenMenuS2C.TYPE);
    }

    /** 玩家能否接收指定包（按包类型检查 channel 注册，1.21.1 判活标准做法）。 */
    public static boolean isConnected(ServerPlayer player, CustomPacketPayload payload) {
        return player != null && ServerPlayNetworking.canSend(player, payload.type());
    }

    public static boolean isOnline(ServerPlayer player) {
        return player != null && player.server != null
                && player.server.getPlayerList().getPlayer(player.getUUID()) != null;
    }
}
