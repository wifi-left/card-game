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
 *   <li>未在房间：选择模式（经典/花牌）与规则集（标准/民间）、创建房间、输入房间码加入、规则介绍</li>
 *   <li>已在房间（等待中）：显示房间码/模式/规则/成员，可离开</li>
 * </ul>
 */
public class DdzLobbyScreen extends Screen {
    private boolean flowerMode;
    private DdzRuleSet ruleSet = DdzRuleSet.STANDARD;
    private EditBox codeBox;

    public DdzLobbyScreen() {
        super(Component.literal("斗地主大厅"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
            }).bounds(cx - 80, hc - 92, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则：" + ruleSet.displayName()), b -> {
                ruleSet = ruleSet == DdzRuleSet.STANDARD ? DdzRuleSet.FOLK : DdzRuleSet.STANDARD;
                b.setMessage(Component.literal("规则：" + ruleSet.displayName()));
            }).bounds(cx - 80, hc - 66, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(flowerMode, (byte) ruleSet.ordinal())))
                    .bounds(cx - 80, hc - 40, 160, 20).build());
            codeBox = new EditBox(this.font, cx - 80, hc - 10, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(5);
            codeBox.setFilter(str -> str.chars().allMatch(Character::isLetterOrDigit));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(cx - 80, hc + 16, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new DdzRulesScreen()))
                    .bounds(cx - 80, hc + 46, 160, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, hc + 56, 160, 20).build());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        int hc = height / 2;
        g.drawCenteredString(this.font, Component.literal("斗地主大厅"), cx, 24, 0xFFFFFFFF);
        if (!s.inRoom()) {
            g.drawCenteredString(this.font, Component.literal("创建房间邀请好友一起玩，或输入房间码加入"), cx, hc + 74, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("提示：房主可用 /doudizhu invite <玩家名> 邀请"), cx, hc + 90, 0xFF888888);
        } else {
            g.drawCenteredString(this.font,
                    Component.literal("房间 " + s.roomCode + "（" + (s.flowerMode ? "花牌模式" : "经典模式")
                            + " · " + s.ruleSet.displayName() + "）"),
                    cx, 50, 0xFFFFFF88);
            g.drawCenteredString(this.font, Component.literal("玩家 " + s.roomSize() + " / 3"), cx, 66, 0xFFFFFFFF);
            for (int i = 0; i < 3; i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names[i] == null || s.names[i].isEmpty() ? "等待加入…" : s.names[i]);
                g.drawCenteredString(this.font, Component.literal(line), cx, 84 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            g.drawCenteredString(this.font, Component.literal("满 3 人自动开始"), cx, 132, 0xFFAAAAAA);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }
}
