package io.wifi.cards.common;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 小游戏注册条目：每个游戏在自身 XxxMod.init() 中构造并登记到 {@link GameRegistry}。
 * <p>承载三层能力：</p>
 * <ul>
 *   <li>菜单展示：游戏名 / 图标首字 / 配色 / 简介 / 房间与在线统计（{@link #roomCount} {@link #playerCount}）</li>
 *   <li>统一命令路由：打开大厅（{@link #opener}）、加入/旁观/离开/邀请（{@link #joiner} {@link #spectater}
 *       {@link #leaver} {@link #inviter}），/cardgames 按房间码前缀路由到对应游戏</li>
 *   <li>跨游戏防护：{@link #busy} 判定玩家是否在该游戏有会话（房间成员或旁观），
 *       进入任何小游戏前必须确保其它游戏无占用</li>
 * </ul>
 */
public record GameInfo(
        String gameId,
        /** 房间码前缀（统一格式：前缀-5位码，如 "DZ-AB12K"）。 */
        String prefix,
        /** 菜单显示名，如 "斗地主"。 */
        String displayName,
        /** 菜单左侧图标文字（游戏名第一个字），如 "斗"。 */
        String iconText,
        /** 图标背景色（ARGB）。 */
        int iconColor,
        /** 菜单简介。 */
        String description,
        /** 打开该游戏 UI：对局/旁观中重发快照恢复界面，否则打开大厅（房间列表通过命令查看）。 */
        Consumer<ServerPlayer> opener,
        /** 加入房间（房间码含前缀；失败经该游戏的 NoticeS2C 自行提示）。 */
        BiConsumer<ServerPlayer, String> joiner,
        /** 旁观房间，返回错误消息或 null。 */
        BiFunction<ServerPlayer, String, String> spectater,
        /** 离开房间/退出旁观（该游戏 leaveRoom 内部区分成员与旁观）。 */
        Consumer<ServerPlayer> leaver,
        /** 邀请玩家加入自己所在房间；成功时向目标发送可点击邀请消息，返回错误消息或 null。 */
        BiFunction<ServerPlayer, ServerPlayer, String> inviter,
        /** 玩家是否在该游戏有会话（房间成员或旁观）。 */
        Predicate<ServerPlayer> busy,
        IntSupplier roomCount,
        /** 在线人数统计（房间成员 + 旁观者）。 */
        IntSupplier playerCount,
        /** 房间单行摘要列表（统一房间管理显示用）。 */
        Supplier<List<String>> roomLines,
        /** 房间列表行（聊天栏 /cardgames rooms 用）：includePrivate=true 时含未公开房间（管理员查询）。 */
        Function<Boolean, List<RoomBrief>> roomBriefs,
        /** 房间详情行（/cardgames roominfo 用）；房间不存在返回空列表。 */
        Function<String, List<String>> roomDetailer,
        /** 删除指定房间（房间码含前缀），返回错误消息或 null。 */
        Function<String, String> roomDeleter,
        /** 清空全部房间，返回删除数量。 */
        IntSupplier roomClearer
) {
}
