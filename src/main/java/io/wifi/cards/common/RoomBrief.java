package io.wifi.cards.common;

import net.minecraft.network.chat.Component;

/**
 * 房间简要信息（聊天栏房间列表命令用，服务端可用——不含任何 client 引用）。
 * status：0=等待中可加入 1=对局中可旁观 2=已结束。
 */
public record RoomBrief(String code, Component line, byte status) {
}
