package io.wifi.cards.uno.gui;

import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoColor;
import io.wifi.cards.uno.model.UnoGamePhase;
import io.wifi.cards.uno.network.UnoPackets.CatchUnoC2S;
import io.wifi.cards.uno.network.UnoPackets.DeclareUnoC2S;
import io.wifi.cards.uno.network.UnoPackets.DrawC2S;
import io.wifi.cards.uno.network.UnoPackets.HistoryC2S;
import io.wifi.cards.uno.network.UnoPackets.LeaveRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.PassC2S;
import io.wifi.cards.uno.network.UnoPackets.PlayCardC2S;
import io.wifi.cards.uno.network.UnoPackets.SpectateLeaveC2S;
import io.wifi.cards.uno.network.UnoPackets.ToggleTrustC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * UNO 牌桌界面（最多 10 人）：
 * <ul>
 * <li>顶部：房间码 + 阶段 + 方向 + 轮到谁/倒计时</li>
 * <li>中央：抽牌堆（自己回合可点击抽牌）+ 弃牌堆顶牌（万能牌显示所选颜色）+ 事件提示</li>
 * <li>左右两侧：对手竖排面板（头像 + 名字 + 剩余张数，当前回合高亮，UNO/可抓标记）</li>
 * <li>底部：自己手牌（点击选中上移高亮）</li>
 * <li>右下：动态操作按钮（出牌/抽牌/跳过/喊UNO/抓UNO/托管/退出）</li>
 * <li>万能牌出牌时中央弹 4 色选色弹层；旁观者左侧面板透视各家手牌</li>
 * </ul>
 */
public class UnoGameScreen extends Screen {
    // 牌面尺寸：固定 34x50，重叠 20px 布局（GAP=20）
    private static final int CARD_W = 34;
    private static final int CARD_H = 50;
    private static final int CARD_GAP = 20;
    private static final int SELECT_OFFSET = 12;
    // 中央牌堆尺寸
    private static final int PILE_W = 30;
    private static final int PILE_H = 44;
    private static final int PILE_TOP = 96;
    // 旁观者手牌面板（左侧竖排，内容超高时滚轮滚动 + 滚动条）
    // 底部让出左下角"规则/历史"按钮区（按钮 y=height-26 高 20）
    private static final int SPECTATOR_PANEL_W = 146;
    private static final int SPECTATOR_PANEL_TOP = 36;
    private static final int SPECTATOR_PANEL_BOTTOM_MARGIN = 34;

    private final Set<Integer> selected = new HashSet<>();
    private final List<Button> actionButtons = new java.util.ArrayList<>();
    private int buttonSignature = -1;
    private int countdown = 30;
    /** 拖拽选牌：上次处理的牌下标（-1=不在牌上），避免同一张牌被反复切换。 */
    private int lastDragCard = -1;
    /** 拖拽时上次鼠标位置（GUI 缩放坐标），用于路径采样插值防漏牌。 */
    private double lastDragX;
    private double lastDragY;
    /** 本次按下是否始于手牌（从按钮/空白按下拖动不处理手牌，避免误选）。 */
    private boolean dragArmed;
    /** 退出确认弹层：成员点「退出」后先询问（旁观者退出无需确认）。 */
    private boolean confirmingExit;
    /** 万能牌选色弹层：出牌前先选颜色。 */
    private boolean confirmingColor;
    private int colorCardId = -1;
    /** 旁观者手牌面板滚动偏移（≥0，滚轮在面板上滚动；内容超高时查看全部手牌）。 */
    private float spectatorScroll;

    public UnoGameScreen() {
        super(Component.literal("UNO" + (UnoClientState.INSTANCE.debugView ? "（调试）" : "")));
    }

    /** 旁观模式：服务端以 mySeat=-1 表示只读旁观（无手牌、无操作权）。 */
    private boolean isSpectator() {
        return UnoClientState.INSTANCE.mySeat < 0;
    }

