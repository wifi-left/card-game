package io.wifi.cards.common.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 小游戏等候大厅通用基类（纯客户端）：三个游戏大厅（斗地主/UNO/棋类）共享的
 * 结构与交互，游戏特有的创建区/房间视图由子类实现。
 * <p>统一提供：</p>
 * <ul>
 *   <li>标题条（右上角"主菜单"按钮，标题背景区域内）</li>
 *   <li>未进房提示区：邀请提示 + 房间列表入口（点击关闭 UI，客户端执行命令，
 *       聊天栏显示当前游戏房间列表，可点击加入/旁观）</li>
 *   <li>T 键打开聊天框（延迟到 tick，避免字符事件打入输入框）→ CardGameChatScreen</li>
 *   <li>等待房间视图：房间信息区/按钮区统一底板、房间码点击复制、小窗口滚动（滚轮+滚动条拖拽）</li>
 *   <li>等待中"关闭界面"按钮（保留房间，输入 /xxx 或 /cardgames 重新打开）</li>
 *   <li>关闭大厅时恢复其它游戏会话（onClose 模板）</li>
 * </ul>
 * 房间列表不在大厅内直接显示：通过提示区"查看房间列表"按钮（关闭 UI 后聊天栏显示
 * 当前游戏房间列表）或 /cardgames rooms 命令在聊天栏查看。
 * 滚动方向统一为负方向（scroll ∈ [-maxScroll, 0]）。
 */
public abstract class AbstractLobbyScreen extends Screen {
    /** 输入房间码输入框（子类在 buildContent 中创建）。 */
    protected EditBox codeBox;

    /** 内容区滚动偏移（≤0，小窗口房间视图超高时滚轮上移）。 */
    protected float scroll;
    /** 滚动条拖拽状态（按下时的鼠标 y / 滚动偏移，用于增量换算）。 */
    private boolean draggingScroll;
    private double dragStartMouseY;
    private int dragStartScroll;
    /** 待打开聊天框（延迟到 tick 执行，避免同按键的字符事件被新聊天框接收）。 */
    private boolean openChatPending;

    protected AbstractLobbyScreen(String titleKey) {
        super(Component.translatable(titleKey));
    }

    // ---------------- 子类钩子（游戏特有） ----------------

    /** 游戏 id（GameRegistry 常量，会话恢复/偏好用）。 */
    protected abstract String gameId();

    /** 客户端是否已在房间中（决定创建区/房间视图切换）。 */
    protected abstract boolean inRoomState();

    /** 内容区顶部 y（随滚动偏移）。 */
    protected abstract int contentTop();

    /** 构建内容区控件（创建区按钮/房间视图按钮；不含主菜单，由基类统一添加）。 */
    protected abstract void buildContent();

    /** 聊天栏消息（复制提示等，带游戏前缀）。 */
    protected abstract void lobbyChat(Component message);

    /** 当前房间码（房间信息区复制用；null=不在房间）。 */
    protected abstract String currentRoomCode();

    /** 房间信息区房间码的可点击区域 {x1,y1,x2,y2}；null=不可点击。 */
    protected abstract int[] roomInfoCodeRect();

    /** 关闭大厅提示（等待玩家中关闭时）。 */
    protected abstract void reopenHint();

    /** 滚动上限（0=不可滚动）；房间视图超高时由子类覆写返回房间区上限。 */
    protected int scrollLimit() {
        return 0;
    }

    /** 房间操作按钮区底部 y（"关闭界面"按钮放置位置，子类提供）。 */
    protected abstract int roomActionBottomY();

    // ---------------- 公共布局 ----------------

    /** 内容区左缘 x（两列布局共用）。 */
    protected int contentLeft() {
        return width / 2 - 172;
    }

    // ---------------- init / 聊天 / 重建 ----------------

