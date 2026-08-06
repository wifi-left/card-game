package io.wifi.cards.board.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 棋牌 GUI 绘制小工具（纯客户端）。 */
public final class BoardGui {
    private BoardGui() {
    }

    /** 在 [0, screenWidth] 范围内居中绘制带阴影文本（阴影提升可读性）。 */
    public static void centeredShadow(GuiGraphics g, Font font, int screenWidth, String text, int y, int color) {
        g.drawString(font, text, (screenWidth - font.width(text)) / 2, y, color, true);
    }

    /** 以 centerX 为屏幕中点居中绘制带阴影文本（用于非全宽区域，如棋盘上方横幅）。 */
    public static void centeredShadowAt(GuiGraphics g, Font font, int centerX, String text, int y, int color) {
        int x = centerX - font.width(text) / 2;
        g.drawString(font, text, Math.max(0, x), y, color, true);
    }
}
