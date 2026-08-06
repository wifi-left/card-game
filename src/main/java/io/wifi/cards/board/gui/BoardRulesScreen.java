package io.wifi.cards.board.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 棋类规则介绍界面（黑白棋/五子棋/围棋 + 房间与托管说明）。
 * 内容超出一屏时可滚动查看（滚轮 + 右侧可拖拽滚动条），Esc 或"返回"按钮退出。
 * 从大厅打开时返回大厅；从棋盘界面打开时返回棋盘界面（渲染父级内容为背景）。
 */
public class BoardRulesScreen extends Screen {
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
    /** 父级界面（从棋盘界面打开时返回棋盘界面，并渲染父级内容为背景）；null 时返回大厅。 */
    private final Screen parent;

    public BoardRulesScreen() {
        this(null);
    }

    public BoardRulesScreen(Screen parent) {
        super(Component.literal("棋类规则"));
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

    /** 返回：有父级（棋盘界面）则回到父级，否则回大厅。 */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent != null ? parent : new BoardLobbyScreen());
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
        BoardGui.centeredShadow(g, this.font, width, "棋类规则", 9, 0xFFFFD700);
        // 内容区半透明黑底，滚动文本绘制在其上
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
        BoardGui.centeredShadow(g, this.font, width, "滚轮滚动 / 拖拽滚动条，Esc 返回", height - 16, 0xFF888888);
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
