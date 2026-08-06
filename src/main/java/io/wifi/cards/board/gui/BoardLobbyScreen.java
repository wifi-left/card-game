package io.wifi.cards.board.gui;

import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.network.BoardPackets.CreateRoomC2S;
import io.wifi.cards.board.network.BoardPackets.JoinRoomC2S;
import io.wifi.cards.board.network.BoardPackets.LeaveRoomC2S;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.AbstractLobbyScreen;
import io.wifi.cards.common.client.LobbyPrefs;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 棋类统一大厅（黑白棋/五子棋/围棋共用一个大厅，继承 {@link AbstractLobbyScreen}，
 * 共享标题条/主菜单/房间列表/复制/滚动）：
 * <ul>
 *   <li>未在房间：选择游戏与棋盘尺寸（黑白棋 6/8/10、五子棋 11/13/15/19、围棋 9/13/19 路）、
 *       是否公布房间到聊天栏、机器人数量（围棋禁用）、创建房间、输入房间码加入、
 *       进行中房间列表（等待中可加入 / 对局中可旁观，点击房间码可复制）</li>
 *   <li>已在房间（等待中）：显示房间码/游戏/尺寸/成员，可离开（点击房间码可复制）</li>
 * </ul>

 */
public class BoardLobbyScreen extends AbstractLobbyScreen {
    private BoardGameType selected = BoardGameType.OTHELLO;
    /** 各游戏当前选择的棋盘尺寸（切换游戏时各自保留）。 */
    private int othelloSize = 8;
    private int gomokuSize = 15;
    private int goSize = 9;
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时是否加入 1 个机器人（围棋无 AI 禁用）。 */
    private boolean botOn;

    public BoardLobbyScreen() {
        super("棋类大厅");
        // 记住上次开房间的选项（客户端 config 持久化），下次打开默认选中；
        // 防御：越界/非法值回退默认（config 文件被手改/版本变化时）
        int typeOrd = LobbyPrefs.getInt(GameRegistry.GAME_BOARD, "gameType", 0);
        selected = typeOrd >= 0 && typeOrd < BoardGameType.values().length
                ? BoardGameType.values()[typeOrd] : BoardGameType.OTHELLO;
        othelloSize = validSize(BoardGameType.OTHELLO.sizeOptions,
                LobbyPrefs.getInt(GameRegistry.GAME_BOARD, "othelloSize", 8), 8);
        gomokuSize = validSize(BoardGameType.GOMOKU.sizeOptions,
                LobbyPrefs.getInt(GameRegistry.GAME_BOARD, "gomokuSize", 15), 15);
        goSize = validSize(BoardGameType.GO.sizeOptions,
                LobbyPrefs.getInt(GameRegistry.GAME_BOARD, "goSize", 9), 9);
        announce = LobbyPrefs.getBool(GameRegistry.GAME_BOARD, "announce", true);
        botOn = LobbyPrefs.getBool(GameRegistry.GAME_BOARD, "botOn", false);
    }

