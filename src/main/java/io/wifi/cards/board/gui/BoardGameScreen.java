package io.wifi.cards.board.gui;

import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.LeaveRoomC2S;
import io.wifi.cards.board.network.BoardPackets.MoveC2S;
import io.wifi.cards.board.network.BoardPackets.NextGameC2S;
import io.wifi.cards.board.network.BoardPackets.PassC2S;
import io.wifi.cards.board.network.BoardPackets.SpectateLeaveC2S;
import io.wifi.cards.board.network.BoardPackets.SurrenderC2S;
import io.wifi.cards.board.othello.rule.OthelloRules;
import io.wifi.cards.common.client.AbstractGameScreen;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

/**
 * 棋类共用棋盘界面（黑白棋/五子棋/围棋共用一个 Screen，按游戏类型分支渲染；
 * 继承 {@link AbstractGameScreen}）：
 * <ul>
 *   <li><b>自适应窗口缩放</b>：棋盘以固定逻辑尺寸（格 28px + 边距）绘制，整体经
 *       {@code gui.pose().translate/scale} 缩放到可用矩形（顶部信息条之下、右侧按钮区左侧），
 *       scale 上限 1.5（窗口够大不再放大）、无下限——恒 ≤ 可用矩形比例，
 *       任何窗口尺寸下棋盘都不会溢出顶栏/按钮（小窗口棋盘变小但完整可见、命中准确）；
 *       鼠标点击按最近一帧的 scale/offset 逆变换回逻辑坐标，小窗口下命中依然准确。</li>
 *   <li>顶部信息条（固定像素不缩放）：双方玩家（头像/名字/黑或白标记）、回合高亮、倒计时、最近动作</li>
 *   <li>右下按钮（固定像素不缩放）：退出（对局中转托管）/ 认输 / 停一手（仅围棋）/ 再来一局 / 返回大厅 / 退出旁观</li>
 *   <li>分支渲染：黑白棋=合法落点提示；五子棋=最后一手红点标记；围棋=星位 + 悬停预览</li>
 *   <li>结算以横幅展示（棋盘保留，终局局面可见）</li>
 * </ul>
 */
public class BoardGameScreen extends AbstractGameScreen {
    // 逻辑格子尺寸与棋盘边距（棋盘绘制在逻辑坐标，整体随窗口缩放）
    private static final int CELL = 28;
    private static final int MARGIN = 14;
    // 固定像素布局：顶部信息条、右侧按钮区（不参与缩放）
    private static final int TOP_BAR = 54;
    private static final int RIGHT_PANEL_W = 116;
    /** 棋盘缩放上限：窗口够大放至 1.5 不再放大。无固定下限——
     *  scale 恒 ≤ 可用矩形比例，任何窗口尺寸下棋盘都不会溢出顶栏/按钮（小窗口棋盘变小但完整可见、命中准确）。 */
    private static final float MAX_SCALE = 1.5F;

    /** 最近一帧的棋盘变换（render 计算，鼠标逆变换用）。 */
    private float boardScale = 1F;
    private float boardOffsetX;
    private float boardOffsetY;
    /** 悬停格（落点预览），-1=无。 */
    private int hoverX = -1;
    private int hoverY = -1;

    public BoardGameScreen() {
        super("棋牌对局");
        countdown = 60; // 棋类回合默认 60 秒（基类默认 30）
    }

    /** 旁观模式：服务端以 mySeat=-1 表示只读旁观（无操作权）。 */
    @Override
    protected boolean isSpectator() {
        return BoardClientState.INSTANCE.mySeat < 0;
    }

    @Override
    protected long turnEndGameTime() {
        return BoardClientState.INSTANCE.turnEndGameTime;
    }

    @Override
    protected void reopenHint() {
        BoardClientState.chatReopenHint("关闭对局界面");
    }

    @Override
    protected String exitConfirmFirstLine() {
        // 围棋无托管：退出直接结束本局
        return BoardClientState.INSTANCE.gameType == BoardGameType.GO
                ? "退出将直接结束本局（围棋无托管）"
                : "退出后座位将由托管代打，对局继续";
    }

