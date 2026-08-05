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

    @Override
    protected void init() {
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        int hc = height / 2;
        if (!s.inRoom()) {
            addRenderableWidget(Button.builder(Component.literal("模式：经典"), b -> {
                flowerMode = !flowerMode;
                b.setMessage(Component.literal(flowerMode ? "模式：花牌（万能牌）" : "模式：经典"));
            }).bounds(cx - 80, hc - 100, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则：" + ruleSet.displayName()), b -> {
                ruleSet = ruleSet == DdzRuleSet.STANDARD ? DdzRuleSet.FOLK : DdzRuleSet.STANDARD;
                b.setMessage(Component.literal("规则：" + ruleSet.displayName()));
            }).bounds(cx - 80, hc - 76, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("公布房间：开"), b -> {
                announce = !announce;
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(cx - 80, hc - 52, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("机器人：关"), b -> {
                botCount = (botCount + 1) % 3; // 关 → 1 个 → 2 个
                b.setMessage(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")));
            }).bounds(cx - 80, hc - 28, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(flowerMode, (byte) ruleSet.ordinal(), announce, (byte) botCount)))
                    .bounds(cx - 80, hc - 4, 160, 20).build());
            codeBox = new EditBox(this.font, cx - 80, hc + 22, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(5);
            codeBox.setFilter(str -> str.chars().allMatch(Character::isLetterOrDigit));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(cx - 80, hc + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new DdzRulesScreen()))
                    .bounds(cx - 80, hc + 76, 160, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, hc + 56, 160, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（含 renderBackground），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        int hc = height / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        DdzGui.centeredShadow(g, this.font, width, "斗地主大厅", 9, 0xFFFFD700);
        if (!s.inRoom()) {
            // 提示区半透明黑底（仅在有内容处）
            g.fill(cx - 200, hc + 72, cx + 200, hc + 110, 0x55000000);
            DdzGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", hc + 80, 0xFFAAAAAA);
            DdzGui.centeredShadow(g, this.font, width, "提示：房主可用 /doudizhu invite <玩家名> 邀请", hc + 94, 0xFF777777);
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
