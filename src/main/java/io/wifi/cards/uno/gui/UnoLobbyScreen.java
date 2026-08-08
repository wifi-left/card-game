package io.wifi.cards.uno.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.AbstractLobbyScreen;
import io.wifi.cards.common.client.LobbyPrefs;
import io.wifi.cards.uno.network.UnoPackets.CreateRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.JoinRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.LeaveRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.StartGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;


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
        super("wifi_card_games.uno.lobby.title");
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
    protected String lobbyTitleKey() {
        return "wifi_card_games.uno.lobby.title";
    }

    @Override
    protected int contentTop() {
        return Math.max(50, (height - 160) / 2) + (int) scroll;
    }

    @Override
    protected void lobbyChat(Component message) {
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
        UnoClientState.chatReopenHint(Component.translatable("wifi_card_games.uno.reopen.closed_lobby"));
    }

    /** 房间操作按钮（离开房间）区底部 y："关闭界面"按钮放在其下方（随房间区滚动）。 */
    @Override
    protected int roomActionBottomY() {
        return roomTop() + roomInfoH() + 8 + 52;
    }

    /** 房间视图内容超高时同样可滚动（成员多的小窗口）。 */
    @Override
    protected int scrollLimit() {
        return inRoomState() ? roomMaxScroll() : 0; // 未进房无房间列表，无需滚动
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

    /** 房间视图底板（信息区 + 按钮区；super.render 之前绘制，按钮在底板之上）。 */
    @Override
    protected void drawRoomViewBg(GuiGraphics g) {
        int cx = width / 2;
        g.fill(cx - 200, roomTop(), cx + 200, roomBottom() + 4, 0x55000000);
    }

    /** 房间内容区底部（信息面板 + 开始/离开/关闭界面按钮行）。 */
    private int roomBottom() {
        return roomTop() + roomInfoH() + 8 + 72;
    }

    /** 房间内容超高时允许的滚动量（0 = 无需滚动）。
     *  用 scroll=0 的固定几何计算（roomTop 含滚动偏移，须剔除）——
     *  滚动上限随当前 scroll 漂移会导致滚轮/拖拽跳动卡死。 */
    private int roomMaxScroll() {
        int baseTop = Math.max(30, (height - (roomInfoH() + 60)) / 2); // roomTop 不含滚动
        int baseBottom = baseTop + roomInfoH() + 8 + 72;
        return Math.max(0, baseBottom - (height - 30));
    }

    /** 房间视图滚动条轨道顶（固定：信息区底，不随滚动偏移——换算分母稳定）。 */
    @Override
    protected int scrollbarTrackTop() {
        return Math.max(30, (height - (roomInfoH() + 60)) / 2) + roomInfoH() + 8;
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
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.lobby.announce",
                    Component.translatable(announce
                            ? "wifi_card_games.uno.lobby.on" : "wifi_card_games.uno.lobby.off")), b -> {
                announce = !announce;
                LobbyPrefs.set(GameRegistry.GAME_UNO, "announce", announce);
                b.setMessage(Component.translatable("wifi_card_games.uno.lobby.announce",
                        Component.translatable(announce
                                ? "wifi_card_games.uno.lobby.on" : "wifi_card_games.uno.lobby.off")));
            }).bounds(lx, top, 160, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.lobby.bots",
                    botCount == 0 ? Component.translatable("wifi_card_games.uno.lobby.bots_off")
                            : Component.translatable("wifi_card_games.uno.lobby.bots_n", botCount)), b -> {
                botCount = (botCount + 1) % 10; // 关 → 1~9 个（房间最多 10 人）
                LobbyPrefs.set(GameRegistry.GAME_UNO, "botCount", botCount);
                b.setMessage(Component.translatable("wifi_card_games.uno.lobby.bots",
                        botCount == 0 ? Component.translatable("wifi_card_games.uno.lobby.bots_off")
                                : Component.translatable("wifi_card_games.uno.lobby.bots_n", botCount)));
            }).bounds(lx, top + 24, 160, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.rules_intro"), b ->
                    Minecraft.getInstance().setScreen(new UnoRulesScreen()))
                    .bounds(lx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.lobby.create"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(announce, (byte) botCount)))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.translatable("wifi_card_games.uno.lobby.code_box"));
            codeBox.setMaxLength(8);
            codeBox.setFilter(str -> str.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-'));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.lobby.join"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
        } else {
            // 房主（座位 0）开始游戏（至少 2 人）；其余玩家等待提示。
            // 按钮位于成员列表下方（随滚动偏移），小窗口下列表超高时可滚动查看
            int btnY = roomTop() + roomInfoH() + 8;
            if (s.isHost()) {
                Button startBtn = Button.builder(Component.translatable("wifi_card_games.uno.lobby.start"), b ->
                        ClientPlayNetworking.send(new StartGameC2S()))
                        .bounds(cx - 80, btnY, 160, 20).build();
                startBtn.active = s.roomSize() >= 2;
                addRenderableWidget(startBtn);
            }
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.lobby.leave"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, btnY + 26, 160, 20).build());
        }
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顶部标题条先绘制（主菜单按钮位于标题条内右上角，super.render 后渲染按钮盖住标题条半透明底）
        drawTitleBar(g);
        // 房间视图底板/未进房提示区先绘制：super.render 的按钮绘制在其上，不被压暗
        if (UnoClientState.INSTANCE.inRoom()) {
            drawRoomViewBg(g);
        } else {
            drawLobbyHints(g); // 邀请提示 + 房间列表入口提示（按钮由基类 init 添加）
        }
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        UnoClientState s = UnoClientState.INSTANCE;
        if (!s.inRoom()) {
        } else {
            // 房间信息区 + 按钮区底板见 drawRoomViewBg（super.render 之前绘制）
            int top = roomTop();
            UnoGui.centeredShadow(g, this.font, width,
                    Component.translatable("wifi_card_games.uno.lobby.room_header", s.roomCode),
                    top + 4, 0xFFFFFF88);
            UnoGui.centeredShadow(g, this.font, width,
                    Component.translatable("wifi_card_games.uno.lobby.players", s.roomSize()),
                    top + 20, 0xFFFFFFFF);
            for (int i = 0; i < s.names.size(); i++) {
                Component line = Component.literal(i == s.mySeat ? "▶ " : "  ")
                        .append(Component.literal((i + 1) + ". "))
                        .append(s.names.get(i) == null || s.names.get(i).isEmpty()
                                ? Component.translatable("wifi_card_games.uno.lobby.waiting_join")
                                : Component.literal(s.names.get(i)));
                if (i == 0) {
                    line = line.copy().append(Component.translatable("wifi_card_games.uno.lobby.host_tag"));
                }
                UnoGui.centeredShadow(g, this.font, width, line, top + 34 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            UnoGui.centeredShadow(g, this.font, width,
                    Component.translatable(s.isHost()
                            ? "wifi_card_games.uno.lobby.min_two_hint" : "wifi_card_games.uno.lobby.wait_host"),
                    top + 34 + s.names.size() * 14 + 6, 0xFFAAAAAA);
            // 房间视图滚动条（成员多/小窗口内容超高时）
            drawRoomScrollbar(g);
        }
    }
}