    /** 关闭前：调试旁观模式清标记且不提示（屏幕仍正常关闭）。 */
    @Override
    protected boolean handleCloseRequest() {
        if (BoardClientState.INSTANCE.debugMode) {
            BoardClientState.INSTANCE.debugMode = false;
            return true;
        }
        return false;
    }

    /** resize 后立即重算棋盘变换，避免首帧点击仍用旧变换命中错位。 */
    @Override
    protected void onScreenResized() {
        computeBoardTransform();
    }

    @Override
    protected void init() {
        // 左下角：规则介绍（返回时回到本棋盘界面，并渲染本界面为背景）
        addRulesButton(() -> Minecraft.getInstance().setScreen(new BoardRulesScreen(BoardGameScreen.this)));
        rebuildActionButtons();
    }

    /** 每 tick 游戏特有逻辑：等待 TurnS2C 期间重置倒计时 + 签名计算与按钮重建（聊天/倒计时由基类处理）。 */
    @Override
    protected void onTick() {
        BoardClientState s = BoardClientState.INSTANCE;
        if (s.phase == BoardPhase.PLAYING && s.turnEndGameTime == 0) {
            countdown = 60; // 新局/重连等待 TurnS2C 期间重置，避免残留上一局倒计时
        }
        // 阶段/轮到谁/调试模式/退出确认弹层变化时重建按钮（旁观模式按钮恒定，独立签名；
        // 含 debugMode：调试界面转真实旁观后"关闭"按钮须替换为"退出旁观"）
        int signature = (s.phase.ordinal() * 200 + (s.currentSeat + 1) * 20 + (s.mySeat >= 0 ? 2 : 0)
                + (s.debugMode ? 1 : 0)) * 2 + (confirmingExit ? 1 : 0);
        rebuildButtonsIfChanged(signature);
    }

    // ---------------- 棋盘几何（缩放自适应） ----------------

    private int size() {
        return BoardClientState.INSTANCE.size;
    }

    /** 棋盘逻辑边长（含边距），绘制与命中均在逻辑坐标进行。 */
    private int boardPx() {
        return (size() - 1) * CELL + MARGIN * 2;
    }

    /**
     * 计算棋盘渲染变换（每帧 render 时更新，供绘制与鼠标逆变换共用）：
     * 可用矩形 = 顶部信息条之下、右侧按钮区左侧；scale 取能容纳棋盘的最大比例
     * （上限 MAX_SCALE，无下限），棋盘恒在可用矩形内居中——永不溢出/遮挡顶栏与按钮。
     */
    private void computeBoardTransform() {
        int availW = Math.max(40, width - 16 - RIGHT_PANEL_W);
        int availH = Math.max(40, height - TOP_BAR - 8 - 60);
        int bp = boardPx();
        boardScale = Math.min(Math.min((float) availW / bp, (float) availH / bp), MAX_SCALE);
        float bw = bp * boardScale;
        boardOffsetX = 8 + (availW - bw) / 2F;
        boardOffsetY = TOP_BAR + 4 + (availH - bw) / 2F;
    }

    /**
     * 鼠标坐标（GUI 缩放坐标）→ 格坐标：按最近一帧的棋盘变换逆变换回逻辑坐标。
     * 点击位置距格中心超过 0.45 格视为无效（返回 null），小窗口下命中依然准确。
     */
    private int[] cellAt(double mouseX, double mouseY) {
        double lx = (mouseX - boardOffsetX) / boardScale;
        double ly = (mouseY - boardOffsetY) / boardScale;
        int s = size();
        int x = (int) Math.round((lx - MARGIN) / CELL);
        int y = (int) Math.round((ly - MARGIN) / CELL);
        if (x < 0 || x >= s || y < 0 || y >= s) {
            return null;
        }
        double dx = Math.abs(lx - (MARGIN + x * CELL));
        double dy = Math.abs(ly - (MARGIN + y * CELL));
        if (Math.max(dx, dy) > CELL * 0.45) {
            return null;
        }
        return new int[]{x, y};
    }

    // ---------------- 交互 ----------------

