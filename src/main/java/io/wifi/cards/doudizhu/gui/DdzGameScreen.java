package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.CallScoreC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.HistoryC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.LeaveRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.PassC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayCardsC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.RevealC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.RobActionC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.SpectateLeaveC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.ToggleTrustC2S;
import io.wifi.cards.doudizhu.rule.DdzAutoPlay;
import io.wifi.cards.doudizhu.rule.DdzPlayResult;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 游戏桌面界面（第一轮文字化牌面）：
 * <ul>
 * <li>顶部：对手信息（名字 + 剩余张数 + 地主标记），当前行动高亮</li>
 * <li>中央：阶段标题、轮到谁 + 倒计时、上一手出牌、底牌</li>
 * <li>底部：手牌（点击选牌高亮，花牌金色）</li>
 * <li>右下：动态操作按钮（叫分 / 抢地主 / 出牌 + 提示 + 托管）</li>
 * </ul>
 */
public class DdzGameScreen extends Screen {
    // 牌面尺寸：固定 34x50，重叠 14px 布局（GAP=20），地主 20 张也能在常见窗口宽度内放下
    private static final int CARD_W = 34;
    private static final int CARD_H = 50;
    private static final int CARD_GAP = 20;
    private static final int SELECT_OFFSET = 12;

    private final Set<Integer> selected = new HashSet<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private int buttonSignature = -1;
    private int countdown = 30;
    /** 拖拽选牌：上次处理的牌下标（-1=不在牌上），避免同一张牌被反复切换。 */
    private int lastDragCard = -1;
    /** 拖拽时上次鼠标位置（GUI 缩放坐标），用于路径采样插值防漏牌。 */
    private double lastDragX;
    private double lastDragY;
    /** 本次按下是否始于手牌（从按钮/空白按下拖动不处理手牌，避免误选）。 */
    private boolean dragArmed;

    public DdzGameScreen() {
        super(Component.literal("斗地主"));
    }

    /** 旁观模式：服务端以 mySeat=-1 表示只读旁观（无手牌、无操作权）。 */
    private boolean isSpectator() {
        return DdzClientState.INSTANCE.mySeat < 0;
    }

    @Override
    protected void init() {
        // 左下角：规则 / 出牌历史（子界面返回时回到本打牌界面，并渲染本界面为背景）
        addRenderableWidget(Button
                .builder(Component.literal("规则"),
                        b -> Minecraft.getInstance().setScreen(new DdzRulesScreen(DdzGameScreen.this)))
                .bounds(8, height - 26, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("历史"), b -> {
            ClientPlayNetworking.send(new HistoryC2S());
            Minecraft.getInstance().setScreen(new DdzHistoryScreen(DdzGameScreen.this));
        }).bounds(72, height - 26, 60, 20).build());
    }

    // ---------------- 背景音乐（循环，音量 0.3） ----------------

    private static SimpleSoundInstance bgm;