    @Override
    protected void init() {
        // 左下角：规则 / 事件历史（子界面返回时回到本打牌界面，并渲染本界面为背景）
        addRenderableWidget(Button
                .builder(Component.literal("规则"),
                        b -> Minecraft.getInstance().setScreen(new UnoRulesScreen(UnoGameScreen.this)))
                .bounds(8, height - 26, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("历史"), b -> {
            ClientPlayNetworking.send(new HistoryC2S());
            Minecraft.getInstance().setScreen(new UnoHistoryScreen(UnoGameScreen.this));
        }).bounds(72, height - 26, 60, 20).build());
        // 操作按钮（退出/托管/出牌等）在 init 时立即重建：
        // resize（窗口/全屏/GUI 缩放变化）会重建整个 widget 树，
        // 若只等 tick 的签名变化重建，旁观者（签名恒定）的「退出旁观」按钮会丢失且无法恢复
        rebuildActionButtons();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 关闭牌局界面（Esc）：选色/退出确认弹层打开时先逐级取消，再按才关闭界面。 */
    @Override
    public void onClose() {
        if (confirmingColor) {
            confirmingColor = false; // 第一下 Esc：取消选色弹层（保留选中，可重新出牌）
            return;
        }
        if (confirmingExit) {
            confirmingExit = false; // 第一下 Esc：取消确认弹层（按钮由 tick 签名重建）
            return;
        }
        // 调试旁观（无真实房间/会话）：直接清空本地状态回到大厅
        if (UnoClientState.INSTANCE.debugView) {
            UnoClientState.INSTANCE.clearAll();
            Minecraft.getInstance().setScreen(new UnoLobbyScreen());
            return;
        }
        UnoClientState.chatReopenHint("关闭牌局界面");
        super.onClose();
    }

    /**
     * 被替换为子界面时调用：父类 removed() 会清空全部 widgets（含操作按钮），
     * 返回本界面时因签名未变不会重建 → 强制重置签名，下个 tick 重建按钮。
     */
    @Override
    public void removed() {
        super.removed();
        buttonSignature = -1;
    }

    /** 窗口 resize：父类会再次调用 init()，先清空全部再重建。 */
    @Override
    public void resize(Minecraft mc, int width, int height) {
        clearWidgets();
        actionButtons.clear();
        buttonSignature = -1;
        super.resize(mc, width, height);
    }

    // ---------------- tick / 按钮 ----------------

    @Override
    public void tick() {
        super.tick();
        UnoClientState s = UnoClientState.INSTANCE;
        // 用服务端下发的截止游戏刻计算剩余秒数：客户端 level.getGameTime() 与服务端同步，
        // 倒计时不受本地帧率/网络延迟影响
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && s.turnEndGameTime > 0) {
            long remainingTicks = s.turnEndGameTime - mc.level.getGameTime();
            countdown = (int) Math.max(0, (remainingTicks + 19) / 20); // 向上取整
        }
        // 服务端拒绝了最近一次出牌：清空选中，便于玩家重新选牌
        if (s.playRejected) {
            s.playRejected = false;
            selected.clear();
            buttonSignature = -1;
        }
        // 选色弹层防残留：回合已过（超时自动行动/他人行动）或对局已结束时关闭，
        // 避免点颜色发出必然被服务端拒绝的过期出牌
        if (confirmingColor && (!s.isMyTurn() || s.phase != UnoGamePhase.PLAYING)) {
            confirmingColor = false;
            colorCardId = -1;
            buttonSignature = -1;
        }
        // 阶段/轮到谁/托管/抽牌状态/选牌/UNO 状态/弹层变化时重建按钮（旁观模式按钮恒定，用独立签名区分）
        int signature = isSpectator() ? -50000
                : (s.phase.ordinal() * 100 + (s.currentSeat + 1) * 10 + (s.myTrust ? 1 : 0)) * 2
                        + (selected.isEmpty() ? 0 : 1)
                        + (s.drawnPlayable ? 1000 : 0)
                        + (s.mySeat >= 0 && s.mySeat < s.unoCatchable.length && s.unoCatchable[s.mySeat]
                                && !s.declaredUno[s.mySeat] ? 2000 : 0)
                        + (catchableTarget() >= 0 ? 4000 + catchableTarget() : 0)
                        + (confirmingExit ? 8000 : 0) + (confirmingColor ? 16000 : 0);
        if (signature != buttonSignature) {
            buttonSignature = signature;
            rebuildActionButtons();
        }
    }

    /** 第一个可被抓（剩 1 张未喊 UNO）的对手座位；无则 -1。 */
    private int catchableTarget() {
        UnoClientState s = UnoClientState.INSTANCE;
        for (int i = 0; i < s.unoCatchable.length; i++) {
            if (i != s.mySeat && s.unoCatchable[i] && !s.declaredUno[i]) {
                return i;
            }
        }
        return -1;
    }

