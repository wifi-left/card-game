package io.wifi.cards.uno.gui;

import io.wifi.cards.common.client.AbstractSubScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则介绍界面：玩法、出牌、功能牌、抽牌、UNO 喊牌、托管与结算说明。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 * 原版模糊背景，以确保看得见文本
 */
public class UnoRulesScreen extends AbstractSubScreen {
    private final List<Component> lines = new ArrayList<>();

    public UnoRulesScreen() {
        this(null);
    }

    public UnoRulesScreen(Screen parent) {
        super(parent, "wifi_card_games.uno.rules.title");
    }

    // ---------------- 滚动计算 ----------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderOriginalBackground(g, mouseX, mouseY, partialTick);
    }

    /** 内容总高（行数 × 行高；历史界面按换行后行数累计）。 */
    @Override
    protected int contentHeight() {
        return lines.size() * LINE_H;
    }

    /** 无父级打开时的返回目标（大厅）。 */
    @Override
    protected Screen fallbackScreen() {
        return new UnoLobbyScreen();
    }

    @Override
    protected void buildContent() {
        lines.clear(); // resize（窗口/全屏变化）会再次调用 init→buildContent，先清空防内容翻倍
        buildLines();
    }

    private void buildLines() {
        lines.add(Component.translatable("wifi_card_games.uno.rules.line1"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line2"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line3"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line4"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line5"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line6"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line7"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line8"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line9"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line10"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line11"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line12"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line13"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line14"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line15"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line16"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line17"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line18"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line19"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line20"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line21"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line22"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line23"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line24"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line25"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line26"));
        lines.add(Component.translatable("wifi_card_games.uno.rules.line27"));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, Component.translatable("wifi_card_games.uno.rules.title"), width / 2, 9, 0xFFFFD700);
        // 内容区半透明黑底（仅在有内容处），滚动文本绘制在其上
        // g.fill(0, CONTENT_TOP, width, height - BOTTOM_BAR, 0x44000000);
        g.enableScissor(0, CONTENT_TOP, width, height - BOTTOM_BAR);
        int y = CONTENT_TOP;
        int viewportBottom = height - BOTTOM_BAR;
        for (Component line : lines) {
            int drawY = y - (int) scroll;
            if (drawY >= CONTENT_TOP - LINE_H && drawY < viewportBottom) {
                g.drawString(this.font, line, 8, drawY, 0xFFDDDDDD, true);
            }
            y += LINE_H;
        }
        g.disableScissor();
        drawScrollbar(g);
        drawScrollHint(g);
    }

}
