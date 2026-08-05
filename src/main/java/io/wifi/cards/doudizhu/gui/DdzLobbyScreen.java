package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.network.DdzPackets.CreateRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.JoinRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.LeaveRoomC2S;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
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
 *   <li>未在房间：选择模式（经典/花牌）、规则集（标准/民间）、是否公布房间到聊天栏、创建房间、
 *       输入房间码加入、规则介绍</li>
 *   <li>已在房间（等待中）：显示房间码/模式/规则/成员，可离开</li>
 * </ul>
 */
public class DdzLobbyScreen extends Screen {
    private boolean flowerMode;
    private DdzRuleSet ruleSet = DdzRuleSet.STANDARD;
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时加入的机器人数量（0~2，补位自动开局）。 */
    private int botCount;
    private EditBox codeBox;
    /** 内容区滚动偏移（≤0，小窗口内容超高时滚轮上移）。 */
    private float scroll;

    public DdzLobbyScreen() {
        super(Component.literal("斗地主大厅"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

    /** 滚动后重建全部控件（位置随 contentTop 变化）。 */
    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!DdzClientState.INSTANCE.inRoom() && maxScroll() > 0) {
            scroll -= (float) verticalAmount * 10;
            scroll = Math.max(-maxScroll(), Math.min(0, scroll));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void init() {
        clearWidgets(); // 滚动重建时防重复添加
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            // 两列紧凑布局：左列选项（模式/规则/公布/机器人），右列操作（创建/输入框/加入/规则介绍）
            int top = contentTop();
            int lx = cx - 172;
            int rx = cx + 12;
            addRenderableWidget(Button.builder(Component.literal("模式：" + (flowerMode ? "花牌（万能牌）" : "经典")), b -> {
                flowerMode = !flowerMode;
                b.setMessage(Component.literal("模式：" + (flowerMode ? "花牌（万能牌）" : "经典")));
            }).bounds(lx, top, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则：" + ruleSet.displayName()), b -> {
                ruleSet = ruleSet == DdzRuleSet.STANDARD ? DdzRuleSet.FOLK : DdzRuleSet.STANDARD;
                b.setMessage(Component.literal("规则：" + ruleSet.displayName()));
            }).bounds(lx, top + 24, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "开" : "关")), b -> {
                announce = !announce;
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(lx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")), b -> {
                botCount = (botCount + 1) % 3; // 关 → 1 个 → 2 个
                b.setMessage(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")));
            }).bounds(lx, top + 72, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(flowerMode, (byte) ruleSet.ordinal(), announce, (byte) botCount)))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(5);
            codeBox.setFilter(str -> str.chars().allMatch(Character::isLetterOrDigit));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new DdzRulesScreen()))
                    .bounds(rx, top + 72, 160, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, Math.max(40, height / 2 + 56), 160, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        DdzGui.centeredShadow(g, this.font, width, "斗地主大厅", 9, 0xFFFFD700);
        if (!s.inRoom()) {
            int top = contentTop();
            // 提示区半透明黑底（位于内容区下方，不与按钮重叠）
            g.fill(cx - 180, top + 94, cx + 180, top + 126, 0x55000000);
            DdzGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", top + 100, 0xFFAAAAAA);
            DdzGui.centeredShadow(g, this.font, width, "提示：房主可用 /doudizhu invite <玩家名> 邀请", top + 114, 0xFF777777);
            if (maxScroll() > 0) {
                DdzGui.centeredShadow(g, this.font, width, "内容超出屏幕，滚动滚轮查看", height - 14, 0xFF888888);
            }
        } else {
            // 房间信息区半透明黑底（覆盖到最底部提示行 116 之下）
            g.fill(cx - 200, 30, cx + 200, 130, 0x55000000);
            DdzGui.centeredShadow(g, this.font, width,
                    "房间 " + s.roomCode + "（" + (s.flowerMode ? "花牌模式" : "经典模式")
                            + " · " + s.ruleSet.displayName() + "）",
                    34, 0xFFFFFF88);
            DdzGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 3", 50, 0xFFFFFFFF);
            for (int i = 0; i < 3; i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names[i] == null || s.names[i].isEmpty() ? "等待加入…" : s.names[i]);
                DdzGui.centeredShadow(g, this.font, width, line, 68 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            DdzGui.centeredShadow(g, this.font, width, "满 3 人自动开始", 116, 0xFFAAAAAA);
        }
    }
}