    private void rebuildActionButtons() {
        for (Button b : actionButtons) {
            removeWidget(b);
        }
        actionButtons.clear();
        UnoClientState s = UnoClientState.INSTANCE;
        int x = width - 100;
        int y = height - 150;
        // 旁观模式：只读观看，仅提供「退出旁观」（服务端清理旁观关系并回到大厅）
        if (isSpectator()) {
            actionButtons.add(button(x, y, "退出旁观", b -> sendUnspectate(), true));
            return;
        }
        // 常驻行：退出游戏 + 托管（整局可用，随时可退出/取消托管）
        if (s.phase == UnoGamePhase.PLAYING) {
            if (confirmingExit) {
                // 退出确认弹层：仅「确认退出 / 取消」（确认后座位转机器人托管，对局继续）。
                // 回调只改字段，按钮由 tick 签名变化统一重建（避免点击遍历期间增删 widget）
                actionButtons.add(button(x - 95, y - 26, "确认退出", b -> {
                    confirmingExit = false;
                    sendLeave();
                }, true));
                actionButtons.add(button(x, y - 26, "取消", b -> confirmingExit = false, true));
                return;
            }
            if (confirmingColor) {
                // 选色弹层：中央 4 色按钮 + 「取消」（保留选中）
                for (UnoColor color : new UnoColor[]{UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE}) {
                    final UnoColor c = color;
                    Button b = Button.builder(Component.literal(c.displayName()), btn -> sendPlayWithColor(c))
                            .bounds(colorBtnX(c), colorBtnY(), 60, 20).build();
                    addRenderableWidget(b);
                    actionButtons.add(b);
                }
                actionButtons.add(button(x, y - 26, "取消", b -> confirmingColor = false, true));
                return;
            }
            actionButtons.add(button(x - 95, y - 26, "退出", b -> confirmingExit = true, true));
            actionButtons.add(button(x, y - 26, s.myTrust ? "取消托管" : "托管", b -> sendTrust(), true));
        }
        if (s.phase == UnoGamePhase.PLAYING) {
            // UNO 喊牌（本人剩 1 张且未喊）：任何时候可喊
            if (s.mySeat >= 0 && s.mySeat < s.unoCatchable.length
                    && s.unoCatchable[s.mySeat] && !s.declaredUno[s.mySeat]) {
                actionButtons.add(button(x, y + 52, "喊 UNO！", b -> sendDeclareUno(), true));
            }
            // 抓 UNO（有对手剩 1 张未喊）：抓住罚对方 2 张
            int target = catchableTarget();
            if (target >= 0) {
                actionButtons.add(button(x, y + 78, "抓 " + shortName(target) + " UNO", b -> sendCatch(target), true));
            }
        }
        if (!s.isMyTurn()) {
            return;
        }
        if (s.phase == UnoGamePhase.PLAYING) {
            actionButtons.add(button(x, y, "出牌", b -> sendPlay(), !selected.isEmpty()));
            if (s.drawnPlayable) {
                actionButtons.add(button(x, y + 26, "跳过", b -> sendPass(), true));
            } else {
                actionButtons.add(button(x, y + 26, "抽牌", b -> sendDraw(), true));
            }
        }
    }

    private Button button(int x, int y, String label, Button.OnPress onPress, boolean active) {
        Button b = Button.builder(Component.literal(label), onPress).bounds(x, y, 90, 20).build();
        b.active = active;
        addRenderableWidget(b);
        return b;
    }

    /** 对手短名（过长截断，按钮标签用）。 */
    private String shortName(int seat) {
        String name = UnoClientState.INSTANCE.nameOf(seat);
        return this.font.plainSubstrByWidth(name, 40);
    }

    // ---------------- 操作 ----------------

    /** 出牌：普通牌直接发；万能牌先弹选色弹层（再发牌）。 */
    private void sendPlay() {
        UnoClientState s = UnoClientState.INSTANCE;
        if (selected.isEmpty()) {
            return;
        }
        UnoCard card = null;
        for (UnoCard c : s.hand) {
            if (selected.contains(c.id())) {
                card = c;
                break;
            }
        }
        if (card == null) {
            return;
        }
        if (card.isWild()) {
            confirmingColor = true;
            colorCardId = card.id();
            return;
        }
        ClientPlayNetworking.send(new PlayCardC2S(card.id(), (byte) 0));
        selected.clear();
    }

    /** 选色按钮回调：以所选颜色打出万能牌。 */
    private void sendPlayWithColor(UnoColor color) {
        if (colorCardId >= 0) {
            ClientPlayNetworking.send(new PlayCardC2S(colorCardId, (byte) color.ordinal()));
        }
        confirmingColor = false;
        colorCardId = -1;
        selected.clear();
    }

    private void sendDraw() {
        ClientPlayNetworking.send(new DrawC2S());
    }

    private void sendPass() {
        ClientPlayNetworking.send(new PassC2S());
    }

    private void sendDeclareUno() {
        ClientPlayNetworking.send(new DeclareUnoC2S());
    }

    private void sendCatch(int targetSeat) {
        ClientPlayNetworking.send(new CatchUnoC2S((byte) targetSeat));
    }

    private void sendTrust() {
        UnoClientState s = UnoClientState.INSTANCE;
        s.myTrust = !s.myTrust;
        ClientPlayNetworking.send(new ToggleTrustC2S(s.myTrust));
    }

