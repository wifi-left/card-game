package io.wifi.cards.board.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.model.BoardPhase;
import io.wifi.cards.board.network.BoardPackets.LeaveRoomC2S;
import io.wifi.cards.board.network.BoardPackets.MoveC2S;
import io.wifi.cards.board.network.BoardPackets.NextGameC2S;
import io.wifi.cards.board.network.BoardPackets.PassC2S;
import io.wifi.cards.board.network.BoardPackets.SpectateLeaveC2S;
import io.wifi.cards.board.network.BoardPackets.SurrenderC2S;
import io.wifi.cards.board.othello.rule.OthelloRules;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 棋类共用棋盘界面（黑白棋/五子棋/围棋共用一个 Screen，按游戏类型分支渲染）。
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
public class BoardGameScreen extends Screen {
    // 逻辑格子尺寸与棋盘边距（棋盘绘制在逻辑坐标，整体随窗口缩放）
    private static final int CELL = 28;
    private static final int MARGIN = 14;
    // 固定像素布局：顶部信息条、右侧按钮区（不参与缩放）
    private static final int TOP_BAR = 54;
    private static final int RIGHT_PANEL_W = 116;
    /** 棋盘缩放上限：窗口够大放至 1.5 不再放大。无固定下限——
     *  scale 恒 ≤ 可用矩形比例，任何窗口尺寸下棋盘都不会溢出顶栏/按钮（小窗口棋盘变小但完整可见、命中准确）。 */
    private static final float MAX_SCALE = 1.5F;

    private final List<Button> actionButtons = new ArrayList<>();
    private int buttonSignature = -1;
    private int countdown = 60;
    /** 最近一帧的棋盘变换（render 计算，鼠标逆变换用）。 */
    private float boardScale = 1F;
    private float boardOffsetX;
    private float boardOffsetY;
    /** 悬停格（落点预览），-1=无。 */
    private int hoverX = -1;
    private int hoverY = -1;
    /** 待打开聊天框（延迟到 tick 执行，避免同按键的字符事件被新聊天框接收）。 */
    private boolean openChatPending;

    public BoardGameScreen() {
        super(Component.literal("棋牌对局"));
    }

