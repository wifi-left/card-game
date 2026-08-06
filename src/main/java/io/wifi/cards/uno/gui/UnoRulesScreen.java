package io.wifi.cards.uno.gui;

import io.wifi.cards.common.client.AbstractSubScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则介绍界面：玩法、出牌、功能牌、抽牌、UNO 喊牌、托管与结算说明。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 */
public class UnoRulesScreen extends AbstractSubScreen {
    private final List<String> lines = new ArrayList<>();

    public UnoRulesScreen() {
        this(null);
    }

    public UnoRulesScreen(Screen parent) {
        super(parent, "UNO 规则");
    }


    // ---------------- 滚动计算 ----------------





    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderOriginalBackground(g, mouseX, mouseY, partialTick);
    }




    /** 内容总高（行数 × 行高；历史界面按换行后行数累计）。 */
    @Override
    protected int contentHeight() {
        return lines.size() * LINE_H;
    }

    /** 无父级打开时的返回目标（大厅）。 */
    @Override
    protected Screen fallbackScreen() {
        return new UnoLobbyScreen();
    }

    @Override
    protected void buildContent() {
        lines.clear(); // resize（窗口/全屏变化）会再次调用 init→buildContent，先清空防内容翻倍
        buildLines();
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
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, "UNO 规则", width / 2, 9, 0xFFFFD700);
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
        drawScrollbar(g);
        drawScrollHint(g);
    }




}