    /**
     * 每 tick 检查当前界面：处于打牌上下文（打牌界面或其子界面：聊天/规则/历史）时
     * 保持循环播放，已在播放则不重启（切换子界面不会从头再来）；离开打牌上下文才停止。
     */
    public static void tickBgm(Screen current) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            stopBgm();
            return;
        }
        boolean inGameUi = current instanceof DdzGameScreen
                || current instanceof DdzChatScreen
                || (current instanceof DdzRulesScreen r && r.isFromGame())
                || (current instanceof DdzHistoryScreen h && h.isFromGame());
        if (inGameUi) {
            playBgm();
        } else {
            stopBgm();
        }
    }

    /** 开始循环播放背景音乐（幂等：已在播放则跳过，不从头重播）。 */
    public static void playBgm() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getSoundManager() == null) {
            return;
        }
        if (bgm == null) {
            bgm = new SimpleSoundInstance(DdzSounds.BGM.getLocation(), SoundSource.MASTER,
                    0.6F, 1.0F, RandomSource.create(), true, 0,
                    SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true);
        }
        if (!mc.getSoundManager().isActive(bgm)) {
            mc.getSoundManager().play(bgm);
        }
    }

    /** 停止背景音乐（幂等）。 */
    public static void stopBgm() {
        Minecraft mc = Minecraft.getInstance();
        if (bgm != null && mc.getSoundManager() != null) {
            mc.getSoundManager().stop(bgm);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 待打开聊天框（延迟到 tick 执行，避免同按键的字符事件被新聊天框接收）。 */
    private boolean openChatPending;

    /** 关闭牌局界面（Esc）：提示可通过命令/点击重新打开。 */
    @Override
    public void onClose() {
        DdzClientState.chatReopenHint("关闭牌局界面");
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        // 按聊天绑定键（原版 options.keyChat，默认 T）打开聊天框；
        // 延迟到 tick 打开：立即打开会把本次按键的 charTyped 字符（如 't'）打进输入框
        if (mc.options.keyChat.matches(keyCode, scanCode)) {
            openChatPending = true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------- tick / 按钮 ----------------

    @Override
    public void tick() {
        super.tick();
        // 延迟打开聊天框（等本次按键的字符事件处理完毕，避免 't' 等字符进入输入框）
        if (openChatPending) {
            openChatPending = false;
            Minecraft.getInstance().setScreen(new DdzChatScreen(this));
        }
        DdzClientState s = DdzClientState.INSTANCE;
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
        // 阶段/轮到谁/托管/明牌/选牌变化时重建按钮（旁观模式按钮恒定，用独立签名区分）
        int signature = isSpectator() ? -50000
                : (s.phase.ordinal() * 100 + (s.currentSeat + 1) * 10 + (s.myTrust ? 1 : 0)) * 2
                        + (selected.isEmpty() ? 0 : 1) + (s.revealed ? 1000 : 0);
        if (signature != buttonSignature) {
            buttonSignature = signature;
            rebuildActionButtons();
        }
    }

    private void rebuildActionButtons() {
        for (Button b : actionButtons) {
            removeWidget(b);
        }
        actionButtons.clear();
        DdzClientState s = DdzClientState.INSTANCE;
        int x = width - 100;
        int y = height - 150;
        // 旁观模式：只读观看，仅提供「退出旁观」（服务端清理旁观关系并回到大厅）
        if (isSpectator()) {
            actionButtons.add(button(x, y, "退出旁观", b -> sendUnspectate(), true));
            return;
        }
        // 常驻行：退出游戏 + 托管（整局可用，随时可退出/取消托管）
        if (s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING) {
            actionButtons.add(button(x - 95, y - 26, "退出", b -> sendLeave(), true));
            actionButtons.add(button(x, y - 26, s.myTrust ? "取消托管" : "托管", b -> sendTrust(), true));
        }
        if (!s.isMyTurn()) {
            return;
        }
        switch (s.phase) {
            case CALLING -> {
                actionButtons.add(button(x, y, "不叫", b -> sendCall((byte) 0), true));
                actionButtons.add(button(x, y + 26, "叫1分", b -> sendCall((byte) 1), s.callMaxScore < 1));
                actionButtons.add(button(x, y + 52, "叫2分", b -> sendCall((byte) 2), s.callMaxScore < 2));
                actionButtons.add(button(x, y + 78, "叫3分", b -> sendCall((byte) 3), s.callMaxScore < 3));
            }
            case ROBBING -> {
                actionButtons.add(button(x, y, "不抢 ❌", b -> sendRob(false), true));
                actionButtons.add(button(x, y + 26, "抢地主 🔥", b -> sendRob(true), true));
            }
            case PLAYING -> {
                // 出牌按钮始终显示；地主出第一手牌前额外显示「明牌」按钮（公开手牌）
                actionButtons.add(button(x, y, "出牌", b -> sendPlay(), !selected.isEmpty()));
                boolean canReveal = s.landlordSeat == s.mySeat && !s.revealed && s.lastPlaySeat < 0;
                if (canReveal) {
                    actionButtons.add(button(x, y + 26, "明牌", b -> sendReveal(), true));
                    actionButtons.add(button(x, y + 52, "不出", b -> sendPass(),
                            s.lastPlaySeat >= 0 && s.lastPlaySeat != s.mySeat));
                    actionButtons.add(button(x, y + 78, "提示", b -> hint(), true));
                } else {
                    actionButtons.add(button(x, y + 26, "不出", b -> sendPass(),
                            s.lastPlaySeat >= 0 && s.lastPlaySeat != s.mySeat));
                    actionButtons.add(button(x, y + 52, "提示", b -> hint(), true));
                }
            }
            default -> {
            }
        }
    }

    private Button button(int x, int y, String label, Button.OnPress onPress, boolean active) {
        Button b = Button.builder(Component.literal(label), onPress).bounds(x, y, 90, 20).build();
        b.active = active;
        addRenderableWidget(b);
        return b;
    }

    // ---------------- 操作 ----------------

    private void sendCall(byte score) {
        ClientPlayNetworking.send(new CallScoreC2S(score));
    }

    private void sendRob(boolean rob) {
        ClientPlayNetworking.send(new RobActionC2S(rob));
    }

    private void sendPlay() {
        DdzClientState s = DdzClientState.INSTANCE;
        if (selected.isEmpty()) {
            return;
        }
        int[] ids = new int[selected.size()];
        int i = 0;
        for (DdzCard c : s.hand) {
            if (selected.contains(c.id())) {
                ids[i++] = c.id();
            }
        }
        ClientPlayNetworking.send(new PlayCardsC2S(ids));
        selected.clear();
    }

    private void sendPass() {
        ClientPlayNetworking.send(new PassC2S());
    }

    /** 地主选择明牌：公开全部手牌。 */
    private void sendReveal() {
        ClientPlayNetworking.send(new RevealC2S());
    }

    private void sendTrust() {
        DdzClientState s = DdzClientState.INSTANCE;
        s.myTrust = !s.myTrust;
        ClientPlayNetworking.send(new ToggleTrustC2S(s.myTrust));
    }

    /** 退出游戏：座位由服务端转机器人托管，本客户端回到大厅。 */
    private void sendLeave() {
        ClientPlayNetworking.send(new LeaveRoomC2S());
    }

    /** 退出旁观：服务端清理旁观关系并下发房间关闭消息，回到大厅。 */
    private void sendUnspectate() {
        ClientPlayNetworking.send(new SpectateLeaveC2S());
    }

    /** 提示：复用服务端托管引擎，选出一手可压的牌。 */
    private void hint() {
        DdzClientState s = DdzClientState.INSTANCE;
        DdzPlayResult target = (s.lastPlayType != null && s.lastPlaySeat >= 0 && s.lastPlaySeat != s.mySeat)
                ? new DdzPlayResult(s.lastPlayType, s.lastPlayKey, s.lastPlayCards)
                : null;
        List<DdzCard> play = DdzAutoPlay.findPlay(s.hand, target, s.flowerMode, s.ruleSet);
        selected.clear();
        if (play == null) {
            DdzClientState.chat("没有能出的牌，可以选“不出”");
        } else {
            selected.addAll(play.stream().map(DdzCard::id).toList());
        }
    }

    // ---------------- 鼠标选牌（事件驱动，与假滚动条同链路；路径采样防漏牌） ----------------

    /**
     * 命中检测：返回鼠标下的牌下标（顶层优先，即最右侧/已抬起的牌先命中），不在牌上返回 -1。
     * 坐标须为 GUI 缩放坐标。
     */
    private int cardIndexAt(double mouseX, double mouseY) {
        DdzClientState s = DdzClientState.INSTANCE;
        List<DdzCard> hand = s.hand;
        int n = hand.size();
        if (n == 0) {
            return -1;
        }
        int totalW = CARD_W + (n - 1) * CARD_GAP;
        int x0 = Math.max(2, (width - totalW) / 2);
        int y = height - CARD_H - 8;
        // 牌按从左到右绘制，右侧牌叠在左侧牌之上；倒序遍历保证点击到的是最顶上那张
        for (int i = n - 1; i >= 0; i--) {
            DdzCard c = hand.get(i);
            int cx = x0 + i * CARD_GAP;
            int cy = selected.contains(c.id()) ? y - SELECT_OFFSET : y;
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    /** 切换一张牌的选中状态（未选中→选中，已选中→取消选中）。 */
    private void toggleCard(int idx) {
        DdzCard c = DdzClientState.INSTANCE.hand.get(idx);
        if (!selected.remove(c.id())) {
            selected.add(c.id());
        }
        buttonSignature = -1; // 触发按钮重建（出牌按钮可用性）
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
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
            dragArmed = false; // 从按钮/空白按下：拖动不处理手牌（避免误选）
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

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上；
        // 聊天历史由原版 HUD 自动渲染，此处不重复绘制
        super.render(g, mouseX, mouseY, partialTick);
        drawTopInfo(g);
        drawCenter(g);
        drawHand(g);
    }

    private void drawTopInfo(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        // 顶部信息条（含玩家头颅与牌背）
        g.fill(0, 0, width, 54, 0x66000000);
        if (isSpectator()) {
            drawSpectatorTop(g, s);
            return;
        }
        int leftSeat = (s.mySeat + 1) % 3;
        int rightSeat = (s.mySeat + 2) % 3;
        // 左侧：左对手（上：头像+牌背叠+名字）+ 自己（下：头像+名字）
        drawHead(g, s.playerUuids[leftSeat], 6, 6, 16);
        drawCardBacks(g, 24, 9, s.countOf(leftSeat));
        g.drawString(this.font, nameLine(leftSeat), 46, 11,
                s.currentSeat == leftSeat ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        drawHead(g, s.playerUuids[s.mySeat], 6, 32, 16);
        String me = "你" + (s.mySeat == s.landlordSeat ? "（地主）" : "") + "：" + s.hand.size() + " 张"
                + (s.myTrust ? "（托管中）" : "");
        g.drawString(this.font, me, 26, 35, 0xFFFFFFFF, true);
        // 右侧：右对手（头像+牌背叠+名字右对齐）
        drawHead(g, s.playerUuids[rightSeat], width - 22, 6, 16);
        drawCardBacks(g, width - 42, 9, s.countOf(rightSeat));
        String rightText = nameLine(rightSeat);
        g.drawString(this.font, rightText, width - this.font.width(rightText) - 46, 11,
                s.currentSeat == rightSeat ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 顶部正中央：当前阶段 + 轮到谁/倒计时
        DdzGui.centeredShadow(g, this.font, width, phaseText(), 13, 0xFFFFD700);
        if (s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING) {
            String turnText = s.isMyTurn()
                    ? "轮到你（剩余 " + Math.max(0, countdown) + " 秒）"
                    : "轮到 " + s.nameOf(s.currentSeat) + "（剩余 " + Math.max(0, countdown) + " 秒）";
            DdzGui.centeredShadow(g, this.font, width, turnText, 29,
                    s.isMyTurn() ? 0xFFFFFF55 : 0xFFAAAAAA);
        }
    }

    /** 旁观模式顶部：三名玩家按座位 0 左 / 1 中 / 2 右 展示，阶段/轮到信息在下两行（无"自己"的手牌）。 */
    private void drawSpectatorTop(GuiGraphics g, DdzClientState s) {
        // 座位 0：左（头像 + 牌背叠 + 名字）
        drawHead(g, s.playerUuids[0], 6, 6, 16);
        drawCardBacks(g, 24, 9, s.countOf(0));
        g.drawString(this.font, nameLine(0), 46, 11,
                s.currentSeat == 0 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 座位 1：中（头像居中，名字与牌背叠在其右侧）
        drawHead(g, s.playerUuids[1], width / 2 - 8, 6, 16);
        drawCardBacks(g, width / 2 + 10, 9, s.countOf(1));
        String midText = nameLine(1);
        g.drawString(this.font, midText, width / 2 - this.font.width(midText) / 2, 11,
                s.currentSeat == 1 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 座位 2：右（头像 + 牌背叠 + 名字右对齐）
        drawHead(g, s.playerUuids[2], width - 22, 6, 16);
        drawCardBacks(g, width - 42, 9, s.countOf(2));
        String rightText = nameLine(2);
        g.drawString(this.font, rightText, width - this.font.width(rightText) - 46, 11,
                s.currentSeat == 2 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 左侧标注旁观状态；中央为阶段 + 轮到谁/倒计时
        g.drawString(this.font, "旁观中", 6, 32, 0xFFAAAAAA, true);
        DdzGui.centeredShadow(g, this.font, width, phaseText(), 29, 0xFFFFD700);
        if (s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING) {
            String turnText = "轮到 " + s.nameOf(s.currentSeat) + "（剩余 " + Math.max(0, countdown) + " 秒）";
            DdzGui.centeredShadow(g, this.font, width, turnText, 45, 0xFFAAAAAA);
        }
    }

    /** 绘制一叠牌背（最多 3 张错位示意，看不到内容），数量 0 时不画。 */
    private void drawCardBacks(GuiGraphics g, int x, int y, int count) {
        int shown = Math.min(3, count);
        for (int i = 0; i < shown; i++) {
            drawCardBack(g, x + i * 2, y + i, 14, 18);
        }
    }

    /** 绘制一张牌背（深蓝底 + 问号）。 */
    private void drawCardBack(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B4B8F);
        Font font = Minecraft.getInstance().font;
        g.drawString(font, "?", x + (w - font.width("?")) / 2, y + (h - 9) / 2, 0xFFFFFFFF, false);
    }

    /** 当前阶段标题（顶部正中央显示）。 */
    private String phaseText() {
        DdzClientState s = DdzClientState.INSTANCE;
        return switch (s.phase) {
            case WAITING -> "等待游戏开始…";
            case DEALING -> "发牌中…";
            case CALLING -> "叫分阶段" + (s.callMaxScore > 0 ? "（当前最高 " + s.callMaxScore + " 分）" : "");
            case ROBBING -> "抢地主阶段（连续不抢 " + s.consecutivePasses + "/2，当前倍数 ×" + s.multiplier + "）";
            case PLAYING -> "出牌阶段（倍数 ×" + s.multiplier + "，底分 " + s.baseScore + "）";
            case SETTLED -> "本局结束";
        };
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

    private String nameLine(int seat) {
        DdzClientState s = DdzClientState.INSTANCE;
        return s.nameOf(seat) + (seat == s.landlordSeat ? "（地主）" : "") + "：" + s.countOf(seat) + " 张";
    }

    private void drawCenter(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        // 中央信息面板：位于顶部信息条（0~54）下方，并预留右侧按钮区（小窗口自动收窄），
        // 所有中央文本在面板内居中，避免与按钮/顶部信息重叠
        int panelW = Math.max(40, Math.min(240, width - 210));
        int panelX = Math.max(8, cx - panelW / 2);
        int panelBottom = 150;
        g.fill(panelX, 58, panelX + panelW, panelBottom, 0x55000000);

        // 最近一次表态
        String actionText = null;
        if (s.lastCallScore >= 0 && !s.lastCallName.isEmpty()) {
            actionText = s.lastCallName + (s.lastCallScore == 0 ? " 不叫" : " 叫了 " + s.lastCallScore + " 分");
        } else if (!s.lastRobName.isEmpty() && s.phase == DdzGamePhase.ROBBING) {
            actionText = s.lastRobName + (s.lastRob ? " 抢地主！" : " 不抢");
        } else if (!s.lastPassName.isEmpty()) {
            actionText = s.lastPassName + " 不出";
        }
        if (actionText != null) {
            DdzGui.centeredShadowAt(g, this.font, panelX + panelW / 2, actionText, 62, 0xFFFFD700);
        }

        // 最近两手历史（出牌/不出，最新在前；第 1 张在上、第 2 张在下）
        List<DdzClientState.PlayEntry> plays = s.lastPlays;
        for (int i = 0; i < Math.min(2, plays.size()); i++) {
            DdzClientState.PlayEntry e = plays.get(i);
            int labelY = i == 0 ? 74 : 106;
            if (e.pass()) {
                // 不出（跳过）：只渲染文本行，不占牌行位置（与出牌行保持同一行高节奏）
                DdzGui.centeredShadowAt(g, this.font, panelX + panelW / 2, e.name() + " 不出", labelY, 0xFFFFD700);
                continue;
            }
            int cardY = labelY + 10;
            String label = e.name() + " 出了 " + (e.type() != null ? e.type().displayName() : "");
            DdzGui.centeredShadowAt(g, this.font, panelX + panelW / 2, label, labelY, 0xFFFFFFFF);
            int n = e.cards().size();
            // 宽牌型（如 20 张飞机带翅膀）动态缩小牌宽并按需重叠，保证不溢出面板
            int cardW = Math.max(8, Math.min(20, (panelW - 16) / n));
            int gap = Math.max(2, cardW - 4);
            int totalW = cardW + (n - 1) * gap;
            int x0 = panelX + Math.max(2, (panelW - totalW) / 2);
            for (int j = 0; j < n; j++) {
                drawCard(g, e.cards().get(j), x0 + j * gap, cardY, cardW, 20);
            }
        }

        // 底牌（地主确定后亮出）
        if (s.landlordSeat >= 0 && !s.bottomCards.isEmpty()) {
            StringBuilder sb = new StringBuilder("底牌：");
            for (DdzCard c : s.bottomCards) {
                sb.append(c.display()).append(' ');
            }
            DdzGui.centeredShadowAt(g, this.font, panelX + panelW / 2, sb.toString(), 138, 0xFFFFFF88);
        }

        // 明牌：公开地主全部手牌（所有玩家可见，随地主出牌同步移除）。
        // 牌面固定可读宽度横向排列，超出面板宽度自动换行，保证每张牌完整显示
        if (s.revealed && !s.revealedCards.isEmpty()) {
            int n = s.revealedCards.size();
            int cardW = 24;
            int cardH = 20;
            int gap = 2;
            int perRow = Math.max(1, (panelW - 8) / (cardW + gap)); // 每行可放张数
            int rows = (n + perRow - 1) / perRow;
            int areaTop = 156; // 标题行
            int areaH = 10 + rows * (cardH + gap) + 4;
            g.fill(panelX, areaTop - 6, panelX + panelW, areaTop + areaH, 0x44000000);
            DdzGui.centeredShadowAt(g, this.font, panelX + panelW / 2, s.landlordName + " 明牌", areaTop, 0xFFFFD700);
            for (int i = 0; i < n; i++) {
                int row = i / perRow;
                int col = i % perRow;
                int totalRowW = perRow * (cardW + gap) - gap;
                int x0 = panelX + Math.max(0, (panelW - totalRowW) / 2);
                drawCard(g, s.revealedCards.get(i), x0 + col * (cardW + gap),
                        areaTop + 12 + row * (cardH + gap), cardW, cardH);
            }
        }
    }

    private void drawHand(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        List<DdzCard> hand = s.hand;
        int n = hand.size();
        if (n == 0) {
            return;
        }
        int totalW = CARD_W + (n - 1) * CARD_GAP;
        int x0 = Math.max(2, (width - totalW) / 2);
        int y = height - CARD_H - 8;
        for (int i = 0; i < n; i++) {
            DdzCard c = hand.get(i);
            int cx = x0 + i * CARD_GAP;
            int cy = selected.contains(c.id()) ? y - SELECT_OFFSET : y;
            if (selected.contains(c.id())) {
                // 选中牌金色描边高亮
                g.fill(cx - 1, cy - 1, cx + CARD_W + 1, cy + CARD_H + 1, 0xFFFFD700);
            }
            drawCard(g, c, cx, cy, CARD_W, CARD_H);
        }
    }

    /** 绘制一张牌（文字化：色块 + 左上角点数/花色文字，花牌金色）。无阴影保证小字号清晰。 */
    public static void drawCard(GuiGraphics g, DdzCard card, int x, int y, int w, int h) {
        int bg;
        if (card.isFlower()) {
            bg = 0xFFF7D94C; // 金色花牌
        } else if (card.isRed()) {
            bg = 0xFFFFE6E6; // 红牌浅红底
        } else {
            bg = 0xFFF0F0F0;
        }
        g.fill(x, y, x + w, y + h, 0xFF000000); // 黑色描边
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        int color = card.isFlower() ? 0xFF7A4E00 : (card.isRed() ? 0xFFD00000 : 0xFF111111);
        g.drawString(Minecraft.getInstance().font, card.display(), x + 3, y + 3, color, false);
    }
}
