package io.wifi.cards.common.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 客户端 UI 绘制小工具（纯客户端）。
 */
public final class GuiUtil {
    private GuiUtil() {
    }

    /**
     * 渲染竖直滚动条（内容超高可滚动时；不超高则不绘制）。
     *
     * @param trackX   轨道左缘 x（轨道宽固定 2px）
     * @param trackY   轨道顶 y
     * @param trackH   轨道高（可视区高度）
     * @param scroll   当前滚动偏移（正数，0=顶部）
     * @param maxScroll 最大滚动偏移（scroll 的取值范围，≤0 不绘制）
     */
    public static void drawScrollbar(GuiGraphics g, int trackX, int trackY, int trackH, int scroll, int maxScroll) {
        if (maxScroll <= 0 || trackH <= 0) {
            return;
        }
        // 内容总高 = 可视高 + 最大滚动量
        int contentH = trackH + maxScroll;
        // 滑块高与内容占比成正比（最小 12px 保证可见）
        int sliderH = sliderHeight(trackH, maxScroll);
        // 滑块位置与滚动偏移成正比
        int sliderY = trackY + (trackH - sliderH) * Math.max(0, Math.min(scroll, maxScroll)) / maxScroll;
        // 轨道（半透明黑）
        g.fill(trackX, trackY, trackX + 2, trackY + trackH, 0x55000000);
        // 滑块（浅灰）
        g.fill(trackX, sliderY, trackX + 2, sliderY + sliderH, 0xCCAAAAAA);
    }

    /** 滑块高度（与 {@link #drawScrollbar} 一致）。 */
    public static int sliderHeight(int trackH, int maxScroll) {
        if (maxScroll <= 0 || trackH <= 0) {
            return 0;
        }
        return Math.max(12, trackH * trackH / (trackH + maxScroll));
    }

    /** 滑块可移动范围（轨道高 - 滑块高），恒 ≥1 防除零。 */
    public static int sliderRange(int trackH, int maxScroll) {
        return Math.max(1, trackH - sliderHeight(trackH, maxScroll));
    }

    /** 由鼠标 y 换算滚动偏移（0..maxScroll，越界钳制；点击/拖拽共用，按滑块中心对齐）。 */
    public static int scrollFromY(int mouseY, int trackY, int trackH, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        int scrolled = (mouseY - trackY - sliderHeight(trackH, maxScroll) / 2) * maxScroll / sliderRange(trackH, maxScroll);
        return Math.max(0, Math.min(scrolled, maxScroll));
    }
}
