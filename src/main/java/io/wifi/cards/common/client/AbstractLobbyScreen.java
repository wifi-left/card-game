package io.wifi.cards.common.client;

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
 * 小游戏等候大厅通用基类（纯客户端）：三个游戏大厅（斗地主/UNO/棋类）共享的
 * 结构与交互，游戏特有的创建区/房间视图由子类实现。
 * <p>统一提供：</p>
 * <ul>
 *   <li>标题条（右上角"主菜单"按钮，标题背景区域内）</li>
 *   <li>公开房间列表区：每 20 tick 轮询、行操作按钮（加入/旁观/已结束）、
 *       行文本渲染、滚动 + 滚动条（支持点击跳转与鼠标拖拽）</li>
 *   <li>房间码复制：点击列表行房间码或房间信息区的房间码 → 复制到剪贴板并提示</li>
 *   <li>关闭大厅时恢复其它游戏会话（onClose 模板）</li>
 * </ul>
 * 滚动方向统一为负方向（scroll ∈ [-maxScroll, 0]，与各游戏原实现一致）。
 */
public abstract class AbstractLobbyScreen extends Screen {
    /** 列表行操作按钮宽度。 */
    protected static final int LIST_BTN_W = 54;
    /** 列表行高。 */
    protected static final int ROW_H = 22;

    /** 输入房间码输入框（子类在 buildContent 中创建，滚动重建保留内容）。 */
    protected EditBox codeBox;

    /** 内容区滚动偏移（≤0，小窗口内容超高时滚轮上移；含房间列表与部分子类房间视图）。 */
    protected float scroll;
    /** 房间列表摘要（内容变化才重建，避免每 tick 闪烁）。 */
    private String listSignature = "";
    /** 房间列表轮询计数（每 20 tick 请求一次服务端快照）。 */
    private int queryCounter;
    /** 滚动条拖拽状态（按下时的鼠标 y / 滚动偏移，用于增量换算）。 */
    private boolean draggingScroll;
    private double dragStartMouseY;
    private int dragStartScroll;
    /** 待打开聊天框（延迟到 tick 执行，避免同按键的字符事件被新聊天框接收）。 */
    private boolean openChatPending;

    protected AbstractLobbyScreen(String title) {
        super(Component.literal(title));
    }

    // ---------------- 子类钩子（游戏特有） ----------------

    /** 游戏 id（GameRegistry 常量，会话恢复/偏好用）。 */
    protected abstract String gameId();

    /** 客户端是否已在房间中（决定创建区/房间视图与列表区切换）。 */
    protected abstract boolean inRoomState();

    /** 内容区顶部 y（随滚动偏移）。 */
    protected abstract int contentTop();

    /** 构建内容区控件（创建区按钮/房间视图按钮；不含主菜单与列表行，由基类统一添加）。 */
    protected abstract void buildContent();

    /** 发送房间列表轮询请求（LobbyQueryC2S）。 */
    protected abstract void sendRoomQuery();

    /** 房间列表数据（客户端缓存）。 */
    protected abstract List<? extends RoomEntry> lobbyRoomList();

    /** 加入房间（列表行"加入"按钮）。 */
    protected abstract void joinRoom(String code);

    /** 旁观房间（列表行"旁观"按钮）。 */
    protected abstract void spectateRoom(String code);

    /** 聊天栏消息（复制提示等，带游戏前缀）。 */
    protected abstract void lobbyChat(String message);

    /** 当前房间码（房间信息区复制用；null=不在房间）。 */
    protected abstract String currentRoomCode();

    /** 房间信息区房间码的可点击区域 {x1,y1,x2,y2}；null=不可点击。 */
    protected abstract int[] roomInfoCodeRect();

    /** 关闭大厅提示（等待玩家中关闭时）。 */
    protected abstract void reopenHint();

    /** 列表区相对内容区的纵向偏移（含标题空间；棋类无提示区偏移较小）。 */
    protected int listTopOffset() {
        return 134;
    }

    /** 滚动上限（0=不可滚动）；UNO 房间视图超高时覆写返回房间区上限。 */
    protected int scrollLimit() {
        return inRoomState() ? 0 : maxScroll();
    }

    /** 房间操作按钮区底部 y（"关闭界面"按钮放置位置，子类提供）。 */
    protected abstract int roomActionBottomY();

    // ---------------- 公共布局 ----------------

    /** 内容区左缘 x（两列布局/列表行共用）。 */
    protected int contentLeft() {
        return width / 2 - 172;
    }

    /** 房间列表区顶部 y（创建区/提示区下方）。 */
    protected int listTop() {
        return contentTop() + listTopOffset();
    }

    /** 列表区可显示的行数（按窗口高度）。 */
    protected int listMaxRows() {
        return Math.max(1, (height - listTop() - 20) / ROW_H);
    }

    /** 内容区底部 y（列表末行 + 边距）。 */
    protected int contentBottom() {
        return contentTop() + 250;
    }

