package io.wifi.cards.board.gui;

import io.wifi.cards.board.model.BoardGameType;
import io.wifi.cards.board.network.BoardPackets.CreateRoomC2S;
import io.wifi.cards.board.network.BoardPackets.JoinRoomC2S;
import io.wifi.cards.board.network.BoardPackets.LeaveRoomC2S;
import io.wifi.cards.board.network.BoardPackets.LobbyQueryC2S;
import io.wifi.cards.board.network.BoardPackets.SpectateC2S;
import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.common.network.CommonPackets.MenuQueryC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 棋类统一大厅（黑白棋/五子棋/围棋共用一个大厅）：
 * <ul>
 *   <li>未在房间：选择游戏（黑白棋/五子棋/围棋）、围棋可选 9/19 路、是否公布房间到聊天栏、
 *       机器人数量（围棋禁用）、创建房间、输入房间码加入、进行中房间列表（等待中可加入 / 对局中可旁观）</li>
 *   <li>已在房间（等待中）：显示房间码/游戏/尺寸/成员，可离开</li>
 * </ul>
 * 房间列表由 LobbyQueryC2S 轮询刷新（打开时 + 每 20 tick），内容变化时重建控件。
 */
public class BoardLobbyScreen extends Screen {
    private BoardGameType selected = BoardGameType.OTHELLO;
    /** 围棋棋盘尺寸（9/19），其他游戏固定。 */
    private int goSize = 9;
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时是否加入 1 个机器人（围棋无 AI 禁用）。 */
    private boolean botOn;
    private EditBox codeBox;
    /** 内容区滚动偏移（≤0，小窗口内容超高时滚轮上移）。 */
    private float scroll;
    /** 房间列表摘要（内容变化才重建，避免每 tick 闪烁）。 */
    private String listSignature = "";

