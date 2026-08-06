package io.wifi.cards.uno.gui;

import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** GUI 绘制小工具（纯客户端）。 */
public final class UnoGui {
    private UnoGui() {
    }

    /** 在 [0, screenWidth] 范围内居中绘制带阴影文本（阴影提升可读性）。 */
    public static void centeredShadow(GuiGraphics g, Font font, int screenWidth, String text, int y, int color) {
        g.drawString(font, text, (screenWidth - font.width(text)) / 2, y, color, true);
    }

    /** 以 centerX 为屏幕中点居中绘制带阴影文本（用于非全宽区域，如中央信息面板）。 */
    public static void centeredShadowAt(GuiGraphics g, Font font, int centerX, String text, int y, int color) {
        int x = centerX - font.width(text) / 2;
        g.drawString(font, text, Math.max(0, x), y, color, true);
    }

    /** 牌面主色（文字色）。 */
    public static int textColor(UnoCard card) {
        if (card.isWild()) {
            return 0xFFFFD700; // 万能牌金色文字
        }
        return switch (card.color()) {
            case RED -> 0xFFC00000;
            case YELLOW -> 0xFFA87B00;
            case GREEN -> 0xFF1E7A1E;
            default -> 0xFF2030C0;
        };
    }

    /** 牌面底色（浅色底，与斗地主文字化牌面风格一致）。 */
    public static int cardBackground(UnoCard card) {
        if (card.isWild()) {
            return 0xFF3A3A3A; // 万能牌深灰底
        }
        return switch (card.color()) {
            case RED -> 0xFFFFE1E1;
            case YELLOW -> 0xFFFFF6D8;
            case GREEN -> 0xFFE2F5E2;
            default -> 0xFFE1E9FF;
        };
    }

    /** 颜色的高亮色（方向箭头/选中提示用）。 */
    public static int colorHighlight(UnoColor color) {
        return switch (color) {
            case RED -> 0xFFFF5555;
            case YELLOW -> 0xFFFFD700;
            case GREEN -> 0xFF55FF55;
            case BLUE -> 0xFF55AAFF;
            default -> 0xFFFFFFFF;
        };
    }

    /**
     * 绘制一张 UNO 牌（文字化：描边 + 色块 + 左上角颜色字 + 中央牌面值，万能牌金色）。
     * 无阴影保证小字号清晰。
     */
    public static void drawCard(GuiGraphics g, UnoCard card, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000); // 黑色描边
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, cardBackground(card));
        Font font = Minecraft.getInstance().font;
        int color = textColor(card);
        if (!card.isWild() && w >= 22) {
            // 左上角颜色字（小尺寸牌省略，只保留中央值）
            g.drawString(font, card.color().symbol(), x + 2, y + 2, color, false);
        }
        String value = card.value().displayName();
        // 中央牌面值（长文字自动截断防溢出）
        value = font.plainSubstrByWidth(value, Math.max(4, w - 2));
        g.drawString(font, value, x + (w - font.width(value)) / 2, y + (h - font.lineHeight) / 2, color, false);
    }

    /** 绘制一张牌背（深蓝底 + 问号）。 */
    public static void drawCardBack(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B4B8F);
        Font font = Minecraft.getInstance().font;
        g.drawString(font, "?", x + (w - font.width("?")) / 2, y + (h - 9) / 2, 0xFFFFFFFF, false);
    }
}
