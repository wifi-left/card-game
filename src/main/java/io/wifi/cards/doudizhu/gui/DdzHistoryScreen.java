package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.common.client.AbstractSubScreen;
import io.wifi.cards.doudizhu.gui.DdzClientState.HistoryLine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 出牌历史界面：文本记录（"XXX 出了 333 5（三带一）"），最新在前。
 * <ul>
 * <li>内容超出一屏时可滚动（滚轮 + 右侧可拖拽滚动条，同规则界面）</li>
 * <li>出牌玩家、牌、牌型三色高亮</li>
 * <li>有父级（打牌界面）时渲染父级内容为背景，返回时回到父级（参考 DdzChatScreen）</li>
 * </ul>
 */
public class DdzHistoryScreen extends AbstractSubScreen {

    public DdzHistoryScreen(Screen parent) {
        super(parent, "出牌历史");
    }

    // ---------------- 滚动计算（与规则界面一致） ----------------

    private List<HistoryLine> lines() {
        return DdzClientState.INSTANCE.historyLines;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderOriginalBackground(g, mouseX, mouseY, partialTick);
    }
    // ---------------- 文本构建（Component 样式着色 + font.split 换行） ----------------

    /** 文本区可用宽度（右侧留出滚动条）。 */
    private int maxWidth() {
        return Math.max(80, width - SCROLLBAR_RIGHT - 16);
    }

    /** 构建一行历史文本：玩家（金）+" 出了 "+牌（青）+"（"+牌型（绿）+")"；"不出"行只高亮玩家名。 */
    private MutableComponent buildLineText(HistoryLine line) {
        MutableComponent text = Component.literal(line.name()).withStyle(ChatFormatting.GOLD);
        if (line.pass()) {
            return text.append(Component.literal(" 不出").withStyle(ChatFormatting.GRAY));
        }
        return text
                .append(Component.literal(" 出了 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(line.cardsText()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("（").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(line.typeName()).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("）").withStyle(ChatFormatting.GRAY));
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

    /** 内容总高（行数 × 行高；历史界面按换行后行数累计）。 */
    @Override
    protected int contentHeight() {
        return totalHeight();
    }

    /** 无父级打开时的返回目标（大厅）。 */
    @Override
    protected Screen fallbackScreen() {
        return new DdzLobbyScreen();
    }

    @Override
    protected void buildContent() {
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, "出牌历史", width / 2, 9, 0xFFFFD700);
        // 内容区半透明黑底（仅在有内容处），滚动文本绘制在其上
        g.fill(0, CONTENT_TOP, width, height - BOTTOM_BAR, 0x44000000);
        g.enableScissor(0, CONTENT_TOP, width, height - BOTTOM_BAR);
        int y = CONTENT_TOP;
        int viewportBottom = height - BOTTOM_BAR;
        List<HistoryLine> history = lines();
        for (HistoryLine line : history) {
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
        drawScrollbar(g);
        drawScrollHint(g);
    }

}
