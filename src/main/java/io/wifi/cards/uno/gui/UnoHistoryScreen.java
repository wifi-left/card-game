package io.wifi.cards.uno.gui;

import io.wifi.cards.uno.gui.UnoClientState.HistoryLine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 事件历史界面：文本记录（"XXX 打出 红9"、"XXX 被罚抽 2 张"），最新在前。
 * <ul>
 *   <li>内容超出一屏时可滚动（滚轮 + 右侧可拖拽滚动条，同规则界面）</li>
 *   <li>玩家名金色高亮，事件描述灰色</li>
 *   <li>有父级（打牌界面）时渲染父级内容为背景，返回时回到父级</li>
 * </ul>
 */
public class UnoHistoryScreen extends Screen {
    private static final int CONTENT_TOP = 30;
    private static final int LINE_H = 10;
    private static final int BOTTOM_BAR = 30;
    private static final int SCROLLBAR_RIGHT = 8;
    private static final int SCROLLBAR_W = 3;

    private final Screen parent;
    private float scroll;
    private boolean draggingScrollbar;
    private double dragOffset;

    public UnoHistoryScreen(Screen parent) {
        super(Component.literal("事件历史"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------- 滚动计算（与规则界面一致） ----------------

    private int trackTop() {
        return CONTENT_TOP;
    }

    private int trackBottom() {
        return height - BOTTOM_BAR;
    }

    private int trackHeight() {
        return trackBottom() - trackTop();
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - trackHeight());
    }

    private int thumbHeight() {
        int track = trackHeight();
        int content = totalHeight();
        return Math.max(12, track * Math.min(track, content) / Math.max(content, track));
    }

    private int thumbTop() {
        int max = maxScroll();
        if (max <= 0) {
            return trackTop();
        }
        return trackTop() + (int) ((trackHeight() - thumbHeight()) * scroll / max);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private List<HistoryLine> lines() {
        return UnoClientState.INSTANCE.historyLines;
    }

    // ---------------- 文本构建（Component 样式着色 + font.split 换行） ----------------

    /** 文本区可用宽度（右侧留出滚动条）。 */
    private int maxWidth() {
        return Math.max(80, width - SCROLLBAR_RIGHT - 16);
    }

    /** 构建一行历史文本：玩家（金）+ 事件描述（灰）。 */
    private MutableComponent buildLineText(HistoryLine line) {
        return Component.literal(line.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" " + line.text()).withStyle(ChatFormatting.GRAY));
    }

    /** 超宽文本按可用宽度换行拆分（每段保留各自样式颜色）。 */
    private List<FormattedCharSequence> wrapLine(HistoryLine line) {
        return this.font.split(buildLineText(line), maxWidth());
    }

    /** 全部行的实际总高度（换行后按行数累计）。 */
    private int totalHeight() {
        int total = 0;
        for (HistoryLine line : lines()) {
            total += LINE_H * Math.max(1, wrapLine(line).size());
        }
        return total;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(width / 2 - 40, height - BOTTOM_BAR + 4, 80, 20).build());
    }

    /** 是否从打牌界面打开（返回归属判断用）。 */
    public boolean isFromGame() {
        return parent instanceof UnoGameScreen;
    }

    /** 返回：有父级（打牌界面）则回到父级，否则回大厅。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent != null ? parent : new UnoLobbyScreen());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有父级时先渲染父级内容为背景，再绘制本界面
        if (parent != null) {
            parent.render(g, 0, 0, partialTick);
        }
        // 底部提示条背景（先画，返回按钮绘制在其上）
        g.fill(0, height - BOTTOM_BAR, width, height, 0x44000000);
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, 26, 0x66000000);
        UnoGui.centeredShadow(g, this.font, width, "事件历史", 9, 0xFFFFD700);
        // 内容区半透明黑底（仅在有内容处），滚动文本绘制在其上
        g.fill(0, CONTENT_TOP, width, height - BOTTOM_BAR, 0x44000000);
        g.enableScissor(0, CONTENT_TOP, width, height - BOTTOM_BAR);
        int y = CONTENT_TOP;
        int viewportBottom = height - BOTTOM_BAR;
        for (HistoryLine line : lines()) {
            List<FormattedCharSequence> wrapped = wrapLine(line);
            int drawY = y - (int) scroll;
            if (drawY + wrapped.size() * LINE_H >= CONTENT_TOP && drawY < viewportBottom) {
                int cy = drawY;
                for (FormattedCharSequence part : wrapped) {
                    if (cy >= CONTENT_TOP - LINE_H && cy < viewportBottom) {
                        // 颜色由文本样式决定（fallback 白色），阴影提升可读性
                        g.drawString(this.font, part, 8, cy, 0xFFFFFFFF, true);
                    }
                    cy += LINE_H;
                }
            }
            y += wrapped.size() * LINE_H;
        }
        g.disableScissor();
        // 滚动条（内容超出一屏时显示）
        int sbX = width - SCROLLBAR_RIGHT;
        int max = maxScroll();
        if (max > 0) {
            g.fill(sbX, trackTop(), sbX + SCROLLBAR_W, trackBottom(), 0x33000000);
            g.fill(sbX, thumbTop(), sbX + SCROLLBAR_W, thumbTop() + thumbHeight(), 0xCCFFFFFF);
        }
        UnoGui.centeredShadow(g, this.font, width, "滚轮滚动 / 拖拽滚动条，Esc 返回", height - 16, 0xFF888888);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= (float) verticalAmount * 10;
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= width - SCROLLBAR_RIGHT && mouseX <= width - SCROLLBAR_RIGHT + SCROLLBAR_W) {
            int top = thumbTop();
            if (mouseY >= top && mouseY <= top + thumbHeight()) {
                draggingScrollbar = true;
                dragOffset = mouseY - top;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            int usable = trackHeight() - thumbHeight();
            if (usable > 0) {
                double pos = mouseY - trackTop() - dragOffset;
                scroll = (float) (maxScroll() * pos / usable);
                clampScroll();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
}
