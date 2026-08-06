package io.wifi.cards.uno.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.AbstractLobbyScreen;
import io.wifi.cards.common.client.LobbyPrefs;
import io.wifi.cards.uno.network.UnoPackets.CreateRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.JoinRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.LeaveRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.LobbyQueryC2S;
import io.wifi.cards.uno.network.UnoPackets.SpectateC2S;
import io.wifi.cards.uno.network.UnoPackets.StartGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * UNO 大厅界面（继承 {@link AbstractLobbyScreen}，共享标题条/主菜单/房间列表/复制/滚动）：
 * <ul>
 *   <li>未在房间：选择是否公布房间到聊天栏、创建房间（可带 0~9 个机器人）、
 *       输入房间码加入、公开房间列表（可加入/旁观/复制房间码）</li>
 *   <li>已在房间（等待中）：显示房间码/成员列表（最多 10 人），
 *       房主（座位 0）可点"开始游戏"（至少 2 人），其余玩家等待；可离开（点击房间码可复制）；
 *       成员多时房间信息区可滚动</li>
 * </ul>
 */
public class UnoLobbyScreen extends AbstractLobbyScreen {
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时加入的机器人数量（0~9 补位）。 */
    private int botCount;

    public UnoLobbyScreen() {
        super("UNO 大厅");
        // 记住上次开房间的选项（客户端 config 持久化），下次打开默认选中
        announce = LobbyPrefs.getBool(GameRegistry.GAME_UNO, "announce", true);
        botCount = Math.max(0, Math.min(LobbyPrefs.getInt(GameRegistry.GAME_UNO, "botCount", 0), 9));
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
        return GameRegistry.GAME_UNO;
    }

    @Override
    protected boolean inRoomState() {
        return UnoClientState.INSTANCE.inRoom();
    }

    @Override
    protected String lobbyTitle() {
        return "UNO 大厅";
    }

    @Override
    protected int contentTop() {
        return Math.max(50, (height - 160) / 2) + (int) scroll;
    }

    @Override
    protected void sendRoomQuery() {
        ClientPlayNetworking.send(new LobbyQueryC2S());
    }

    @Override
    protected List<? extends RoomEntry> lobbyRoomList() {
        return UnoClientState.INSTANCE.roomList;
    }

    @Override
    protected void joinRoom(String code) {
        ClientPlayNetworking.send(new JoinRoomC2S(code));
    }

    @Override
    protected void spectateRoom(String code) {
        ClientPlayNetworking.send(new SpectateC2S(code));
    }

    @Override
    protected void lobbyChat(String message) {
        UnoClientState.chat(message);
    }

    @Override
    protected String currentRoomCode() {
        return UnoClientState.INSTANCE.roomCode;
    }

    @Override
    protected int[] roomInfoCodeRect() {
        if (!inRoomState()) {
            return null;
        }
        // 房间信息区"房间 XXX"行（y = roomTop+4，行高 9，放宽点击区）
        return new int[]{width / 2 - 200, roomTop(), width / 2 + 200, roomTop() + 18};
    }

    @Override
    protected void reopenHint() {
        UnoClientState.chatReopenHint("关闭大厅");
    }

    /** 房间操作按钮（离开房间）区底部 y："关闭界面"按钮放在其下方（随房间区滚动）。 */
    @Override
    protected int roomActionBottomY() {
        return roomTop() + roomInfoH() + 8 + 52;
    }

    /** 房间视图内容超高时同样可滚动（成员多的小窗口）。 */
    @Override
    protected int scrollLimit() {
        return inRoomState() ? roomMaxScroll() : maxScroll();
    }

    // ---------------- 房间信息区布局（成员多时滚动） ----------------

    /** 房间信息区顶部 y（随滚动偏移）。 */
    private int roomTop() {
        return Math.max(30, (height - (roomInfoH() + 60)) / 2) + (int) scroll;
    }

    /** 房间信息区高度（标题行 + 成员行 + 底部提示行）。 */
    private int roomInfoH() {
        UnoClientState s = UnoClientState.INSTANCE;
        return 30 + s.names.size() * 14 + 16;
    }

