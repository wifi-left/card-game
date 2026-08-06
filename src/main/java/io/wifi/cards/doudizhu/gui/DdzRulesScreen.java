package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.common.client.AbstractSubScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则介绍界面：玩法、牌型、大小、花牌模式差异、流程与结算说明。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 * 原版模糊背景，以确保看得见文本
 */
public class DdzRulesScreen extends AbstractSubScreen {
    private final List<String> lines = new ArrayList<>();

    public DdzRulesScreen() {
        this(null);
    }

    public DdzRulesScreen(Screen parent) {
        super(parent, "斗地主规则");
    }

    // ---------------- 滚动计算 ----------------

    /** 内容总高（行数 × 行高；历史界面按换行后行数累计）。 */
    @Override
    protected int contentHeight() {
        return lines.size() * LINE_H;
    }

    /** 无父级打开时的返回目标（大厅）。 */
    @Override
    protected Screen fallbackScreen() {
        return new DdzLobbyScreen();
    }

    @Override
    protected void buildContent() {
        lines.clear(); // resize（窗口/全屏变化）会再次调用 init→buildContent，先清空防内容翻倍
        buildLines();
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
        lines.add("不允许出单张花牌（花牌必须与其他牌组合）；");
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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有父级时先渲染父级内容为背景（参考 DdzChatScreen），再绘制本界面
        // g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, "斗地主规则", width / 2, 9, 0xFFFFD700);
        // 内容区半透明黑底（仅在有内容处），滚动文本绘制在其上
        // g.fill(0, CONTENT_TOP, width, height - BOTTOM_BAR, 0x44000000);
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
        drawScrollbar(g);
        drawScrollHint(g);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderOriginalBackground(g, mouseX, mouseY, partialTick);
    }

}
