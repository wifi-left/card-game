package io.wifi.cards.uno.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.common.network.CommonPackets.MenuQueryC2S;
import io.wifi.cards.uno.network.UnoPackets.CreateRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.JoinRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.LeaveRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.StartGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 大厅界面：
 * <ul>
 *   <li>未在房间：选择是否公布房间到聊天栏、创建房间（可带 0~3 个机器人）、
 *       输入房间码加入</li>
 *   <li>已在房间（等待中）：显示房间码/成员列表（最多 10 人），
 *       房主（座位 0）可点"开始游戏"（至少 2 人），其余玩家等待；可离开</li>
 * </ul>
 */
public class UnoLobbyScreen extends Screen {
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时加入的机器人数量（0~3 补位）。 */
    private int botCount;
    private EditBox codeBox;
    /** 内容区滚动偏移（≤0，小窗口内容超高时滚轮上移）。 */
    private float scroll;

    public UnoLobbyScreen() {
        super(Component.literal("UNO 大厅"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 关闭大厅（Esc）：等待玩家中关闭时提示可通过命令/点击重新打开；
     *  从菜单进入本大厅后关闭时，若其它游戏有进行中的会话则恢复其界面。 */
    @Override
    public void onClose() {
        if (GameMenuClient.tryRestoreOtherSession(GameRegistry.GAME_UNO)) {
            return;
        }
        if (UnoClientState.INSTANCE.inRoom()) {
            UnoClientState.chatReopenHint("关闭大厅");
        }
        super.onClose();
    }

    // ---------------- 内容区布局（两列紧凑 + 滚轮滚动兜底） ----------------

    /** 内容区顶部 y（随滚动偏移）。 */
    private int contentTop() {
        return Math.max(50, (height - 160) / 2) + (int) scroll;
    }

    /** 内容区底部 y（提示文本末行 + 边距）。 */
    private int contentBottom() {
        return contentTop() + 124;
    }

    /** 内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int maxScroll() {
        return Math.max(0, contentBottom() - (height - 30));
    }

    /** 房间信息区顶部 y（随滚动偏移）。 */
    private int roomTop() {
        return Math.max(30, (height - (roomInfoH() + 60)) / 2) + (int) scroll;
    }

    /** 房间信息区高度（标题行 + 成员行 + 底部提示行）。 */
    private int roomInfoH() {
        UnoClientState s = UnoClientState.INSTANCE;
        return 30 + s.names.size() * 14 + 16;
    }

    /** 房间内容区底部（信息面板 + 开始/离开按钮行）。 */
    private int roomBottom() {
        return roomTop() + roomInfoH() + 8 + 48;
    }

    /** 房间内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int roomMaxScroll() {
        return Math.max(0, roomBottom() - (height - 30));
    }

    /** 滚动后重建全部控件（位置随 contentTop/roomTop 变化）。 */
    private void rebuild() {
        // 保留输入框内容（滚动重建会重新创建 EditBox，直接重建会清空已输入的房间码）
        String prevCode = codeBox != null ? codeBox.getValue() : "";
        clearWidgets();
        init();
        if (codeBox != null && !prevCode.isEmpty()) {
            codeBox.setValue(prevCode);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        UnoClientState s = UnoClientState.INSTANCE;
        int limit = s.inRoom() ? roomMaxScroll() : maxScroll();
        if (limit > 0) {
            scroll -= (float) verticalAmount * 10;
            scroll = Math.max(-limit, Math.min(0, scroll));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void init() {
        clearWidgets(); // 滚动重建时防重复添加
        UnoClientState s = UnoClientState.INSTANCE;
        int cx = width / 2;
        // 返回小游戏菜单（发刷新请求，服务端回发菜单数据打开菜单界面）
        addRenderableWidget(Button.builder(Component.literal("主菜单"), b ->
                        ClientPlayNetworking.send(new MenuQueryC2S()))
                .bounds(width - 110, 32, 100, 20).build());
        if (!s.inRoom()) {
            // 两列紧凑布局：左列选项（公布/机器人），右列操作（创建/输入框/加入）
            int top = contentTop();
            int lx = cx - 172;
            int rx = cx + 12;
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "开" : "关")), b -> {
                announce = !announce;
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(lx, top, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")), b -> {
                botCount = (botCount + 1) % 4; // 关 → 1 个 → 2 个 → 3 个
                b.setMessage(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")));
            }).bounds(lx, top + 24, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(announce, (byte) botCount)))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(8);
            codeBox.setFilter(str -> str.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-'));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new UnoRulesScreen()))
                    .bounds(rx, top + 72, 160, 20).build());
        } else {
            // 房主（座位 0）开始游戏（至少 2 人）；其余玩家等待提示。
            // 按钮位于成员列表下方（随滚动偏移），小窗口下列表超高时可滚动查看
            int btnY = roomTop() + roomInfoH() + 8;
            if (s.isHost()) {
                Button startBtn = Button.builder(Component.literal("开始游戏"), b ->
                        ClientPlayNetworking.send(new StartGameC2S()))
                        .bounds(cx - 80, btnY, 160, 20).build();
                startBtn.active = s.roomSize() >= 2;
                addRenderableWidget(startBtn);
            }
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, btnY + 26, 160, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        UnoClientState s = UnoClientState.INSTANCE;
        int cx = width / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        UnoGui.centeredShadow(g, this.font, width, "UNO 大厅", 9, 0xFFFFD700);
        if (!s.inRoom()) {
            int top = contentTop();
            // 提示区半透明黑底（位于内容区下方，不与按钮重叠）
            g.fill(cx - 180, top + 94, cx + 180, top + 126, 0x55000000);
            UnoGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", top + 100, 0xFFAAAAAA);
            UnoGui.centeredShadow(g, this.font, width, "提示：房主可用 /cardgames invite <玩家名> 邀请", top + 114, 0xFF777777);
            if (maxScroll() > 0) {
                UnoGui.centeredShadow(g, this.font, width, "内容超出屏幕，滚动滚轮查看", height - 14, 0xFF888888);
            }
        } else {
            // 房间信息区半透明黑底（随滚动偏移，覆盖到最底部提示行）
            int top = roomTop();
            int infoH = roomInfoH();
            g.fill(cx - 200, top, cx + 200, top + infoH + 8, 0x55000000);
            UnoGui.centeredShadow(g, this.font, width, "房间 " + s.roomCode, top + 4, 0xFFFFFF88);
            UnoGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 10", top + 20, 0xFFFFFFFF);
            for (int i = 0; i < s.names.size(); i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names.get(i) == null || s.names.get(i).isEmpty() ? "等待加入…" : s.names.get(i))
                        + (i == 0 ? "（房主）" : "");
                UnoGui.centeredShadow(g, this.font, width, line, top + 34 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            UnoGui.centeredShadow(g, this.font, width,
                    s.isHost() ? "至少 2 人可开始游戏" : "等待房主开始游戏…",
                    top + 34 + s.names.size() * 14 + 6, 0xFFAAAAAA);
            if (roomMaxScroll() > 0) {
                UnoGui.centeredShadow(g, this.font, width, "内容超出屏幕，滚动滚轮查看", height - 14, 0xFF888888);
            }
        }
    }
}
