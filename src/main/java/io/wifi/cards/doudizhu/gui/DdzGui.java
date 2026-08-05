package io.wifi.cards.doudizhu.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** GUI 绘制小工具（纯客户端）。 */
public final class DdzGui {
    private DdzGui() {
    }

    /** 居中绘制带阴影文本（阴影提升可读性）。 */
    public static void centeredShadow(GuiGraphics g, Font font, int screenWidth, String text, int y, int color) {
        g.drawString(font, text, (screenWidth - font.width(text)) / 2, y, color, true);
    }
}
