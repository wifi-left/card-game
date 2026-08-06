package io.wifi.cards.common.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 规则/历史等子界面基类（纯客户端）：从对局界面打开时渲染对局界面为背景、
 * 返回时回到父级；无父级（从大厅打开）时返回各游戏大厅。
 * <p>
 * 统一提供：
 * </p>
 * <ul>
 * <li>parent 渲染为背景 + onClose 返回父级/大厅（fallbackScreen）</li>
 * <li>isFromGame()：是否从对局界面打开（斗地主 BGM 归属判断用，见 DdzGameScreen.tickBgm）</li>
 * <li>内容滚动全套：滚轮滚动、滚动条（按下滑块拖拽），几何与文案统一</li>
 * <li>返回按钮（底部居中）</li>
 * <li>renderBackground 覆写为空：父级内容直接作为背景（不画原版暗色菜单背景）</li>
 * </ul>
 * 子类只提供：标题、行内容构建（buildContent）、内容区渲染（renderContent）、
 * 内容总高（contentHeight）与无父级时的返回目标（fallbackScreen）。
 */
public abstract class AbstractSubScreen extends Screen {
    /** 内容区顶部 y（标题条之下）。 */
    protected static final int CONTENT_TOP = 30;
    /** 内容行高。 */
    protected static final int LINE_H = 10;
    /** 底部提示条高。 */
    protected static final int BOTTOM_BAR = 30;
    /** 滚动条距右缘的距离与宽度。 */
    protected static final int SCROLLBAR_RIGHT = 8;
    protected static final int SCROLLBAR_W = 3;

    /** 父级界面（从对局界面打开时返回对局界面，并渲染父级内容为背景）；null 时返回大厅。 */
    protected final Screen parent;
    /** 是否从对局界面打开（背景音乐归属判断用）。 */
    private final boolean fromGame;

    /** 内容区滚动偏移（≥0，滚轮/拖拽滚动条）。 */
    protected float scroll;
    /** 是否正在拖拽滚动条滑块。 */
    private boolean draggingScrollbar;
    /** 按下时鼠标相对滑块顶部的偏移。 */
    private double dragOffset;

    protected AbstractSubScreen(Screen parent, String title) {
        super(Component.literal(title));
        this.parent = parent;
        this.fromGame = parent instanceof AbstractGameScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：父级内容直接作为背景，不画原版暗色菜单背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    public void renderOriginalBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
    }

    /** 是否从对局界面打开（背景音乐归属判断用）。 */
    public boolean isFromGame() {
        return fromGame;
    }

    // ---------------- 滚动计算 ----------------

    protected int trackTop() {
        return CONTENT_TOP;
    }

    protected int trackBottom() {
        return height - BOTTOM_BAR;
    }

    protected int trackHeight() {
        return trackBottom() - trackTop();
    }

    /** 内容总高（行数 × 行高，子类提供）。 */
    protected abstract int contentHeight();

    protected int maxScroll() {
        return Math.max(0, contentHeight() - trackHeight());
    }

    protected int thumbHeight() {
        int track = trackHeight();
        int content = contentHeight();
        return Math.max(12, track * Math.min(track, content) / Math.max(content, track));
    }

    protected int thumbTop() {
        int max = maxScroll();
        if (max <= 0) {
            return trackTop();
        }
        return trackTop() + (int) ((trackHeight() - thumbHeight()) * scroll / max);
    }

    protected void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    // ---------------- init / 返回 ----------------

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> this.onClose())
                .bounds(width / 2 - 40, height - BOTTOM_BAR + 4, 80, 20).build());
        buildContent();
    }

    /** 子类：构建内容（如规则行/历史行文本）。 */
    protected abstract void buildContent();

    /** 返回：有父级（对局界面/大厅）则回到父级，否则回大厅。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent != null ? parent : fallbackScreen());
    }

    /** 无父级打开时的返回目标（各游戏大厅）。 */
    protected abstract Screen fallbackScreen();

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有父级时先渲染父级内容为背景（参考 CardGameChatScreen），再绘制本界面
        if (parent != null) {
            parent.render(g, 0, 0, partialTick);
        }
        // 每帧校准滚动偏移：窗口 resize 后轨道变高、maxScroll 变小，残留的 scroll 会越界
        // （内容底部留白、滑块画出轨道），这里统一收敛到合法区间
        clampScroll();
        // 底部提示条背景（先画，返回按钮绘制在其上）
        g.fill(0, height - BOTTOM_BAR, width, height, 0x44000000);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        renderContent(g, mouseX, mouseY, partialTick);
    }

    /** 子类：标题条 + 内容区 + 滚动文本 + 滚动条 + 底部提示（super.render 之后调用，绘制在按钮之上）。 */
    protected abstract void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    /** 绘制滚动条（内容超出一屏时显示；轨道 + 滑块）。 */
    protected void drawScrollbar(GuiGraphics g) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int sbX = width - SCROLLBAR_RIGHT;
        g.fill(sbX, trackTop(), sbX + SCROLLBAR_W, trackBottom(), 0x33000000); // 轨道
        g.fill(sbX, thumbTop(), sbX + SCROLLBAR_W, thumbTop() + thumbHeight(), 0xCCFFFFFF); // 滑块
    }

    /** 底部提示文字（子类渲染内容时调用，统一文案）。 */
    protected void drawScrollHint(GuiGraphics g) {
        g.drawCenteredString(this.font, "滚轮滚动 / 拖拽滚动条，Esc 返回", width / 2, height - 16, 0xFF888888);
    }

    // ---------------- 滚动交互 ----------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= (float) verticalAmount * 10;
        clampScroll();
        return true;
    }

    /** 按下滑块进入拖拽（轨道点击不跳转）。 */
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