    /** 退出游戏：座位由服务端转机器人托管，本客户端回到大厅。 */
    private void sendLeave() {
        ClientPlayNetworking.send(new LeaveRoomC2S());
    }

    /** 退出旁观：服务端清理旁观关系并下发房间关闭消息，回到大厅（调试模式仅本地清理）。 */
    private void sendUnspectate() {
        if (UnoClientState.INSTANCE.debugView) {
            UnoClientState.INSTANCE.clearAll();
            Minecraft.getInstance().setScreen(new UnoLobbyScreen());
            return;
        }
        ClientPlayNetworking.send(new SpectateLeaveC2S());
    }

    // ---------------- 鼠标选牌（事件驱动；路径采样防漏牌） ----------------

    /**
     * 手牌重叠间距：牌少用标准 20px；手牌多（罚牌累积可达 30~60 张）时自动收窄
     * （最小 4px），保证全部手牌在屏幕内可见可点。绘制与命中检测共用同一几何。
     */
    private int cardGap() {
        int n = UnoClientState.INSTANCE.hand.size();
        if (n <= 1) {
            return CARD_GAP;
        }
        int avail = Math.max(CARD_W + 4, width - 4);
        return Math.max(4, Math.min(CARD_GAP, (avail - CARD_W) / (n - 1)));
    }

    /** 手牌顶部 y：整体上移让出底部按钮区（牌底 height-34），
     *  手牌铺满全宽时左下"规则/历史"与右下"抓 UNO"按钮完全可见可点，互不遮挡。 */
    private int handY() {
        return height - CARD_H - 34;
    }