    /** 内容超高时允许的滚动量（0 = 无需滚动）。 */
    protected int maxScroll() {
        return Math.max(0, contentBottom() - (height - 30));
    }

    // ---------------- init / 轮询 / 重建 ----------------

    @Override
    protected void init() {
        clearWidgets(); // 滚动重建时防重复添加
        // 返回小游戏菜单（发刷新请求，服务端回发菜单数据打开菜单界面）；
        // 位置：标题背景区域（0..26px）右上角，Y 轴居中
        addRenderableWidget(Button.builder(Component.literal("主菜单"), b ->
                        ClientPlayNetworking.send(new MenuQueryC2S()))
                .bounds(width - 106, 3, 100, 20).build());
        buildContent();
        buildRoomListButtons();
        if (inRoomState()) {
            addCloseLobbyButton();
        }
    }

    /** 等待房间视图："关闭界面"按钮（关闭大厅界面但保留房间，输入 /xxx 或 /cardgames 可重新打开）。 */
    private void addCloseLobbyButton() {
        addRenderableWidget(Button.builder(Component.literal("关闭界面"), b -> closeLobbyKeepRoom())
                .bounds(width / 2 - 80, roomActionBottomY(), 160, 20).build());
    }

    /** 关闭大厅界面但保留房间（不发送离开房间请求，服务端房间与会话原样保留）。 */
    private void closeLobbyKeepRoom() {
        reopenHint(); // 提示如何重新打开
        Minecraft.getInstance().setScreen(null);
    }

    /** 列表行操作按钮（等待中可加入 / 对局中可旁观 / 已结束禁用）。 */
    private void buildRoomListButtons() {
        if (inRoomState()) {
            return;
        }
        List<? extends RoomEntry> list = lobbyRoomList();
        int lx = contentLeft();
        int ly = listTop();
        int maxRows = listMaxRows();
        for (int i = 0; i < Math.min(list.size(), maxRows); i++) {
            RoomEntry e = list.get(i);
            int y = ly + i * ROW_H;
            if (e.status() == 0) {
                addRenderableWidget(Button.builder(Component.literal("加入"), b ->
                                joinRoom(e.code()))
                        .bounds(lx, y, LIST_BTN_W, 20).build());
            } else if (e.status() == 1) {
                addRenderableWidget(Button.builder(Component.literal("旁观"), b ->
                                spectateRoom(e.code()))
                        .bounds(lx, y, LIST_BTN_W, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("已结束"), b -> {
                        })
                        .bounds(lx, y, LIST_BTN_W, 20).build()).active = false;
            }
        }
    }

