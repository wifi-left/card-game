package io.wifi.cards.doudizhu.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则介绍界面：玩法、牌型、大小、花牌模式差异、流程与结算说明。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 */
public class DdzRulesScreen extends Screen {
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

    public DdzRulesScreen() {
        this(null);
    }

    public DdzRulesScreen(Screen parent) {
        super(Component.literal("斗地主规则"));
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

    /** 是否从打牌界面打开（背景音乐归属判断用）。 */
    public boolean isFromGame() {
        return parent instanceof DdzGameScreen;
    }

    /** 返回：有父级（打牌界面）则回到父级，否则回大厅。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent != null ? parent : new DdzLobbyScreen());
    }

    private void buildLines() {
        lines.add("【玩法】3 人对局：地主 20 张（含 3 张底牌），农民各 17 张。");
        lines.add("地主单独对抗两名农民，先出完手牌的一方获胜。");
        lines.add("");
        lines.add("【大小】3<4<5<6<7<8<9<10<J<Q<K<A<2<小王<大王");
        lines.add("");
        lines.add("【牌型】（标准规则）");
        lines.add("· 单牌 / 对子 / 三张");
        lines.add("· 三带一：333+5（比三张部分）");
        lines.add("· 三带二：333+55（民间规则无此牌型）");
        lines.add("· 顺子：5 张起连续，不含 2 与王，最大 10JQKA");
        lines.add("· 连对：3 对起连续，不含 2");
        lines.add("· 飞机：2 组起连续三张，不含 2（如 333444）");
        lines.add("· 飞机带翅膀：飞机 + 同数量单牌或对子（民间规则仅单牌）");
        lines.add("· 四带二：4 张 + 两张单牌，或 4 张 + 一对/两对（民间规则仅两张单牌）");
        lines.add("· 炸弹：4 张同值；火箭：大小王（全场最大）");
        lines.add("· 不存在“三王炸”牌型");
        lines.add("");
        lines.add("【规则集】创建房间时选择，本局生效：");
        lines.add("· 标准规则：三带二、飞机带对子、四带两对均允许");
        lines.add("· 民间规则：无三带二、无飞机带对子、无四带两对");
        lines.add("");
        lines.add("【压制】同牌型且张数相同才可压（比最大牌点）；");
        lines.add("四带二不可互压；炸弹可压任何一般牌型；");
        lines.add("火箭 > 炸弹；炸弹互压比点数。");
        lines.add("");
        lines.add("【花牌模式（万能牌）】55 张牌、底牌 4 张。");
        lines.add("花牌可当作任意一张牌（3~大王）参与组合；");
        lines.add("花牌 + 三张同值 = 含花牌炸弹（等于炸弹）；");
        lines.add("花牌模式不允许三带二（四带二、飞机带翅膀、裸飞机均允许）。");
        lines.add("");
        lines.add("【流程】发牌后随机开始叫分：不叫/1/2/3（须更高）。");
        lines.add("有人叫 3 分 → 抢地主：轮流抢/不抢，");
        lines.add("连续 2 人不抢即终止，底分固定 3 分；");
        lines.add("无人叫 3 分则最高分者当地主（底分 1 或 2 分），");
        lines.add("全不叫则重新发牌。");
        lines.add("地主先出牌，轮到你可压牌或不出；");
        lines.add("另外两家都不出后，上一家可自由出任意牌型。");
        lines.add("");
        lines.add("【结算】底分 × 倍数：炸弹/火箭/含花牌炸弹当场倍数×2。");
        lines.add("地主胜：地主 +2×底分×倍数，农民各 -底分×倍数；");
        lines.add("地主败则相反。分数仅本局展示，不保存。");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有父级时先渲染父级内容为背景（参考 DdzChatScreen），再绘制本界面
        if (parent != null) {
            parent.render(g, 0, 0, partialTick);
        }
        // 底部提示条背景（先画，返回按钮绘制在其上）
        g.fill(0, height - BOTTOM_BAR, width, height, 0x44000000);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, 26, 0x66000000);
        DdzGui.centeredShadow(g, this.font, width, "斗地主规则", 9, 0xFFFFD700);
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
        DdzGui.centeredShadow(g, this.font, width, "滚轮滚动 / 拖拽滚动条，Esc 返回", height - 16, 0xFF888888);
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