    /** 房间内容区底部（信息面板 + 开始/离开/关闭界面按钮行）。 */
    private int roomBottom() {
        return roomTop() + roomInfoH() + 8 + 72;
    }

    /** 房间内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int roomMaxScroll() {
        return Math.max(0, roomBottom() - (height - 30));
    }

    // ---------------- 内容区控件 ----------------

    @Override
    protected void buildContent() {
        UnoClientState s = UnoClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            // 两列对称布局：左右各 3 个控件（左：公布/机器人/规则介绍，右：创建/输入框/加入）
            int top = contentTop();
            int lx = cx - 172;
            int rx = cx + 12;
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "开" : "关")), b -> {
                announce = !announce;
                LobbyPrefs.set(GameRegistry.GAME_UNO, "announce", announce);
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(lx, top, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")), b -> {
                botCount = (botCount + 1) % 10; // 关 → 1~9 个（房间最多 10 人）
                LobbyPrefs.set(GameRegistry.GAME_UNO, "botCount", botCount);
                b.setMessage(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")));
            }).bounds(lx, top + 24, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new UnoRulesScreen()))
                    .bounds(lx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(announce, (byte) botCount)))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(8);
            codeBox.setFilter(str -> str.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-'));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
        } else {
            // 房主（座位 0）开始游戏（至少 2 人）；其余玩家等待提示。
            // 按钮位于成员列表下方（随滚动偏移），小窗口下列表超高时可滚动查看
            int btnY = roomTop() + roomInfoH() + 8;
            if (s.isHost()) {
                Button startBtn = Button.builder(Component.literal("开始游戏"), b ->
                        ClientPlayNetworking.send(new StartGameC2S()))
                        .bounds(cx - 80, btnY, 160, 20).build();
                startBtn.active = s.roomSize() >= 2;
                addRenderableWidget(startBtn);
            }
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, btnY + 26, 160, 20).build());
        }
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顶部标题条先绘制（主菜单按钮位于标题条内右上角，super.render 后渲染按钮盖住标题条半透明底）
        drawTitleBar(g);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        UnoClientState s = UnoClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            int top = contentTop();
            int lx = cx - 172;
            // 提示区半透明黑底（位于内容区下方，不与按钮重叠）
            g.fill(cx - 180, top + 94, cx + 180, top + 126, 0x55000000);
            UnoGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", top + 100, 0xFFAAAAAA);
            UnoGui.centeredShadow(g, this.font, width, "提示：房主可用 /cardgames invite <玩家名> 邀请", top + 114, 0xFF777777);
            // 公开房间列表（标题 + 行文本 + 滚动条，点击行操作按钮加入/旁观、点房间码复制）
            drawRoomList(g);
        } else {
            // 房间信息区半透明黑底（随滚动偏移，覆盖到最底部提示行）
            int top = roomTop();
            int infoH = roomInfoH();
            g.fill(cx - 200, top, cx + 200, top + infoH + 8, 0x55000000);
            UnoGui.centeredShadow(g, this.font, width, "房间 " + s.roomCode, top + 4, 0xFFFFFF88);
            UnoGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 10", top + 20, 0xFFFFFFFF);
            for (int i = 0; i < s.names.size(); i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names.get(i) == null || s.names.get(i).isEmpty() ? "等待加入…" : s.names.get(i))
                        + (i == 0 ? "（房主）" : "");
                UnoGui.centeredShadow(g, this.font, width, line, top + 34 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            UnoGui.centeredShadow(g, this.font, width,
                    s.isHost() ? "至少 2 人可开始游戏" : "等待房主开始游戏…",
                    top + 34 + s.names.size() * 14 + 6, 0xFFAAAAAA);
            if (roomMaxScroll() > 0) {
                UnoGui.centeredShadow(g, this.font, width, "内容超出屏幕，滚动滚轮查看", height - 14, 0xFF888888);
            }
        }
    }
}