    /** 校验持久化的尺寸是否是该游戏的可选尺寸之一；非法回退默认。 */
    private static int validSize(int[] options, int value, int def) {
        for (int opt : options) {
            if (opt == value) {
                return value;
            }
        }
        return def;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    // ---------------- 基类钩子 ----------------

    @Override
    protected String gameId() {
        return GameRegistry.GAME_BOARD;
    }

    @Override
    protected boolean inRoomState() {
        return BoardClientState.INSTANCE.inRoom();
    }

    @Override
    protected String lobbyTitle() {
        return "棋类大厅";
    }

    @Override
    protected int contentTop() {
        return Math.max(50, (height - 180) / 2) + (int) scroll;
    }

    @Override
    protected void lobbyChat(String message) {
        BoardClientState.chat(message);
    }

    @Override
    protected String currentRoomCode() {
        return BoardClientState.INSTANCE.roomCode;
    }

    @Override
    protected void reopenHint() {
        BoardClientState.chatReopenHint("关闭大厅");
    }

    /** 房间码点击区随滚动偏移（房间信息区首行）。 */
    @Override
    protected int[] roomInfoCodeRect() {
        if (!inRoomState()) {
            return null;
        }
        int sc = (int) scroll;
        // 等待房间信息区"房间 XXX（游戏·尺寸）"行（居中，y≈50，行高 9，放宽点击区）
        return new int[]{width / 2 - 200, 46 + sc, width / 2 + 200, 59 + sc};
    }

    /** 房间视图底板（信息区 + 金色边框 + 按钮区；super.render 之前绘制，按钮在底板之上）。 */
    @Override
    protected void drawRoomViewBg(GuiGraphics g) {
        int sc = (int) scroll;
        int cx = width / 2;
        g.fill(cx - 200, 30 + sc, cx + 200, 124 + sc, 0x55000000);
        g.fill(cx - 201, 29 + sc, cx + 201, 30 + sc, 0xFFB08A3B);
        g.fill(cx - 201, 124 + sc, cx + 201, 125 + sc, 0xFFB08A3B);
        g.fill(cx - 201, 29 + sc, cx - 200, 125 + sc, 0xFFB08A3B);
        g.fill(cx + 200, 29 + sc, cx + 201, 125 + sc, 0xFFB08A3B);
        g.fill(cx - 200, 124 + sc, cx + 200, roomBottom() + 6, 0x44000000);
    }

    /** 房间操作按钮（离开房间）区底部 y："关闭界面"按钮放在其下方（随房间区滚动）。 */
    @Override
    protected int roomActionBottomY() {
        return Math.max(40, height / 2 + 56) + 26 + (int) scroll;
    }

    /** 房间视图内容超高（小窗口）时同样可滚动。 */
    @Override
    protected int scrollLimit() {
        return inRoomState() ? roomMaxScroll() : 0; // 未进房无房间列表，无需滚动
    }

    /** 房间内容区底部（信息面板 + 离开/关闭界面按钮行）。 */
    private int roomBottom() {
        return roomActionBottomY() + 20;
    }

    /** 房间内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int roomMaxScroll() {
        return Math.max(0, roomBottom() - (height - 30));
    }

    /** 房间视图滚动条轨道顶（信息区底，随滚动偏移）。 */
    @Override
    protected int scrollbarTrackTop() {
        return 124 + (int) scroll;
    }

    // ---------------- 内容区控件 ----------------

    @Override
    protected void buildContent() {
        BoardClientState s = BoardClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            // 两列紧凑布局：左列选项（游戏/尺寸/公布/机器人），右列操作（创建/输入框/加入）
            int top = contentTop();
            int lx = cx - 172;
            int rx = cx + 12;
            // 游戏选择：三个小按钮一行（选中的以 ▶ 标记）
            addRenderableWidget(Button.builder(Component.literal(mark(selected == BoardGameType.OTHELLO, "黑白棋")),
                    b -> selectGame(BoardGameType.OTHELLO))
                    .bounds(lx, top, 50, 20).build());
            addRenderableWidget(Button.builder(Component.literal(mark(selected == BoardGameType.GOMOKU, "五子棋")),
                    b -> selectGame(BoardGameType.GOMOKU))
                    .bounds(lx + 54, top, 50, 20).build());
            addRenderableWidget(Button.builder(Component.literal(mark(selected == BoardGameType.GO, "围棋")),
                    b -> selectGame(BoardGameType.GO))
                    .bounds(lx + 108, top, 50, 20).build());
            // 尺寸选择：当前游戏的可选尺寸一行，按钮宽按选项数自适应铺满左列 160（对齐）；
            // 选中指示由 render 的金色描边承担（窄按钮放不下 ▶ 前缀）
            int[] opts = selected.sizeOptions;
            int bw = sizeBtnW(opts.length);
            for (int i = 0; i < opts.length; i++) {
                final int sz = opts[i];
                addRenderableWidget(Button.builder(Component.literal(sz + " 路"), b -> {
                    setSize(sz);
                    rebuildLobby();
                }).bounds(lx + i * (bw + 2), top + 24, bw, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "✓ 开" : "✗ 关")), b -> {
                announce = !announce;
                LobbyPrefs.set(GameRegistry.GAME_BOARD, "announce", announce);
                b.setMessage(Component.literal("公布房间：" + (announce ? "✓ 开" : "✗ 关")));
            }).bounds(lx, top + 48, 160, 20).build());
            // 机器人（围棋无 AI，禁用并提示；先清 botOn 再建按钮，避免文案残留"1 个"）
            if (selected == BoardGameType.GO) {
                botOn = false;
            }
            Button botBtn = Button.builder(Component.literal("机器人：" + (botOn ? "✓ 1 个" : "✗ 关")), b -> {
                botOn = !botOn;
                LobbyPrefs.set(GameRegistry.GAME_BOARD, "botOn", botOn);
                b.setMessage(Component.literal("机器人：" + (botOn ? "✓ 1 个" : "✗ 关")));
            }).bounds(lx, top + 72, 160, 20).build();
            botBtn.active = selected != BoardGameType.GO;
            addRenderableWidget(botBtn);
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S((byte) selected.ordinal(), (byte) currentSize(),
                            announce, (byte) (botOn ? 1 : 0))))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(8);
            codeBox.setFilter(str -> str.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-'));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new BoardRulesScreen(BoardLobbyScreen.this)))
                    .bounds(rx, top + 72, 160, 20).build());
        } else {
            // 离开房间按钮：随滚动偏移（小窗口房间视图滚动时保持可见可点）
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, Math.max(40, height / 2 + 56) + (int) scroll, 160, 20).build());
        }
    }

    private void selectGame(BoardGameType type) {
        selected = type;
        LobbyPrefs.set(GameRegistry.GAME_BOARD, "gameType", type.ordinal());
        rebuildLobby();
    }

    /** 当前选中游戏已选的棋盘尺寸。 */
    private int currentSize() {
        return switch (selected) {
            case OTHELLO -> othelloSize;
            case GOMOKU -> gomokuSize;
            case GO -> goSize;
        };
    }

    private void setSize(int size) {
        switch (selected) {
            case OTHELLO -> {
                othelloSize = size;
                LobbyPrefs.set(GameRegistry.GAME_BOARD, "othelloSize", size);
            }
            case GOMOKU -> {
                gomokuSize = size;
                LobbyPrefs.set(GameRegistry.GAME_BOARD, "gomokuSize", size);
            }
            case GO -> {
                goSize = size;
                LobbyPrefs.set(GameRegistry.GAME_BOARD, "goSize", size);
            }
        }
    }

    /** 选中按钮金色描边（1px，画在按钮外缘，替代缺失的原生选中态）。 */
    private static void drawSelectionFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y, 0xFFFFD700);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFFFFD700);
        g.fill(x - 1, y - 1, x, y + h + 1, 0xFFFFD700);
        g.fill(x + w, y - 1, x + w + 1, y + h + 1, 0xFFFFD700);
    }

    /** 尺寸按钮宽度：总宽 160（左列宽）按选项数均分，2px 间隙（5 档 = 30px，仍放得下"14 路"）。 */
    private static int sizeBtnW(int count) {
        return count <= 0 ? 0 : (160 - (count - 1) * 2) / count;
    }

    /** 选中的按钮加 ▶ 前缀（视觉选中指示）。 */
    private static String mark(boolean selected, String label) {
        return selected ? "▶" + label : label;
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顶部标题条先绘制（主菜单按钮位于标题条内右上角，super.render 后渲染按钮盖住标题条半透明底）
        drawTitleBar(g);
        // 房间视图底板/未进房提示区先绘制：super.render 的按钮绘制在其上，不被压暗
        if (BoardClientState.INSTANCE.inRoom()) {
            drawRoomViewBg(g);
        } else {
            drawLobbyHints(g); // 邀请提示 + 房间列表入口提示（按钮由基类 init 添加）
        }
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        BoardClientState s = BoardClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            int top = contentTop();
            int lx = cx - 172;
            // 选中项金色描边（游戏选择行 + 尺寸行，画在按钮之上）
            drawSelectionFrame(g, lx + selected.ordinal() * 54, top, 50, 20);
            int[] opts = selected.sizeOptions;
            int bw = sizeBtnW(opts.length);
            for (int i = 0; i < opts.length; i++) {
                if (currentSize() == opts[i]) {
                    drawSelectionFrame(g, lx + i * (bw + 2), top + 24, bw, 20);
                }
            }
            // 区块标题（屏幕居中）
            BoardGui.centeredShadow(g, this.font, width, "创建房间", top - 12, 0xFFFFD700);
        } else {
            // 房间信息区 + 按钮区底板见 drawRoomViewBg（super.render 之前绘制）
            int sc = (int) scroll;
            BoardGui.centeredShadow(g, this.font, width, "等待房间（满 2 人自动开始）", 34 + sc, 0xFFFFD700);
            BoardGui.centeredShadow(g, this.font, width,
                    "房间 " + s.roomCode + "（" + s.gameType.displayName + sizeText() + "）", 50 + sc, 0xFFFFFF88);
            BoardGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 2", 64 + sc, 0xFFFFFFFF);
            for (int i = 0; i < 2; i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names[i] == null || s.names[i].isEmpty() ? "等待加入…" : s.names[i])
                        + "（" + s.sideName(i) + "方）";
                BoardGui.centeredShadow(g, this.font, width, line, 80 + i * 14 + sc,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            BoardGui.centeredShadow(g, this.font, width,
                    "提示：房主可用 /cardgames invite <玩家名> 邀请", 114 + sc, 0xFFAAAAAA);
            // 房间视图滚动条（小窗口内容超高时）
            drawRoomScrollbar(g);
        }
    }

    private String sizeText() {
        BoardClientState s = BoardClientState.INSTANCE;
        return s.gameType == BoardGameType.GO ? s.size + " 路" : " · " + s.size + "×" + s.size + " 盘";
    }
}