    /** 按聊天绑定键（原版 options.keyChat，默认 T）打开聊天框（参考斗地主）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.keyChat.matches(keyCode, scanCode)) {
            openChatPending = true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 旁观模式：服务端以 mySeat=-1 表示只读旁观（无操作权）。 */
    private boolean isSpectator() {
        return BoardClientState.INSTANCE.mySeat < 0;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 关闭对局界面（Esc）：调试旁观模式退出调试标记；正常对局提示可通过命令/点击重新打开。 */
    @Override
    public void onClose() {
        BoardClientState s = BoardClientState.INSTANCE;
        if (s.debugMode) {
            s.debugMode = false;
        } else {
            BoardClientState.chatReopenHint("关闭对局界面");
        }
        super.onClose();
    }

    // 注意：不在 removed() 里清 debugMode——打开规则界面等子界面也会触发 removed()，
    // 若清除会导致调试会话降级（"关闭"按钮变"退出旁观"）；真正关闭由 onClose/关闭按钮/真实状态包处理。

    @Override
    protected void init() {
        // 左下角：规则介绍（返回时回到本棋盘界面，并渲染本界面为背景）
        addRenderableWidget(Button
                .builder(Component.literal("规则"),
                        b -> Minecraft.getInstance().setScreen(new BoardRulesScreen(BoardGameScreen.this)))
                .bounds(8, height - 26, 60, 20).build());
        rebuildActionButtons();
    }

    /** 窗口 resize：父类会再次调用 init()，先清空全部再重建（防止按钮重复添加）；
     *  同时立即重算棋盘变换，避免 resize 后首帧点击仍用旧变换命中错位。 */
    @Override
    public void resize(Minecraft mc, int width, int height) {
        clearWidgets();
        actionButtons.clear();
        buttonSignature = -1;
        super.resize(mc, width, height);
        computeBoardTransform();
    }

    @Override
    public void tick() {
        super.tick();
        // 延迟打开聊天框（等本次按键的字符事件处理完毕，避免 't' 等字符进入输入框）
        if (openChatPending) {
            openChatPending = false;
            Minecraft.getInstance().setScreen(new BoardChatScreen(this));
        }
        BoardClientState s = BoardClientState.INSTANCE;
        // 用服务端下发的截止游戏刻计算剩余秒数：客户端 level.getGameTime() 与服务端同步，
        // 倒计时不受本地帧率/网络延迟影响
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && s.turnEndGameTime > 0) {
            long remainingTicks = s.turnEndGameTime - mc.level.getGameTime();
            countdown = (int) Math.max(0, (remainingTicks + 19) / 20); // 向上取整
        } else if (s.phase == BoardPhase.PLAYING && s.turnEndGameTime == 0) {
            countdown = 60; // 新局/重连等待 TurnS2C 期间重置，避免残留上一局倒计时
        }
        // 阶段/轮到谁/调试模式变化时重建按钮（旁观模式按钮恒定，独立签名；
        // 含 debugMode：调试界面转真实旁观后"关闭"按钮须替换为"退出旁观"）
        int signature = (s.phase.ordinal() * 100 + (s.currentSeat + 1) * 10 + (s.mySeat >= 0 ? 1 : 0)) * 2
                + (s.debugMode ? 1 : 0);
        if (signature != buttonSignature) {
            buttonSignature = signature;
            rebuildActionButtons();
        }
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
            for (int[] m : OthelloRules.legalMoves(s.board, (byte) (s.mySeat + 1))) {
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

    /** 悬停：轮到本人时更新预览格（渲染半透明棋子提示落点）。 */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        BoardClientState s = BoardClientState.INSTANCE;
        if (s.phase == BoardPhase.PLAYING && s.isMyTurn()) {
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
    }

    private void drawTopInfo(GuiGraphics g) {
        BoardClientState s = BoardClientState.INSTANCE;
        // 顶部信息条（固定像素，不随棋盘缩放）
        g.fill(0, 0, width, TOP_BAR, 0x66000000);
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
        // 左侧第二行：旁观/自己身份
        if (isSpectator()) {
            g.drawString(this.font, s.debugMode ? "旁观中（调试数据）" : "旁观中", 6, 32, 0xFFAAAAAA, true);
        } else {
            g.drawString(this.font, "你（" + s.sideName(s.mySeat) + "方）", 6, 32, 0xFFFFFFFF, true);
        }
        // 中央第二/三行：轮到谁 + 倒计时 + 最近动作
        if (s.phase == BoardPhase.PLAYING) {
            String turnText = s.isMyTurn()
                    ? "轮到你（剩余 " + Math.max(0, countdown) + " 秒）"
                    : "轮到 " + s.nameOf(s.currentSeat) + "（剩余 " + Math.max(0, countdown) + " 秒）";
            BoardGui.centeredShadow(g, this.font, width, turnText, 22,
                    s.isMyTurn() ? 0xFFFFFF55 : 0xFFAAAAAA);
            BoardGui.centeredShadow(g, this.font, width, s.lastAction, 36, 0xFFAAAAAA);
        } else if (s.phase == BoardPhase.SETTLED) {
            BoardGui.centeredShadow(g, this.font, width, s.lastAction, 36, 0xFFAAAAAA);
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
        // 棋盘底色 + 黑色边框
        g.fill(0, 0, bp, bp, 0xFFB07840);
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
        // 棋子（Tesselator 立即绘制）。关键：先 flush GuiGraphics 批处理缓冲——
        // 棋盘底色/网格线等 fill 是延迟到渲染结束统一提交的，若不 flush，
        // 棋子先画、棋盘底后提交会覆盖棋子，导致"棋盘可见但看不到棋子"
        g.flush();
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
            for (int[] m : OthelloRules.legalMoves(s.board, (byte) (s.mySeat + 1))) {
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

    /** 围棋星位（9 路 5 星，19 路 9 星）。 */
    private int[][] goStars() {
        if (size() == 9) {
            return new int[][]{{2, 2}, {2, 6}, {6, 2}, {6, 6}, {4, 4}};
        }
        return new int[][]{
                {3, 3}, {3, 9}, {3, 15},
                {9, 3}, {9, 9}, {9, 15},
                {15, 3}, {15, 9}, {15, 15}
        };
    }

    /** 绘制一个棋子：白色实心子先画黑色描边圆，再画本体（半透明提示不描边）。 */
    private void drawStone(GuiGraphics g, float cx, float cy, float r, int argb) {
        if (argb == 0xFFF2F2F2) {
            drawFilledCircle(g, cx, cy, r + 1, 0xFF000000);
        }
        drawFilledCircle(g, cx, cy, r, argb);
    }

    /** 实心圆（Tesselator 三角扇形，24 段；与斗地主"纯色块无纹理"风格一致）。 */
    private void drawFilledCircle(GuiGraphics g, float cx, float cy, float r, int argb) {
        Matrix4f pose = g.pose().last().pose();
        float red = ((argb >> 16) & 0xFF) / 255F;
        float green = ((argb >> 8) & 0xFF) / 255F;
        float blue = (argb & 0xFF) / 255F;
        float alpha = ((argb >> 24) & 0xFF) / 255F;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(pose, cx, cy, 0).setColor(red, green, blue, alpha);
        int segments = 24;
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2 * i / segments;
            b.addVertex(pose, cx + (float) (Math.cos(angle) * r), cy + (float) (Math.sin(angle) * r), 0)
                    .setColor(red, green, blue, alpha);
        }
        BufferUploader.drawWithShader(b.build());
        RenderSystem.disableBlend();
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
        BoardGui.centeredShadow(g, this.font, width, text, y, 0xFFFFD700);
    }

    // ---------------- 按钮（右下角，固定像素不缩放） ----------------

    private void rebuildActionButtons() {
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
            // 常驻：退出（座位转托管）+ 认输（任意时刻可认输）；围棋轮到本人时提供「停一手」
            actionButtons.add(button(x, y - 52, "退出", b -> sendLeave(), true));
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

    private Button button(int x, int y, String label, Button.OnPress onPress, boolean active) {
        Button b = Button.builder(Component.literal(label), onPress).bounds(x, y, 90, 20).build();
        b.active = active;
        addRenderableWidget(b);
        return b;
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

    /**
     * 渲染玩家头颅：通过 tab 列表的 PlayerInfo 获取皮肤纹理，
     * 用 PlayerFaceRenderer 绘制脸部区域（8x8 放大到目标尺寸）。
     * uuidStr 为空（假人/未知）、玩家不在 tab 列表或皮肤缺失时跳过。
     */
    private void drawHead(GuiGraphics g, String uuidStr, int x, int y, int size) {
        if (uuidStr == null || uuidStr.isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener connection = mc.getConnection();
            if (connection == null) {
                return;
            }
            PlayerInfo info = connection.getPlayerInfo(UUID.fromString(uuidStr));
            if (info == null) {
                return;
            }
            ResourceLocation skin = info.getSkin().texture();
            if (skin == null) {
                return;
            }
            PlayerFaceRenderer.draw(g, skin, x, y, size);
        } catch (IllegalArgumentException ignored) {
            // 非法 UUID（理论不会发生）→ 跳过头像
        }
    }
}
