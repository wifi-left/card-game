package io.wifi.cards.board.gui;

import io.wifi.cards.common.client.AbstractSubScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 棋类规则介绍界面（黑白棋/五子棋/围棋 + 房间与托管说明）。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 * 从大厅打开时返回大厅；从棋盘界面打开时返回棋盘界面（渲染父级内容为背景）。
 * 
 * 原版模糊背景，以确保看得见文本
 */
public class BoardRulesScreen extends AbstractSubScreen {
    private final List<String> lines = new ArrayList<>();

    public BoardRulesScreen() {
        this(null);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderOriginalBackground(g, mouseX, mouseY, partialTick);
    }

    public BoardRulesScreen(Screen parent) {
        super(parent, "棋类规则");
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
        return new BoardLobbyScreen();
    }

    @Override
    protected void buildContent() {
        lines.clear(); // resize（窗口/全屏变化）会再次调用 init→buildContent，先清空防内容翻倍
        buildLines();
    }

    private void buildLines() {
        lines.add("【房间与对局】2 人对战：黑白棋 8×8、五子棋 15×15、围棋 9/19 路。");
        lines.add("满 2 人自动开局；黑方（房主）先手，轮流落子。");
        lines.add("对局开始后可旁观（大厅列表 / 聊天栏公告 / /board spectate）。");
        lines.add("每步限时 60 秒：五子棋/黑白棋超时由 AI 托管走一步，");
        lines.add("围棋超时直接跳过轮到对方。可随时认输。");
        lines.add("对局中退出：五子棋/黑白棋座位转机器人托管继续；");
        lines.add("围棋无托管，退出/断线直接结束本局（对方获胜）。");
        lines.add("断线重连自动恢复完整对局状态（五子棋/黑白棋；");
        lines.add("围棋断线即结束本局，重连时房间已关闭）；");
        lines.add("结算后可再来一局或返回大厅。");
        lines.add("调试：/board debug ui 可打开随机虚拟对局检查旁观界面。");
        lines.add("");
        lines.add("【黑白棋（8×8）】黑先。");
        lines.add("落子在空格，横/竖/斜方向夹住对方棋子则整串翻转。");
        lines.add("无合法落点时自动停一手换边；双方均无落点则终局，");
        lines.add("棋子多者获胜，同数平局。");
        lines.add("轮到你的合法落点会以白点提示。");
        lines.add("");
        lines.add("【五子棋（15×15）】黑先。");
        lines.add("先连成五子（横/竖/斜）者获胜；棋盘下满则平局。");
        lines.add("无禁手规则。");
        lines.add("");
        lines.add("【围棋（9/19 路）】黑先，落子于交叉点。");
        lines.add("· 气：棋子相邻的空交叉点；无气的棋块被提走。");
        lines.add("· 禁自杀：落子后自身无气且未提掉对方为非法。");
        lines.add("· 打劫：禁止立即提回形成重复局面（简单劫）。");
        lines.add("· 停一手：轮到你可停一手；双方连续停一手即终局。");
        lines.add("· 连续 4 手无人落子（超时/挂机）自动按当前局面终局。");
        lines.add("· 数子（中国规则）：黑贴 3.75 子，");
        lines.add("  黑子+领地超过总交叉点一半加贴子获胜。");
        lines.add("· 无 AI：不提供机器人陪练；退出/断线直接结束本局。");
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, "棋类规则", width / 2, 9, 0xFFFFD700);
        // 内容区半透明黑底，滚动文本绘制在其上
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

}