    /** 大厅内每 20 tick 轮询房间列表（服务端快照下发生成，无专门推送通道）；
     *  已在房间等待中时界面为房间信息视图，无需刷新列表，停止轮询。 */
    @Override
    public void tick() {
        super.tick();
        // 延迟打开聊天框（等本次按键的字符事件处理完毕）
        if (openChatPending) {
            openChatPending = false;
            Minecraft.getInstance().setScreen(new CardGameChatScreen(this));
        }
        if (++queryCounter >= 20) {
            queryCounter = 0;
            if (!inRoomState()) {
                sendRoomQuery();
            }
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

    /** 房间列表下发：摘要（含行文本，成员名变化也触发刷新）变化才重建（保留输入框内容）。 */
    public void onRoomListChanged() {
        StringBuilder sb = new StringBuilder();
        for (RoomEntry e : lobbyRoomList()) {
            sb.append(e.code()).append(e.status()).append(e.line());
        }
        String sig = sb.toString();
        if (!sig.equals(listSignature)) {
            listSignature = sig;
            rebuildLobby();
        }
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

    // ---------------- 滚动（滚轮 + 滚动条点击/拖拽） ----------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int limit = scrollLimit();
        if (limit > 0) {
            scroll -= (float) verticalAmount * 10;
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
            dragStartScroll = GuiUtil.scrollFromY((int) mouseY, scrollbarY(), scrollbarH(), maxScroll());
            scroll = -dragStartScroll;
            return true;
        }
        if (button == 0 && copyCodeAt(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 拖拽滚动条：按鼠标位移增量换算滚动偏移（clamp 到合法范围）。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            int target = dragStartScroll
                    + (int) ((mouseY - dragStartMouseY) * maxScroll() / GuiUtil.sliderRange(scrollbarH(), maxScroll()));
            scroll = -Math.max(0, Math.min(target, maxScroll()));
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

    // ---------------- 房间码复制 ----------------

    /** 点击房间码文本区域 → 复制到剪贴板并提示（列表行 + 房间信息区）。 */
    private boolean copyCodeAt(double mouseX, double mouseY) {
        if (!inRoomState()) {
            // 列表行：房间码金色区域（lx+60 .. lx+96）
            int lx = contentLeft();
            int idx = (int) ((mouseY - listTop()) / ROW_H);
            List<? extends RoomEntry> list = lobbyRoomList();
            if (idx >= 0 && idx < list.size() && mouseX >= lx + 60 && mouseX < lx + 96) {
                copyCode(list.get(idx).code());
                return true;
            }
        } else {
            // 房间信息区：房间码行（各游戏行位置不同，由子类提供点击区域）
            int[] rect = roomInfoCodeRect();
            String code = currentRoomCode();
            if (rect != null && code != null
                    && mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3]) {
                copyCode(code);
                return true;
            }
        }
        return false;
    }

    private void copyCode(String code) {
        Minecraft.getInstance().keyboardHandler.setClipboard(code);
        lobbyChat("已复制房间码 " + code);
    }

    // ---------------- 渲染辅助 ----------------

    /** 顶部标题条（子类 render 在 super.render 之前调用，按钮渲染在标题条之上）。 */
    protected void drawTitleBar(GuiGraphics g) {
        g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, lobbyTitle(), width / 2, 9, 0xFFFFD700);
    }

    /** 大厅标题文字（子类提供，如"斗地主大厅"）。 */
    protected abstract String lobbyTitle();

    /** 房间列表区（底板 + 提示 + 标题 + 行文本 + 滚动条 + 滚动提示；行操作按钮由 init 构建）。 */
    protected void drawRoomList(GuiGraphics g) {
        int lx = contentLeft();
        int ly = listTop();
        int maxRows = listMaxRows();
        List<? extends RoomEntry> list = lobbyRoomList();
        int shown = Math.min(list.size(), maxRows);
        // 列表区底板（统一半透明黑底：提示两行 + 标题 + 列表行；与菜单/房间信息区视觉一致，
        // 空列表也画最小高度，避免文本浮在游戏世界上）
        int panelBottom = ly + Math.max(shown, 3) * ROW_H + 6;
        g.fill(lx - 4, ly - 42, lx + 354, panelBottom, 0x44000000);
        // 提示两行（统一文案：邀请好友 + 邀请命令）
        g.drawCenteredString(this.font, "创建房间邀请好友一起玩，或输入房间码加入", width / 2, ly - 38, 0xFFAAAAAA);
        g.drawCenteredString(this.font, "提示：房主可用 /cardgames invite <玩家名> 邀请", width / 2, ly - 25, 0xFF777777);
        // 列表区标题（含数量）+ 复制提示（房间码金色 / 信息灰色）
        g.drawCenteredString(this.font, "房间列表（" + list.size() + " 个）", width / 2, ly - 12, 0xFFFFD700);
        g.drawString(this.font, "点击房间码复制", width / 2 + 172 - this.font.width("点击房间码复制"), ly - 13, 0xFF888888, true);
        if (list.isEmpty()) {
            g.drawCenteredString(this.font, "暂无公开房间，创建第一个房间吧", width / 2, ly + 10, 0xFF888888);
        } else {
            for (int i = 0; i < shown; i++) {
                RoomEntry e = list.get(i);
                int y = ly + i * ROW_H;
                // 行文本垂直居中（对齐 20px 按钮中心：行高 22、字高 9 → y+6）；
                // 房间码金色，信息灰色（已结束行更暗）
                g.drawString(this.font, e.code(), lx + 60, y + 6, 0xFFFFD700, true);
                g.drawString(this.font, font.plainSubstrByWidth(e.line(), 230), lx + 96, y + 6,
                        e.status() == 2 ? 0xFF999999 : 0xFFDDDDDD, true);
            }
            if (list.size() > maxRows) {
                g.drawCenteredString(this.font, "滚动滚轮查看全部房间", width / 2, height - 30, 0xFF888888);
            }
            // 滚动条（内容超高可滚动时，滑块比例 = 可视/内容）
            if (maxScroll() > 0) {
                GuiUtil.drawScrollbar(g, scrollbarX(), scrollbarY(), scrollbarH(), (int) -scroll, maxScroll());
            }
        }
        if (maxScroll() > 0) {
            g.drawCenteredString(this.font, "内容超出屏幕，滚动滚轮查看", width / 2, height - 14, 0xFF888888);
        }
    }

    // ---------------- 滚动条几何（与绘制一致） ----------------

    /** 滚动条轨道左缘 x（lx + 350 = cx - 172 + 350）。 */
    private int scrollbarX() {
        return width / 2 + 178;
    }

    private int scrollbarY() {
        return listTop() - 14;
    }

    private int scrollbarH() {
        return height - 16 - scrollbarY();
    }

    /** 命中滚动条轨道（含滑块）区域（轨道宽 2px，判定放宽到 6px 便于点击）。 */
    private boolean hitScrollbar(double mouseX, double mouseY) {
        return mouseX >= scrollbarX() - 2 && mouseX < scrollbarX() + 4
                && mouseY >= scrollbarY() && mouseY < scrollbarY() + scrollbarH();
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

    // ---------------- 房间条目 ----------------

    /** 一条大厅房间条目。status：0=等待中可加入 1=对局中可旁观 2=已结束。 */
    public record RoomEntry(String code, String line, byte status) {
    }
}
