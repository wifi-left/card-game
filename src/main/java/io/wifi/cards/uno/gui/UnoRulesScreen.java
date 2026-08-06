package io.wifi.cards.uno.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则介绍界面：玩法、出牌、功能牌、抽牌、UNO 喊牌、托管与结算说明。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 */
public class UnoRulesScreen extends Screen {
    private static final int CONTENT_TOP = 30;
    private static final int LINE_H = 10;
    private static final int BOTTOM_BAR = 30;
    /** 滚动条距右缘的距离与宽度。 */
    private static final int SCROLLBAR_RIGHT = 8;
    private static final int SCROLLBAR_W = 3;

    private final List<String> lines = new ArrayList<>();
    private float scroll;
    /** 是否正在拖拽滚动条滑块。 */
    private boolean draggingScrollbar;
    /** 按下时鼠标相对滑块顶部的偏移。 */
    private double dragOffset;
    /** 父级界面（从打牌界面打开时返回打牌界面，并渲染父级内容为背景）；null 时返回大厅。 */
    private final Screen parent;

    public UnoRulesScreen() {
        this(null);
    }

    public UnoRulesScreen(Screen parent) {
        super(Component.literal("UNO 规则"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------- 滚动计算 ----------------

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
        return Math.max(0, lines.size() * LINE_H - trackHeight());
    }

    private int thumbHeight() {
        int track = trackHeight();
        int content = lines.size() * LINE_H;
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

    @Override
    protected void init() {
        buildLines();
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

    private void buildLines() {
        lines.add("【玩法】2~10 人对局，房主点击「开始游戏」开局（至少 2 人）。");
        lines.add("每人 7 张手牌，翻一张起牌（须为数字牌），先出完手牌者获胜。");
        lines.add("");
        lines.add("【出牌】与顶牌同色或同点数可打（如顶牌是红 5，");
        lines.add("可打任意红牌或任意 5）；万能牌任意可打，打出前选择颜色。");
        lines.add("");
        lines.add("【功能牌】");
        lines.add("· 跳过：下家被跳过");
        lines.add("· 反转：出牌方向反转（2 人局视为跳过下家）");
        lines.add("· +2：下家罚抽 2 张并跳过");
        lines.add("· 万能：打出的同时选择一个颜色");
        lines.add("· 万能+4：选一个颜色，下家罚抽 4 张并跳过");
        lines.add("");
        lines.add("【抽牌】无牌可打时点「抽牌」（或点击牌堆）抽 1 张；");
        lines.add("抽到可打的牌可选择打出或跳过，抽到不可打的牌自动跳过；");
        lines.add("牌堆抽空后把弃牌堆洗回（保留顶牌）。");
        lines.add("");
        lines.add("【UNO 喊牌】打出倒数第二张（手牌剩 1 张）后必须点「喊 UNO！」；");
        lines.add("未喊且被其他玩家点「抓 XX UNO」抓住，罚 2 张（抓成功后窗口关闭）；");
        lines.add("无人抓时轮到自己自动罚 2 张（聊天栏 + 牌桌提示）；");
        lines.add("打出最后一张立即获胜（胜后不可被抓）。");
        lines.add("");
        lines.add("【托管与超时】可随时开启托管（自动出牌/选色/喊牌）；");
        lines.add("出牌时限 30 秒，超时自动抽牌（抽到可打则自动打出）。");
        lines.add("");
        lines.add("【结算】单局制，先出完手牌者获胜；");
        lines.add("结算界面可「再来一局」（房间不散直接重开）。");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有父级时先渲染父级内容为背景（参考斗地主子界面），再绘制本界面
        if (parent != null) {
            parent.render(g, 0, 0, partialTick);
        }
        // 底部提示条背景（先画，返回按钮绘制在其上）
        g.fill(0, height - BOTTOM_BAR, width, height, 0x44000000);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, 26, 0x66000000);
        UnoGui.centeredShadow(g, this.font, width, "UNO 规则", 9, 0xFFFFD700);
        // 内容区半透明黑底（仅在有内容处），滚动文本绘制在其上
        g.fill(0, CONTENT_TOP, width, height - BOTTOM_BAR, 0x44000000);
        g.enableScissor(0, CONTENT_TOP, width, height - BOTTOM_BAR);
        int y = CONTENT_TOP;
        int viewportBottom = height - BOTTOM_BAR;
        for (String line : lines) {
            int drawY = y - (int) scroll;
            if (drawY >= CONTENT_TOP - LINE_H && drawY < viewportBottom) {
                g.drawString(this.font, line, 8, drawY, 0xFFDDDDDD, true);
            }
            y += LINE_H;
        }
        g.disableScissor();
        // 滚动条（内容超出一屏时显示）
        int sbX = width - SCROLLBAR_RIGHT;
        int max = maxScroll();
        if (max > 0) {
            g.fill(sbX, trackTop(), sbX + SCROLLBAR_W, trackBottom(), 0x33000000); // 轨道
            g.fill(sbX, thumbTop(), sbX + SCROLLBAR_W, thumbTop() + thumbHeight(), 0xCCFFFFFF); // 滑块
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