    @Override
    protected void init() {
        clearWidgets(); // 重建时防重复添加
        // 返回小游戏菜单：直接用缓存数据打开 UI（不发包——服务端刷新有签名对比，
        // 统计无变化时不发 OpenMenuS2C，发包会导致菜单打不开）；统计可在菜单内点"刷新"更新
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.main_menu"), b ->
                        GameMenuClient.openMenuFromCache())
                .bounds(width - 106, 3, 100, 20).build());
        buildContent();
        if (inRoomState()) {
            addCloseLobbyButton();
        } else {
            addRoomListButton();
        }
    }

    /** 未进房："查看房间列表"入口——点击关闭 UI，客户端执行 /cardgames rooms <游戏>，
     *  聊天栏显示当前游戏房间列表（可点击加入/旁观）。 */
    private void addRoomListButton() {
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.room_list"), b -> openRoomList())
                .bounds(width / 2 - 80, contentTop() + 136, 160, 20).build());
    }

    /** 关闭大厅界面并请求聊天栏房间列表（客户端执行命令，服务端回当前游戏房间列表）。 */
    private void openRoomList() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null); // 关闭 UI
        if (mc.getConnection() != null) {
            mc.getConnection().sendCommand("cardgames rooms " + gameId());
        }
    }

    /** 等待房间视图："关闭界面"按钮（关闭大厅界面但保留房间，输入 /xxx 或 /cardgames 可重新打开）。 */
    private void addCloseLobbyButton() {
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.close_ui"), b -> closeLobbyKeepRoom())
                .bounds(width / 2 - 80, roomActionBottomY(), 160, 20).build());
    }

    /** 关闭大厅界面但保留房间（不发送离开房间请求，服务端房间与会话原样保留）。 */
    private void closeLobbyKeepRoom() {
        reopenHint(); // 提示如何重新打开
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void tick() {
        super.tick();
        // 延迟打开聊天框（等本次按键的字符事件处理完毕）
        if (openChatPending) {
            openChatPending = false;
            Minecraft.getInstance().setScreen(new CardGameChatScreen(this));
        }
    }

    /**
     * 键盘：输入框（房间码）聚焦时按键优先交给 widget（super 先分发），
     * 无焦点时聊天键（默认 T）打开游戏内聊天框——Esc/Enter 返回大厅。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (Minecraft.getInstance().options.keyChat.matches(keyCode, scanCode)) {
            openChatPending = true;
            return true;
        }
        return false;
    }

    /** 被聊天框/子界面替换时取消待打开聊天，避免返回大厅后误弹聊天框。 */
    @Override
    public void removed() {
        super.removed();
        openChatPending = false;
    }

    // ---------------- 滚动（滚轮 + 滚动条点击/拖拽，房间视图小窗口用） ----------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int limit = scrollLimit();
        if (limit > 0) {
            scroll += (float) verticalAmount * 10;
            scroll = Math.max(-limit, Math.min(0, scroll));
            rebuildLobby();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** 点击滚动条（轨道/滑块）跳转并进入拖拽；点击房间码区域复制到剪贴板。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hitScrollbar(mouseX, mouseY)) {
            draggingScroll = true;
            dragStartMouseY = mouseY;
            dragStartScroll = GuiUtil.scrollFromY((int) mouseY, scrollbarY(), scrollbarH(), scrollLimit());
            scroll = -dragStartScroll;
            rebuildLobby();
            return true;
        }
        if (button == 0 && copyCodeAt(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 拖拽滚动条：按鼠标位移增量换算滚动偏移（clamp 到合法范围），每帧重建控件。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            int limit = scrollLimit();
            int target = dragStartScroll
                    + (int) ((mouseY - dragStartMouseY) * limit / GuiUtil.sliderRange(scrollbarH(), limit));
            scroll = -Math.max(0, Math.min(target, limit));
            rebuildLobby();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ---------------- 房间码复制（房间信息区） ----------------

    /** 点击房间码文本区域 → 复制到剪贴板并提示（房间信息区）。 */
    private boolean copyCodeAt(double mouseX, double mouseY) {
        if (!inRoomState()) {
            return false;
        }
        // 房间信息区：房间码行（各游戏行位置不同，由子类提供点击区域）
        int[] rect = roomInfoCodeRect();
        String code = currentRoomCode();
        if (rect != null && code != null
                && mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3]) {
            copyCode(code);
            return true;
        }
        return false;
    }

    private void copyCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        lobbyChat(Component.translatable("wifi_card_games.common.lobby.code_copied", code));
    }

    // ---------------- 渲染辅助 ----------------

    /** 顶部标题条（子类 render 在 super.render 之前调用，按钮渲染在标题条之上）。 */
    protected void drawTitleBar(GuiGraphics g) {
        g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, Component.translatable(lobbyTitleKey()), width / 2, 9, 0xFFFFD700);
    }

    /** 大厅标题翻译键（子类提供，如 wifi_card_games.ddz.lobby.title）。 */
    protected abstract String lobbyTitleKey();

    /** 房间视图底板（信息区 + 按钮区背景；super.render 之前调用，按钮绘制在底板之上）。 */
    protected abstract void drawRoomViewBg(GuiGraphics g);

    /** 未进房提示区（创建区下方，super.render 之前调用）：邀请提示 + 房间列表入口提示。
     *  配色：提示标签黄色、普通文字白色、命令青色（可读性优先）。 */
    protected void drawLobbyHints(GuiGraphics g) {
        int cx = width / 2;
        int top = contentTop();
        g.fill(cx - 180, top + 88, cx + 180, top + 162, 0x55000000);
        g.drawCenteredString(this.font,
                Component.translatable("wifi_card_games.common.lobby.hint_create").withStyle(ChatFormatting.WHITE),
                cx, top + 94, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
                Component.translatable("wifi_card_games.common.lobby.hint_tip").withStyle(ChatFormatting.YELLOW)
                        .append(Component.translatable("wifi_card_games.common.lobby.hint_owner").withStyle(ChatFormatting.WHITE))
                        .append(Component.translatable("wifi_card_games.common.lobby.cmd_invite").withStyle(ChatFormatting.AQUA))
                        .append(Component.translatable("wifi_card_games.common.lobby.hint_invite").withStyle(ChatFormatting.WHITE)),
                cx, top + 108, 0xFFFFFFFF);
        g.drawCenteredString(this.font,
                Component.translatable("wifi_card_games.common.lobby.hint_rooms_label").withStyle(ChatFormatting.YELLOW)
                        .append(Component.translatable("wifi_card_games.common.lobby.hint_rooms").withStyle(ChatFormatting.WHITE)),
                cx, top + 122, 0xFFFFFFFF);
    }

    // ---------------- 滚动条几何（与绘制一致） ----------------

    /** 滚动条轨道左缘 x（lx + 350 = cx - 172 + 350）。 */
    private int scrollbarX() {
        return width / 2 + 178;
    }

    /** 滚动条轨道顶 y（房间视图由子类覆写为信息区底，随滚动偏移）。 */
    protected int scrollbarTrackTop() {
        return 130;
    }

    private int scrollbarY() {
        return scrollbarTrackTop();
    }

    private int scrollbarH() {
        return height - 16 - scrollbarY();
    }

    /** 房间视图滚动条 + 滚动提示（子类房间渲染时调用；轨道顶由 scrollbarTrackTop 提供）。 */
    protected void drawRoomScrollbar(GuiGraphics g) {
        if (scrollLimit() > 0) {
            GuiUtil.drawScrollbar(g, scrollbarX(), scrollbarY(), scrollbarH(), (int) -scroll, scrollLimit());
            g.drawCenteredString(this.font, Component.translatable("wifi_card_games.common.lobby.scroll_hint"),
                    width / 2, height - 14, 0xFF888888);
        }
    }

    /** 命中滚动条轨道（含滑块）区域（轨道宽 2px，判定放宽到 6px 便于点击）。 */
    private boolean hitScrollbar(double mouseX, double mouseY) {
        return mouseX >= scrollbarX() - 2 && mouseX < scrollbarX() + 4
                && mouseY >= scrollbarY() && mouseY < scrollbarY() + scrollbarH();
    }

    /** 滚动后重建全部控件（保留输入框内容，位置随 contentTop 变化）。 */
    protected void rebuildLobby() {
        String prevCode = codeBox != null ? codeBox.getValue() : "";
        clearWidgets();
        init();
        if (codeBox != null && !prevCode.isEmpty()) {
            codeBox.setValue(prevCode);
        }
    }

    // ---------------- 关闭 ----------------

    /** 关闭大厅（Esc）：从菜单进入本大厅后关闭时，若其它游戏有进行中的会话则恢复其界面；
     *  等待玩家中关闭时提示可通过命令/点击重新打开。 */
    @Override
    public void onClose() {
        if (GameMenuClient.tryRestoreOtherSession(gameId())) {
            return;
        }
        if (inRoomState()) {
            reopenHint();
        }
        super.onClose();
    }
}