    /**
     * 命中检测：返回鼠标下的牌下标（顶层优先，即最右侧/已抬起的牌先命中），不在牌上返回 -1。
     * 坐标须为 GUI 缩放坐标。
     */
    private int cardIndexAt(double mouseX, double mouseY) {
        UnoClientState s = UnoClientState.INSTANCE;
        List<UnoCard> hand = s.hand;
        int n = hand.size();
        if (n == 0) {
            return -1;
        }
        int gap = cardGap();
        int totalW = CARD_W + (n - 1) * gap;
        int x0 = Math.max(2, (width - totalW) / 2);
        int y = handY();
        // 牌按从左到右绘制，右侧牌叠在左侧牌之上；倒序遍历保证点击到的是最顶上那张
        for (int i = n - 1; i >= 0; i--) {
            UnoCard c = hand.get(i);
            int cx = x0 + i * gap;
            int cy = selected.contains(c.id()) ? y - SELECT_OFFSET : y;
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    /** 切换一张牌的选中状态（未选中→选中，已选中→取消选中）。 */
    private void toggleCard(int idx) {
        UnoCard c = UnoClientState.INSTANCE.hand.get(idx);
        if (!selected.remove(c.id())) {
            selected.add(c.id());
        }
        buttonSignature = -1; // 触发按钮重建（出牌按钮可用性）
    }

    /** 抽牌堆点击区（自己回合可点抽牌；几何与 drawCenter 一致）。 */
    private boolean drawPileHit(double mouseX, double mouseY) {
        int x = drawPileX();
        return mouseX >= x && mouseX < x + PILE_W
                && mouseY >= PILE_TOP && mouseY < PILE_TOP + PILE_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmingExit || confirmingColor) {
            // 弹层中：不处理选牌/拖拽/抽牌，仅响应弹层按钮（super 转发给 widget）
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // 点击抽牌堆抽牌（自己回合且未抽过牌时）
            UnoClientState s = UnoClientState.INSTANCE;
            if (!isSpectator() && s.phase == UnoGamePhase.PLAYING && s.isMyTurn()
                    && !s.drawnPlayable && drawPileHit(mouseX, mouseY)) {
                sendDraw();
                return true;
            }
            // 按钮优先：手牌铺满全宽（罚牌累积 40+ 张）时会视觉覆盖左下"规则/历史"
            // 与右下操作按钮，先分发给 widget 保证按钮可点；未命中按钮再处理手牌选牌
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            // 点击：始终切换按下那张牌的选中状态；命中后消费点击以激活后续拖拽
            lastDragCard = -1;
            lastDragX = mouseX;
            lastDragY = mouseY;
            int idx = cardIndexAt(mouseX, mouseY);
            if (idx >= 0) {
                dragArmed = true; // 从手牌上按下：长按拖动才会滑选/滑取消
                toggleCard(idx);
                lastDragCard = idx; // 按下已处理，避免拖拽首事件重复切换
                return true;
            }
            dragArmed = false; // 从空白按下：拖动不处理手牌（避免误选）
            return false; // super 已在上方分发过，不重复
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 长按左键滑动：逐张切换经过的每张牌——所在牌未选中则选中（滑选），
     * 已选中则取消（滑取消），实时按该牌自身状态决定。
     * 鼠标快速滑动时单次事件可能跨越多张牌，这里沿「上次位置→当前位置」线段每 4px
     * 采样一次命中检测，保证中间牌不被漏掉；同一张牌在本次拖拽中只切换一次
     * （lastDragCard 去重，移开再滑回会再次切换）。
     */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragArmed) {
            double dx = mouseX - lastDragX;
            double dy = mouseY - lastDragY;
            double dist = Math.hypot(dx, dy);
            int steps = Math.max(1, (int) (dist / 4));
            for (int i = 1; i <= steps; i++) {
                double sx = lastDragX + dx * i / steps;
                double sy = lastDragY + dy * i / steps;
                int idx = cardIndexAt(sx, sy);
                if (idx >= 0 && idx != lastDragCard) {
                    toggleCard(idx);
                    lastDragCard = idx;
                }
            }
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            lastDragCard = -1; // 松手后下次拖拽重新计数，避免残留状态
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 旁观者手牌面板底部 y（让出左下角规则/历史按钮区）。 */
    private int spectatorPanelBottom() {
        return height - SPECTATOR_PANEL_BOTTOM_MARGIN;
    }

    /** 旁观者手牌面板滚轮滚动（鼠标悬停面板时生效；成员回合内滚轮无冲突用途）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isSpectator() && mouseX >= 4 && mouseX <= 6 + SPECTATOR_PANEL_W + 4
                && mouseY >= 34 && mouseY <= spectatorPanelBottom()) {
            spectatorScroll -= (float) verticalAmount * 20;
            spectatorScroll = Math.max(0, Math.min(spectatorScroll, spectatorMaxScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        drawTopInfo(g);
        drawOpponents(g);
        drawCenter(g);
        drawHand(g);
        if (isSpectator()) {
            drawSpectatorHands(g); // 旁观透视：左侧面板显示各家完整手牌
        }
        if (confirmingExit) {
            drawExitConfirm(g);
        }
        if (confirmingColor) {
            drawColorPicker(g);
        }
    }

    /** 退出确认弹层：半透明黑底 + 提示文本（按钮在右侧常驻行，见 rebuildActionButtons）。 */
    private void drawExitConfirm(GuiGraphics g) {
        int w = Math.min(340, width - 40);
        int h = 54;
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        g.fill(x0, y0, x0 + w, y0 + h, 0xE6000000); // 深色背景遮罩
        UnoGui.centeredShadow(g, this.font, width, "退出后座位将由机器人托管，对局继续", y0 + 10, 0xFFFFD700);
        UnoGui.centeredShadow(g, this.font, width, "确定要退出游戏吗？", y0 + 26, 0xFFFFFFFF);
    }

    /** 选色弹层顶部 y（与 drawColorPicker 一致）。 */
    private int colorPickerTop() {
        int h = 96;
        return Math.max(40, (height - h) / 2 - 40);
    }

    /** 选色按钮行 y（与 drawColorPicker 一致）。 */
    private int colorBtnY() {
        return colorPickerTop() + 72;
    }

    /** 选色按钮 x（4 色等距居中，与 drawColorPicker 一致）。 */
    private int colorBtnX(UnoColor color) {
        int bw = 60;
        int gap = 8;
        int total = bw * 4 + gap * 3;
        int bx = (width - total) / 2;
        return bx + color.ordinal() * (bw + gap);
    }

    /** 万能牌选色弹层：4 色按钮（widget）+ 背景面板 + 提示（选中的万能牌预览）。 */
    private void drawColorPicker(GuiGraphics g) {
        int w = Math.min(300, width - 40);
        int h = 96;
        int x0 = (width - w) / 2;
        int y0 = colorPickerTop();
        g.fill(x0, y0, x0 + w, y0 + h, 0xE6000000); // 深色背景遮罩
        UnoGui.centeredShadow(g, this.font, width, "万能牌：请选择颜色", y0 + 10, 0xFFFFD700);
        UnoCard card = colorCardId >= 0 ? UnoCard.byId(colorCardId) : null;
        if (card != null) {
            // 中央预览所选万能牌
            UnoGui.drawCard(g, card, width / 2 - 17, y0 + 24, 34, 44);
        }
        // 提示选色按钮行（按钮 widget 在 rebuildActionButtons 中创建）
        int by = colorBtnY();
        int bw = 60;
        for (UnoColor color : new UnoColor[]{UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE}) {
            int cx = colorBtnX(color);
            g.fill(cx, by, cx + bw, by + 20, 0x66000000); // 按钮底色（文字与点击由 widget 渲染）
        }
    }

    /** 顶部信息条：房间码/阶段/方向/轮到谁 + 倒计时（调试模式标注"（调试）"）。 */
    private void drawTopInfo(GuiGraphics g) {
        UnoClientState s = UnoClientState.INSTANCE;
        g.fill(0, 0, width, 30, 0x66000000);
        String left = (isSpectator() ? "旁观中 · " : "")
                + (s.debugView ? "UNO（调试）" : "UNO 房间 " + (s.roomCode == null ? "" : s.roomCode));
        g.drawString(this.font, left, 6, 8, 0xFFFFFFFF, true);
        if (s.phase == UnoGamePhase.PLAYING) {
            String dir = s.direction > 0 ? "→" : "←";
            UnoGui.centeredShadow(g, this.font, width, "出牌中 · 方向 " + dir, 8, 0xFFFFD700);
            // 倒计时仅在服务端下发了截止刻（turnEndGameTime>0）时显示；
            // 调试旁观数据无截止刻，避免显示虚假的"剩余 30 秒"
            String timeText = s.turnEndGameTime > 0 ? "（剩余 " + Math.max(0, countdown) + " 秒）" : "";
            String turnText = s.isMyTurn()
                    ? "轮到你" + timeText
                    : "轮到 " + s.nameOf(s.currentSeat) + timeText;
            if (s.myTrust) {
                turnText += "（托管中）"; // 托管状态标识：自动出牌中，避免误以为掉线/卡住
            }
            UnoGui.centeredShadow(g, this.font, width, turnText, 22,
                    s.isMyTurn() ? 0xFFFFFF55 : 0xFFAAAAAA);
        } else {
            UnoGui.centeredShadow(g, this.font, width, s.phase == UnoGamePhase.SETTLED ? "本局结束" : "等待开始…", 16, 0xFFFFD700);
        }
    }

    /** 对手竖排面板：左右两列均分（最多 10 人 9 对手）。 */
    private void drawOpponents(GuiGraphics g) {
        UnoClientState s = UnoClientState.INSTANCE;
        if (isSpectator()) {
            return; // 旁观者统一在左侧面板看全员手牌
        }
        int panelW = 120;
        int rowH = 46;
        List<Integer> opponents = new java.util.ArrayList<>();
        for (int i = 0; i < s.names.size(); i++) {
            if (i != s.mySeat) {
                opponents.add(i);
            }
        }
        int half = (opponents.size() + 1) / 2;
        int startY = 36;
        for (int i = 0; i < opponents.size(); i++) {
            int seat = opponents.get(i);
            boolean left = i < half;
            int x = left ? 6 : width - panelW - 6;
            int row = left ? i : i - half;
            drawOpponentPanel(g, seat, x, startY + row * rowH, panelW, rowH);
        }
    }

    /** 单个对手面板：头像 + 名字（回合高亮）+ 张数 + UNO/可抓/离线标记。 */
    private void drawOpponentPanel(GuiGraphics g, int seat, int x, int y, int w, int h) {
        UnoClientState s = UnoClientState.INSTANCE;
        boolean isTurn = s.currentSeat == seat;
        // 底框：当前回合亮框，其余半透明底
        g.fill(x, y, x + w, y + h, isTurn ? 0x44FFFF88 : 0x33000000);
        if (isTurn) {
            g.fill(x, y, x + w, y + 1, 0xFFFFFF55);
            g.fill(x, y + h - 1, x + w, y + h, 0xFFFFFF55);
        }
        drawHead(g, s.playerUuids.get(seat), x + 4, y + 2, 16);
        // 名字截断宽度预留右侧 UNO/可抓标记区（标记右对齐在 x+w-5 处，避免与名字相压）
        String name = s.nameOf(seat);
        name = this.font.plainSubstrByWidth(name, w - 60);
        g.drawString(this.font, name, x + 24, y + 3, isTurn ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        StringBuilder line = new StringBuilder(s.countOf(seat) + " 张");
        if (seat < s.connected.size() && !s.connected.get(seat)) {
            line.append(" · 离线");
        }
        g.drawString(this.font, line.toString(), x + 24, y + 18, 0xFFCCCCCC, true);
        // UNO / 可抓标记（右上角）：仅在手牌仍为 1 张时显示
        // （被罚牌离开 1 张状态后徽标不复位，纯显示问题）
        boolean declared = seat < s.declaredUno.length && s.declaredUno[seat] && s.countOf(seat) == 1;
        boolean catchable = seat < s.unoCatchable.length && s.unoCatchable[seat] && !declared;
        String mark = declared ? "UNO" : (catchable ? "可抓!" : "");
        if (!mark.isEmpty()) {
            int color = declared ? 0xFF55FF55 : 0xFFFF5555;
            g.drawString(this.font, mark, x + w - this.font.width(mark) - 5, y + 3, color, true);
        }
    }

    /** 中央信息面板：抽牌堆 + 弃牌堆顶牌 + 方向箭头 + 事件提示。 */
    private void drawCenter(GuiGraphics g) {
        UnoClientState s = UnoClientState.INSTANCE;
        int panelW = centerPanelW();
        int panelX = centerPanelX();
        // 面板底 156：完整包住牌堆（96~140）与牌堆标签（142~152）
        int panelBottom = 156;
        g.fill(panelX, 58, panelX + panelW, panelBottom, 0x55000000);
        // 抽牌堆（左侧）：自己回合且未抽牌时高亮可点击
        boolean drawable = !isSpectator() && s.phase == UnoGamePhase.PLAYING && s.isMyTurn() && !s.drawnPlayable;
        int pileX = drawPileX();
        g.fill(pileX - 1, PILE_TOP - 1, pileX + PILE_W + 1, PILE_TOP + PILE_H + 1,
                drawable ? 0xFFFFFF55 : 0xFF000000);
        UnoGui.drawCardBack(g, pileX, PILE_TOP, PILE_W, PILE_H);
        if (drawable) {
            UnoGui.centeredShadowAt(g, this.font, pileX + PILE_W / 2, "点击抽牌", PILE_TOP + PILE_H + 2, 0xFFFFFF55);
        } else {
            UnoGui.centeredShadowAt(g, this.font, pileX + PILE_W / 2, "抽牌堆", PILE_TOP + PILE_H + 2, 0xFFAAAAAA);
        }
        // 方向箭头
        int arrowX = pileX + PILE_W + 6;
        UnoGui.centeredShadowAt(g, this.font, arrowX + 8, s.direction > 0 ? "→" : "←",
                PILE_TOP + PILE_H / 2 - 5, 0xFFFFFFFF);
        // 弃牌堆顶牌（右侧）：万能牌显示所选颜色条
        int discX = arrowX + 22;
        g.fill(discX - 1, PILE_TOP - 1, discX + PILE_W + 1, PILE_TOP + PILE_H + 1, 0xFF000000);
        if (s.topCard != null) {
            UnoGui.drawCard(g, s.topCard, discX, PILE_TOP, PILE_W, PILE_H);
            if (s.topCard.isWild()) {
                // 当前有效颜色条
                g.fill(discX + 3, PILE_TOP + PILE_H - 5, discX + PILE_W - 3, PILE_TOP + PILE_H - 2,
                        UnoGui.colorHighlight(s.topColor));
            }
        } else {
            UnoGui.drawCardBack(g, discX, PILE_TOP, PILE_W, PILE_H);
        }
        // 事件提示（最新一条，截断防溢出）。位于牌堆（y=96~140）上方，
        // 不与其重叠（此前画在 y=128 会压在弃牌堆上）
        if (!s.lastEvent.isEmpty()) {
            String event = this.font.plainSubstrByWidth(s.lastEvent, panelW - 12);
            UnoGui.centeredShadowAt(g, this.font, panelX + panelW / 2, event, 64, 0xFFFFFF88);
        }
    }

    /** 中央面板宽（与 drawCenter 一致）。 */
    private int centerPanelW() {
        return Math.max(120, Math.min(260, width - 250));
    }

    /** 中央面板左缘（与 drawCenter 一致）。 */
    private int centerPanelX() {
        return Math.max(8, width / 2 - centerPanelW() / 2);
    }

    /** 抽牌堆左缘（与 drawCenter 一致）。 */
    private int drawPileX() {
        return centerPanelX() + Math.max(4, (centerPanelW() / 2 - PILE_W) / 2);
    }

    private void drawHand(GuiGraphics g) {
        UnoClientState s = UnoClientState.INSTANCE;
        List<UnoCard> hand = s.hand;
        int n = hand.size();
        if (n == 0) {
            return;
        }
        int gap = cardGap();
        int totalW = CARD_W + (n - 1) * gap;
        int x0 = Math.max(2, (width - totalW) / 2);
        int y = handY();
        for (int i = 0; i < n; i++) {
            UnoCard c = hand.get(i);
            int cx = x0 + i * gap;
            int cy = selected.contains(c.id()) ? y - SELECT_OFFSET : y;
            if (selected.contains(c.id())) {
                // 选中牌金色描边高亮
                g.fill(cx - 1, cy - 1, cx + CARD_W + 1, cy + CARD_H + 1, 0xFFFFD700);
            }
            UnoGui.drawCard(g, c, cx, cy, CARD_W, CARD_H);
        }
    }

    /**
     * 旁观透视：左侧面板按座位顺序展示各家完整手牌
     * （名字 + 张数 + 迷你牌行，放不下时自动换行）。
     * 内容超高（人多/手牌多）时面板右侧出现滚动条，滚轮在面板上滚动查看全部手牌。
     */
    private void drawSpectatorHands(GuiGraphics g) {
        UnoClientState s = UnoClientState.INSTANCE;
        if (s.spectatorHands.isEmpty()) {
            return; // 尚未收到手牌快照
        }
        int panelX = 6;
        int panelTop = SPECTATOR_PANEL_TOP;
        int panelBottom = spectatorPanelBottom();
        int viewH = panelBottom - panelTop;
        // 面板底框
        g.fill(panelX - 2, panelTop - 2, panelX + SPECTATOR_PANEL_W + 2, panelBottom, 0x33000000);
        // 滚动条（内容超高时）：右侧 2px 轨道 + 滑块
        int maxScroll = spectatorMaxScroll();
        spectatorScroll = Math.max(0, Math.min(spectatorScroll, maxScroll));
        if (maxScroll > 0) {
            int trackX = panelX + SPECTATOR_PANEL_W - 3;
            g.fill(trackX, panelTop, trackX + 2, panelBottom, 0x44000000);
            int thumbH = Math.max(20, viewH * viewH / spectatorContentHeight());
            int thumbY = panelTop + (int) ((viewH - thumbH) * (spectatorScroll / (float) maxScroll));
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xAAFFFFFF);
        }
        // 内容按滚动偏移绘制，并裁剪在面板区域内（不溢出到牌桌上）
        g.enableScissor(panelX - 2, panelTop - 2, SPECTATOR_PANEL_W + 4, panelBottom - (panelTop - 2));
        int cardW = 11;
        int cardH = 16;
        int gap = 1;
        int perRow = Math.max(1, (SPECTATOR_PANEL_W - 6) / (cardW + gap));
        int y = panelTop + 2 - (int) spectatorScroll;
        for (int i = 0; i < s.spectatorHands.size(); i++) {
            List<UnoCard> hand = s.spectatorHands.get(i);
            boolean isTurn = s.currentSeat == i;
            // UNO 状态标记：已喊(绿)/可抓(红，剩 1 张未喊仍在窗口期)——与名字分离绘制，颜色区分。
            // 仅在手牌仍为 1 张时显示（被罚牌离开 1 张状态后徽标不复位）
            boolean declared = i < s.declaredUno.length && s.declaredUno[i] && s.countOf(i) == 1;
            boolean catchable = i < s.unoCatchable.length && s.unoCatchable[i] && !declared;
            String mark = declared ? "UNO" : (catchable ? "可抓!" : "");
            String base = s.nameOf(i) + "：" + hand.size() + " 张";
            int markW = mark.isEmpty() ? 0 : this.font.width(" " + mark);
            String name = this.font.plainSubstrByWidth(base, SPECTATOR_PANEL_W - 6 - markW);
            g.drawString(this.font, name, panelX + 2, y, isTurn ? 0xFFFFFF55 : 0xFFFFFFFF, true);
            if (!mark.isEmpty()) {
                g.drawString(this.font, " " + mark, panelX + 2 + this.font.width(name), y,
                        declared ? 0xFF55FF55 : 0xFFFF5555, true);
            }
            y += 10;
            int rows = (hand.size() + perRow - 1) / perRow;
            for (int r = 0; r < rows; r++) {
                int from = r * perRow;
                int to = Math.min(hand.size(), from + perRow);
                int x = panelX + 2;
                for (int j = from; j < to; j++) {
                    UnoGui.drawCard(g, hand.get(j), x, y, cardW, cardH);
                    x += cardW + gap;
                }
                y += cardH + gap;
            }
        }
        g.disableScissor();
    }

    /** 旁观者手牌面板内容总高（名字行 + 各人手牌行）。 */
    private int spectatorContentHeight() {
        UnoClientState s = UnoClientState.INSTANCE;
        if (s.spectatorHands.isEmpty()) {
            return 0;
        }
        int cardW = 11;
        int cardH = 16;
        int gap = 1;
        int perRow = Math.max(1, (SPECTATOR_PANEL_W - 6) / (cardW + gap));
        int contentH = 0;
        for (List<UnoCard> hand : s.spectatorHands) {
            int rows = (hand.size() + perRow - 1) / perRow;
            contentH += 10 + rows * (cardH + gap);
        }
        return contentH;
    }

    /** 旁观者手牌面板最大滚动量（内容超高时 >0）。 */
    private int spectatorMaxScroll() {
        return Math.max(0, spectatorContentHeight() - (spectatorPanelBottom() - SPECTATOR_PANEL_TOP));
    }

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