    public BoardLobbyScreen() {
        super(Component.literal("棋类大厅"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 关闭大厅（Esc）：等待玩家中关闭时提示可通过命令/点击重新打开；
     *  从菜单进入本大厅后关闭时，若其它游戏有进行中的会话则恢复其界面。 */
    @Override
    public void onClose() {
        if (GameMenuClient.tryRestoreOtherSession(GameRegistry.GAME_BOARD)) {
            return;
        }
        if (BoardClientState.INSTANCE.inRoom()) {
            BoardClientState.chatReopenHint("关闭大厅");
        }
        super.onClose();
    }

    /** 大厅内每 20 tick 轮询房间列表（服务端快照下发生成，无专门推送通道）；
     *  已在房间等待中时界面为房间信息视图，无需刷新列表，停止轮询。 */
    @Override
    public void tick() {
        super.tick();
        if (++queryCounter >= 20) {
            queryCounter = 0;
            if (!BoardClientState.INSTANCE.inRoom()) {
                ClientPlayNetworking.send(new LobbyQueryC2S());
            }
        }
    }

    private int queryCounter;

    /** 房间列表下发：摘要（含行文本，成员名变化也触发刷新）变化才重建（保留输入框内容）。 */
    public void onRoomListChanged() {
        StringBuilder sb = new StringBuilder();
        for (BoardClientState.RoomEntry e : BoardClientState.INSTANCE.roomList) {
            sb.append(e.code()).append(e.status()).append(e.line());
        }
        String sig = sb.toString();
        if (!sig.equals(listSignature)) {
            listSignature = sig;
            rebuild();
        }
    }

    // ---------------- 内容区布局（两列紧凑 + 滚轮滚动兜底） ----------------

    /** 内容区顶部 y（随滚动偏移）。 */
    private int contentTop() {
        return Math.max(50, (height - 180) / 2) + (int) scroll;
    }

    /** 内容区底部 y（房间列表末行 + 边距）。 */
    private int contentBottom() {
        return contentTop() + 250;
    }

    /** 内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int maxScroll() {
        return Math.max(0, contentBottom() - (height - 30));
    }

    /** 滚动后重建全部控件（位置随 contentTop 变化）。 */
    private void rebuild() {
        // 保留输入框内容（滚动重建会重新创建 EditBox，直接重建会清空已输入的房间码）
        String prevCode = codeBox != null ? codeBox.getValue() : "";
        clearWidgets();
        init();
        if (codeBox != null && !prevCode.isEmpty()) {
            codeBox.setValue(prevCode);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!BoardClientState.INSTANCE.inRoom() && maxScroll() > 0) {
            scroll -= (float) verticalAmount * 10;
            scroll = Math.max(-maxScroll(), Math.min(0, scroll));
            rebuild();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void init() {
        clearWidgets(); // 滚动重建时防重复添加
        BoardClientState s = BoardClientState.INSTANCE;
        int cx = width / 2;
        // 返回小游戏菜单（发刷新请求，服务端回发菜单数据打开菜单界面）
        addRenderableWidget(Button.builder(Component.literal("主菜单"), b ->
                        ClientPlayNetworking.send(new MenuQueryC2S()))
                .bounds(width - 110, 32, 100, 20).build());
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
            // 围棋尺寸（仅选围棋时显示）
            if (selected == BoardGameType.GO) {
                addRenderableWidget(Button.builder(Component.literal("尺寸：9 路"), b -> {
                    goSize = 9;
                    rebuild();
                }).bounds(lx, top + 24, 75, 20).build());
                addRenderableWidget(Button.builder(Component.literal("尺寸：19 路"), b -> {
                    goSize = 19;
                    rebuild();
                }).bounds(lx + 79, top + 24, 85, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "开" : "关")), b -> {
                announce = !announce;
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(lx, top + 48, 160, 20).build());
            // 机器人（围棋无 AI，禁用并提示；先清 botOn 再建按钮，避免文案残留"1 个"）
            if (selected == BoardGameType.GO) {
                botOn = false;
            }
            Button botBtn = Button.builder(Component.literal("机器人：" + (botOn ? "1 个" : "关")), b -> {
                botOn = !botOn;
                b.setMessage(Component.literal("机器人：" + (botOn ? "1 个" : "关")));
            }).bounds(lx, top + 72, 160, 20).build();
            botBtn.active = selected != BoardGameType.GO;
            addRenderableWidget(botBtn);
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S((byte) selected.ordinal(), (byte) goSize,
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
            // 房间列表（等待中可加入 / 对局中可旁观）；起始 y 在提示区（top+92..116）之下，防重叠
            List<BoardClientState.RoomEntry> list = s.roomList;
            if (!list.isEmpty()) {
                int ly = top + 122;
                int maxRows = Math.max(1, (height - ly - 20) / 22);
                for (int i = 0; i < Math.min(list.size(), maxRows); i++) {
                    BoardClientState.RoomEntry e = list.get(i);
                    int y = ly + i * 22;
                    // 行文本截断到按钮宽度内（按钮 228 宽，留边距防溢出）
                    String line = font.plainSubstrByWidth(e.line(), 212);
                    addRenderableWidget(Button.builder(Component.literal(e.code()), b ->
                                    ClientPlayNetworking.send(new JoinRoomC2S(e.code())))
                            .bounds(lx, y, 62, 20).build());
                    if (e.status() == 0) {
                        addRenderableWidget(Button.builder(Component.literal(line), b ->
                                        ClientPlayNetworking.send(new JoinRoomC2S(e.code())))
                                .bounds(lx + 66, y, 228, 20).build());
                    } else if (e.status() == 1) {
                        addRenderableWidget(Button.builder(Component.literal(line + " [旁观]"), b ->
                                        ClientPlayNetworking.send(new SpectateC2S(e.code())))
                                .bounds(lx + 66, y, 228, 20).build());
                    } else {
                        addRenderableWidget(Button.builder(Component.literal(line + "（已结束）"), b -> {
                                })
                                .bounds(lx + 66, y, 228, 20).build()).active = false;
                    }
                }
            }
        } else {
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, Math.max(40, height / 2 + 56), 160, 20).build());
        }
    }

    private void selectGame(BoardGameType type) {
        selected = type;
        rebuild();
    }

    /** 选中的游戏按钮加 ▶ 前缀（视觉选中指示）。 */
    private static String mark(boolean selected, String label) {
        return selected ? "▶ " + label : label;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        BoardClientState s = BoardClientState.INSTANCE;
        int cx = width / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        BoardGui.centeredShadow(g, this.font, width, "棋类大厅", 9, 0xFFFFD700);
        if (!s.inRoom()) {
            int top = contentTop();
            // 提示区半透明黑底
            g.fill(cx - 180, top + 92, cx + 180, top + 116, 0x55000000);
            BoardGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", top + 98, 0xFFAAAAAA);
            BoardGui.centeredShadow(g, this.font, width, "提示：房主可用 /cardgames invite <玩家名> 邀请；对局中房间可在下方列表旁观", top + 110, 0xFF777777);
            if (maxScroll() > 0) {
                BoardGui.centeredShadow(g, this.font, width, "内容超出屏幕，滚动滚轮查看", height - 14, 0xFF888888);
            }
        } else {
            // 房间信息区半透明黑底
            g.fill(cx - 200, 30, cx + 200, 120, 0x55000000);
            BoardGui.centeredShadow(g, this.font, width,
                    "房间 " + s.roomCode + "（" + s.gameType.displayName + sizeText() + "）", 34, 0xFFFFFF88);
            BoardGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 2", 50, 0xFFFFFFFF);
            for (int i = 0; i < 2; i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names[i] == null || s.names[i].isEmpty() ? "等待加入…" : s.names[i])
                        + "（" + s.sideName(i) + "方）";
                BoardGui.centeredShadow(g, this.font, width, line, 68 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            BoardGui.centeredShadow(g, this.font, width, "满 2 人自动开始", 102, 0xFFAAAAAA);
        }
    }

    private String sizeText() {
        BoardClientState s = BoardClientState.INSTANCE;
        return s.gameType == BoardGameType.GO ? s.size + " 路" : " · " + s.size + "×" + s.size + " 盘";
    }
}