    /** 该格是否为空（占用格不可落子）。带 x/y 范围校验：调试模式切换棋盘尺寸后
     *  残留的 hover 格可能越界（如 19 路 → 9 路时 hoverX=18），必须拒绝防数组越界。 */
    private boolean boardEmptyAt(int x, int y) {
        BoardClientState s = BoardClientState.INSTANCE;
        int sz = size();
        return x >= 0 && x < sz && y >= 0 && y < sz
                && s.board.length == sz * sz && s.board[y * sz + x] == 0;
    }

    /** 该格当前是否可落子：空位；黑白棋还需是合法落点（与服务端校验一致）。 */
    private boolean canMoveAt(int x, int y) {
        BoardClientState s = BoardClientState.INSTANCE;
        if (!boardEmptyAt(x, y)) {
            return false;
        }
        if (s.gameType == BoardGameType.OTHELLO) {
            for (int[] m : OthelloRules.legalMoves(s.board, size(), (byte) (s.mySeat + 1))) {
                if (m[0] == x && m[1] == y) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        BoardClientState s = BoardClientState.INSTANCE;
        // 退出确认弹层中：不落子，仅响应确认/取消按钮（super 转发给 widget）
        if (confirmingExit) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 轮到我且对局中：点击棋盘格 → 落子（坐标在服务端再次校验）
        if (button == 0 && s.phase == BoardPhase.PLAYING && s.isMyTurn()) {
            int[] cell = cellAt(mouseX, mouseY);
            if (cell != null && canMoveAt(cell[0], cell[1])) {
                ClientPlayNetworking.send(new MoveC2S((byte) cell[0], (byte) cell[1]));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 悬停：轮到本人时更新预览格（渲染半透明棋子提示落点）。退出确认弹层中不更新。 */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        BoardClientState s = BoardClientState.INSTANCE;
        if (!confirmingExit && s.phase == BoardPhase.PLAYING && s.isMyTurn()) {
            int[] cell = cellAt(mouseX, mouseY);
            if (cell != null && canMoveAt(cell[0], cell[1])) {
                hoverX = cell[0];
                hoverY = cell[1];
                return;
            }
        }
        hoverX = -1;
        hoverY = -1;
        super.mouseMoved(mouseX, mouseY);
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        computeBoardTransform(); // 先算变换：本帧绘制与鼠标命中使用同一份
        super.render(g, mouseX, mouseY, partialTick);
        drawTopInfo(g);
        drawBoard(g);
        drawResultBanner(g);
        if (confirmingExit) {
            drawExitConfirm(g); // 退出确认弹层最后画，盖在最上层
        }
    }



    private void drawTopInfo(GuiGraphics g) {
        BoardClientState s = BoardClientState.INSTANCE;
        // 顶部信息条（固定像素，不随棋盘缩放）
        g.fill(0, 0, width, TOP_BAR, 0x66000000);
        g.fill(0, TOP_BAR - 1, width, TOP_BAR, 0xFFB08A3B); // 底部金色装饰线
        // 左：座位 0（黑方）；名字截断防窄窗口与中央标题重叠
        drawHead(g, s.playerUuids[0], 6, 6, 16);
        String leftText = truncateName(s.names[0].isEmpty() ? "等待加入…" : s.names[0]) + "（黑）";
        g.drawString(this.font, leftText, 26, 11, s.currentSeat == 0 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 右：座位 1（白方，右对齐）
        drawHead(g, s.playerUuids[1], width - 22, 6, 16);
        String rightText = truncateName(s.names[1].isEmpty() ? "等待加入…" : s.names[1]) + "（白）";
        g.drawString(this.font, rightText, width - this.font.width(rightText) - 26, 11,
                s.currentSeat == 1 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 中央：游戏名 + 阶段（调试旁观模式标题带"（调试）"标记）
        String title = s.gameType.displayName
                + (s.gameType == BoardGameType.GO ? " " + s.size + " 路" : "")
                + " · " + phaseText();
        if (s.debugMode) {
            title = "（调试）" + title;
        }
        BoardGui.centeredShadow(g, this.font, width, title, 8, 0xFFFFD700);
        // 左侧第二行：旁观/自己身份（与中央"轮到…"同一行高度对齐）
        if (isSpectator()) {
            g.drawString(this.font, s.debugMode ? "旁观中（调试数据）" : "旁观中", 6, 32, 0xFFAAAAAA, true);
        } else {
            g.drawString(this.font, "你（" + s.sideName(s.mySeat) + "方）", 6, 32, 0xFFFFFFFF, true);
        }
        // 中央第二/三行：轮到谁 + 倒计时 + 最近动作（与左/右第二行对齐；临期红色警示）
        if (s.phase == BoardPhase.PLAYING) {
            // 截止刻未下发（开局/重连等待 TurnS2C 窗口）时不显示编造的倒计时
            String timeText = s.turnEndGameTime > 0 ? "（剩余 " + Math.max(0, countdown) + " 秒）" : "";
            String turnText = s.isMyTurn()
                    ? "轮到你" + timeText
                    : "轮到 " + s.nameOf(s.currentSeat) + timeText;
            int turnColor;
            if (s.isMyTurn()) {
                turnColor = countdown <= 10 ? 0xFFFF5555 : 0xFFFFFF55;
            } else {
                turnColor = countdown <= 10 ? 0xFFFF8888 : 0xFFAAAAAA;
            }
            BoardGui.centeredShadow(g, this.font, width, turnText, 32, turnColor);
            BoardGui.centeredShadow(g, this.font, width, s.lastAction, 44, 0xFFAAAAAA);
        } else if (s.phase == BoardPhase.SETTLED) {
            BoardGui.centeredShadow(g, this.font, width, s.lastAction, 44, 0xFFAAAAAA);
        }
    }

    private String phaseText() {
        return switch (BoardClientState.INSTANCE.phase) {
            case WAITING -> "等待游戏开始…";
            case PLAYING -> "对局中";
            case SETTLED -> "本局结束";
        };
    }

    /** 玩家名截断（顶部信息条防窄窗口与中央标题重叠）。 */
    private String truncateName(String name) {
        return this.font.plainSubstrByWidth(name, 90);
    }

    /** 绘制棋盘（pushPose → translate → scale → 逻辑坐标绘制 → popPose）。 */
    private void drawBoard(GuiGraphics g) {
        BoardClientState s = BoardClientState.INSTANCE;
        if (s.board.length != size() * size()) {
            return; // 棋盘数据未就绪（如刚打开大厅瞬间）
        }
        int bp = boardPx();
        g.pose().pushPose();
        g.pose().translate(boardOffsetX, boardOffsetY, 0);
        g.pose().scale(boardScale, boardScale, 1);
        // 棋盘底色：纵向渐变（上浅下深的木色，逐行 fill，纯批处理管线）
        int c1 = 0xFFC08A50;
        int c2 = 0xFF8A5A28;
        for (int yy = 0; yy < bp; yy++) {
            float t = (float) yy / bp;
            int rr = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
            int gg = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
            int bb = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
            g.fill(0, yy, bp, yy + 1, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
        }
        // 黑色边框
        g.fill(0, 0, bp, 2, 0xFF000000);
        g.fill(0, bp - 2, bp, bp, 0xFF000000);
        g.fill(0, 0, 2, bp, 0xFF000000);
        g.fill(bp - 2, 0, bp, bp, 0xFF000000);
        // 网格线（黑白棋/五子棋格中心落子，围棋交叉点落子，均以格点为基准）
        int lineEnd = MARGIN + (size() - 1) * CELL;
        for (int i = 0; i < size(); i++) {
            int p = MARGIN + i * CELL;
            g.fill(MARGIN, p, lineEnd + 1, p + 1, 0xFF000000);
            g.fill(p, MARGIN, p + 1, lineEnd + 1, 0xFF000000);
        }
        // 围棋星位
        if (s.gameType == BoardGameType.GO) {
            for (int[] star : goStars()) {
                int sx = MARGIN + star[0] * CELL - 3;
                int sy = MARGIN + star[1] * CELL - 3;
                g.fill(sx, sy, sx + 6, sy + 6, 0xFF000000);
            }
        }
        // 棋子（GuiGraphics.fill 像素圆：与棋盘底同批处理，顶点顺序即绘制顺序，棋子恒在棋盘底之上）
        int stoneR = (int) (CELL * 0.4);
        for (int y = 0; y < size(); y++) {
            for (int x = 0; x < size(); x++) {
                byte v = s.board[y * size() + x];
                if (v == 0) {
                    continue;
                }
                drawStone(g, MARGIN + x * CELL, MARGIN + y * CELL, stoneR,
                        v == 1 ? 0xFF111111 : 0xFFF2F2F2);
            }
        }
        // 最后一手标记（红点，三棋通用）
        if (s.lastMoveX >= 0 && s.lastMoveY >= 0) {
            drawStone(g, MARGIN + s.lastMoveX * CELL, MARGIN + s.lastMoveY * CELL, 5, 0xFFFF3B30);
        }
        // 黑白棋：轮到本人时显示全部合法落点提示
        if (s.gameType == BoardGameType.OTHELLO && s.phase == BoardPhase.PLAYING && s.isMyTurn()) {
            for (int[] m : OthelloRules.legalMoves(s.board, size(), (byte) (s.mySeat + 1))) {
                drawStone(g, MARGIN + m[0] * CELL, MARGIN + m[1] * CELL, 4, 0x66FFFFFF);
            }
        }
        // 悬停预览（轮到本人、可落点；渲染时再次校验，防止新局后残留旧局的 hover 格）
        if (s.phase == BoardPhase.PLAYING && s.isMyTurn() && hoverX >= 0 && hoverY >= 0
                && canMoveAt(hoverX, hoverY)) {
            drawStone(g, MARGIN + hoverX * CELL, MARGIN + hoverY * CELL, stoneR,
                    s.mySeat == 0 ? 0x55111111 : 0x55F2F2F2);
        }
        g.pose().popPose();
    }

    /** 围棋星位（9/13 路 5 星，19 路 9 星；标准星位位置）。 */
    private int[][] goStars() {
        if (size() == 9) {
            return new int[][]{{2, 2}, {2, 6}, {6, 2}, {6, 6}, {4, 4}};
        }
        if (size() == 13) {
            return new int[][]{{3, 3}, {3, 9}, {9, 3}, {9, 9}, {6, 6}};
        }
        return new int[][]{
                {3, 3}, {3, 9}, {3, 15},
                {9, 3}, {9, 9}, {9, 15},
                {15, 3}, {15, 9}, {15, 15}
        };
    }

    /** 绘制一个棋子：方块棋子，实心子先画描边方块（白子黑描边、黑子灰描边）再画本体
     *  （半透明提示/红标不描边）。纯 GuiGraphics.fill 矩形：与棋盘底同批处理，
     *  顶点顺序即绘制顺序，棋子恒在棋盘底之上。 */
    private void drawStone(GuiGraphics g, float cx, float cy, float r, int argb) {
        if (argb == 0xFFF2F2F2 || argb == 0xFF111111) {
            int edge = argb == 0xFF111111 ? 0xFF7A7A7A : 0xFF000000;
            int ex = (int) (cx - r - 1);
            int ey = (int) (cy - r - 1);
            g.fill(ex, ey, (int) (cx + r + 1), (int) (cy + r + 1), edge);
        }
        g.fill((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r), argb);
    }

    /** 结算横幅：紧贴棋盘上缘上方显示结果（胜者/分数/原因），按钮区同步切换为再来一局。
     *  小窗口下棋盘顶部贴近信息条时退到信息条下方一行，保证不遮挡棋盘。 */
    private void drawResultBanner(GuiGraphics g) {
        BoardClientState s = BoardClientState.INSTANCE;
        if (s.phase != BoardPhase.SETTLED) {
            return;
        }
        int cx = width / 2;
        int y = (int) Math.max(TOP_BAR + 8, boardOffsetY - 24);
        String text;
        if (s.winSeat < 0) {
            text = "平局（黑 " + s.blackScore + " · 白 " + s.whiteScore + "）";
        } else if (s.resultReason == 1) {
            text = s.winName + " 获胜（对方认输）";
        } else if (s.resultReason == 2) {
            text = s.winName + " 获胜（对方退出游戏）";
        } else {
            text = s.winName + " 获胜（黑 " + s.blackScore + " · 白 " + s.whiteScore + "）";
        }
        g.fill(cx - 180, y - 4, cx + 180, y + 16, 0xAA000000);
        g.fill(cx - 181, y - 5, cx + 181, y - 4, 0xFFB08A3B); // 横幅金色边框
        g.fill(cx - 181, y + 16, cx + 181, y + 17, 0xFFB08A3B);
        g.fill(cx - 181, y - 5, cx - 180, y + 17, 0xFFB08A3B);
        g.fill(cx + 180, y - 5, cx + 181, y + 17, 0xFFB08A3B);
        BoardGui.centeredShadow(g, this.font, width, text, y, 0xFFFFD700);
    }

    // ---------------- 按钮（右下角，固定像素不缩放） ----------------

    @Override
    protected void rebuildActionButtons() {
        for (Button b : actionButtons) {
            removeWidget(b);
        }
        actionButtons.clear();
        BoardClientState s = BoardClientState.INSTANCE;
        int x = width - RIGHT_PANEL_W + 8;
        int y = height - 150;
        // 单列按钮（宽 90 < 按钮区 116）：棋盘可用矩形（width-16-RIGHT_PANEL_W）整体在按钮区左侧，
        // 棋盘任何尺寸下都不会覆盖按钮（此前双列布局的 x-95 按钮会被大窗口棋盘压住）
        // 旁观模式：只读观看，仅提供「退出旁观」；调试旁观模式（无真实房间）提供「关闭」
        if (isSpectator()) {
            if (s.debugMode) {
                actionButtons.add(button(x, y, "关闭", b -> {
                    BoardClientState.INSTANCE.debugMode = false; // 关闭按钮直接退出调试模式
                    Minecraft.getInstance().setScreen(null);
                }, true));
            } else {
                actionButtons.add(button(x, y, "退出旁观", b -> sendUnspectate(), true));
            }
            return;
        }
        if (s.phase == BoardPhase.PLAYING) {
            if (confirmingExit) {
                // 退出确认弹层：仅「确认退出 / 取消」（确认后座位转托管，对局继续；
                // 围棋无托管，确认后直接结束本局）。回调只改字段，按钮由 tick 签名变化统一重建
                actionButtons.add(button(x, y - 52, "确认退出", b -> {
                    confirmingExit = false;
                    sendLeave();
                }, true));
                actionButtons.add(button(x, y - 26, "取消", b -> confirmingExit = false, true));
                return;
            }
            // 常驻：退出（座位转托管）+ 认输（任意时刻可认输）；围棋轮到本人时提供「停一手」
            actionButtons.add(button(x, y - 52, "退出", b -> confirmingExit = true, true));
            actionButtons.add(button(x, y - 26, "认输", b -> sendSurrender(), true));
            if (s.gameType == BoardGameType.GO && s.isMyTurn()) {
                actionButtons.add(button(x, y, "停一手", b -> sendPass(), true));
            }
        } else if (s.phase == BoardPhase.SETTLED) {
            // 围棋：房间存在退出者转的"（托管）"座位时无法开新局（无 AI），禁用再来一局
            boolean goBlocked = s.gameType == BoardGameType.GO
                    && (s.names[0].endsWith("（托管）") || s.names[1].endsWith("（托管）"));
            actionButtons.add(button(x, y - 26, "返回大厅", b -> sendLeave(), true));
            actionButtons.add(button(x, y, "再来一局", b -> sendNext(), !goBlocked));
        }
    }



    // ---------------- 操作 ----------------

    private void sendLeave() {
        ClientPlayNetworking.send(new LeaveRoomC2S());
    }

    private void sendSurrender() {
        ClientPlayNetworking.send(new SurrenderC2S());
    }

    private void sendPass() {
        ClientPlayNetworking.send(new PassC2S());
    }

    private void sendNext() {
        ClientPlayNetworking.send(new NextGameC2S());
    }

    private void sendUnspectate() {
        ClientPlayNetworking.send(new SpectateLeaveC2S());
    }

    // ---------------- 头像（复用斗地主渲染模式） ----------------


}
