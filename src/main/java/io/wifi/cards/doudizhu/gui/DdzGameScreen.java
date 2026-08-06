package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.common.client.AbstractGameScreen;
import io.wifi.cards.common.client.CardGameChatScreen;
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
import io.wifi.cards.doudizhu.rule.DdzCardTypeRecognizer;
import io.wifi.cards.doudizhu.rule.DdzPlayResult;
import io.wifi.cards.doudizhu.sound.DdzSounds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏桌面界面（第一轮文字化牌面，继承 {@link AbstractGameScreen}）：
 * <ul>
 * <li>顶部：对手信息（名字 + 剩余张数 + 地主标记），当前行动高亮</li>
 * <li>中央：阶段标题、轮到谁 + 倒计时、上一手出牌、底牌</li>
 * <li>底部：手牌（点击选牌高亮，花牌金色）</li>
 * <li>右下：动态操作按钮（叫分 / 抢地主 / 出牌 + 提示 + 托管）</li>
 * </ul>
 */
public class DdzGameScreen extends AbstractGameScreen {
    // 牌面尺寸：固定 34x50，重叠 14px 布局（GAP=20），地主 20 张也能在常见窗口宽度内放下
    private static final int CARD_W = 34;
    private static final int CARD_H = 50;
    private static final int CARD_GAP = 20;
    private static final int SELECT_OFFSET = 12;

    /** 拖拽选牌：上次处理的牌下标（-1=不在牌上），避免同一张牌被反复切换。 */
    private int lastDragCard = -1;
    /** 拖拽时上次鼠标位置（GUI 缩放坐标），用于路径采样插值防漏牌。 */
    private double lastDragX;
    private double lastDragY;
    /** 本次按下是否始于手牌（从按钮/空白按下拖动不处理手牌，避免误选）。 */
    private boolean dragArmed;

    public DdzGameScreen() {
        super("斗地主");
    }

    /** 旁观模式：服务端以 mySeat=-1 表示只读旁观（无手牌、无操作权）。 */
    @Override
    protected boolean isSpectator() {
        return DdzClientState.INSTANCE.mySeat < 0;
    }

    @Override
    protected long turnEndGameTime() {
        return DdzClientState.INSTANCE.turnEndGameTime;
    }

    @Override
    protected void reopenHint() {
        DdzClientState.chatReopenHint("关闭牌局界面");
    }

    @Override
    protected String exitConfirmFirstLine() {
        return "退出后座位将由机器人托管，对局继续";
    }

    @Override
    protected void init() {
        // 左下角：规则 / 出牌历史（子界面返回时回到本打牌界面，并渲染本界面为背景）
        addRulesButton(() -> Minecraft.getInstance().setScreen(new DdzRulesScreen(DdzGameScreen.this)));
        addHistoryButton(() -> {
            ClientPlayNetworking.send(new HistoryC2S());
            Minecraft.getInstance().setScreen(new DdzHistoryScreen(DdzGameScreen.this));
        });
        // 操作按钮（退出/托管/出牌等）在 init 时立即重建：
        // resize（窗口/全屏/GUI 缩放变化）会重建整个 widget 树，若只等 tick 的签名变化重建，
        // 旁观者（签名恒定）的「退出旁观」按钮会丢失且无法恢复（成员则要等选牌等变化才恢复）
        rebuildActionButtons();
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
        // 聊天框为三游戏共用：仅当父级是斗地主牌桌时视为打牌上下文，
        // 否则在 UNO/棋类对局中打开聊天框会误播斗地主 BGM
        boolean inGameUi = current instanceof DdzGameScreen
                || (current instanceof CardGameChatScreen c && c.parent() instanceof DdzGameScreen)
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

    // ---------------- tick / 按钮 ----------------

    /** 每 tick 游戏特有逻辑：出牌被拒清选中 + 签名计算与按钮重建（聊天/倒计时由基类处理）。 */
    @Override
    protected void onTick() {
        DdzClientState s = DdzClientState.INSTANCE;
        // 服务端拒绝了最近一次出牌：清空选中，便于玩家重新选牌
        if (s.playRejected) {
            s.playRejected = false;
            selected.clear();
            buttonSignature = -1;
        }
        // 阶段/轮到谁/托管/明牌/选牌/退出确认弹层变化时重建按钮（旁观模式按钮恒定，用独立签名区分）
        int signature = isSpectator() ? -50000
                : (s.phase.ordinal() * 100 + (s.currentSeat + 1) * 10 + (s.myTrust ? 1 : 0)) * 2
                        + (selected.isEmpty() ? 0 : 1) + (s.revealed ? 1000 : 0) + (confirmingExit ? 500 : 0);
        rebuildButtonsIfChanged(signature);
    }

    @Override
    protected void rebuildActionButtons() {
        for (Button b : actionButtons) {
            removeWidget(b);
        }
        actionButtons.clear();
        DdzClientState s = DdzClientState.INSTANCE;
        int x = width - 100;
        int y = height - 150;
        // 旁观模式：只读观看，仅提供「退出旁观」——置于左下角「规则/历史」按钮上方，
        // 宽度恰好 = 规则(60) + 间隔(4) + 历史(60) = 124，与两按钮左右对齐
        if (isSpectator()) {
            Button exitBtn = Button.builder(Component.literal("退出旁观"), b -> sendUnspectate())
                    .bounds(8, height - 50, 124, 20).build();
            actionButtons.add(exitBtn);
            addRenderableWidget(exitBtn);
            return;
        }
        // 常驻行：退出游戏 + 托管（整局可用，随时可退出/取消托管）
        if (s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING) {
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
            actionButtons.add(button(x - 95, y - 26, "退出", b -> confirmingExit = true, true));
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
                // 出牌按钮始终显示；选中牌为合法牌型且能压过上家时才可用（本地预检，与服务端同引擎）
                actionButtons.add(button(x, y, "出牌", b -> sendPlay(), canPlaySelected()));
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

    /**
     * 客户端本地出牌预检：选中牌是否为合法牌型（按房间规则过滤）且能压过上家。
     * 与服务端 DdzGame.choosePlay 同引擎（DdzCardTypeRecognizer），防止把非法牌发到服务端被拒。
     */
    private boolean canPlaySelected() {
        DdzClientState s = DdzClientState.INSTANCE;
        if (selected.isEmpty() || s.hand.isEmpty()) {
            return false;
        }
        List<DdzCard> cards = new ArrayList<>(selected.size());
        for (DdzCard c : s.hand) {
            if (selected.contains(c.id())) {
                cards.add(c);
            }
        }
        DdzPlayResult target = (s.lastPlayType != null && s.lastPlaySeat >= 0 && s.lastPlaySeat != s.mySeat)
                ? new DdzPlayResult(s.lastPlayType, s.lastPlayKey, s.lastPlayCards)
                : null;
        for (DdzPlayResult r : DdzCardTypeRecognizer.recognize(cards, s.flowerMode, s.ruleSet)) {
            if (r.canBeat(target)) {
                return true;
            }
        }
        return false;
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
        // 选牌变化：强制重建出牌按钮可用性（签名仅区分空/非空两态，
        // 错误组合 → 提示替换为合法组合后仍非空，不重建会导致按钮一直禁用）
        buttonSignature = -1;
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
        if (confirmingExit) {
            // 退出确认弹层中：不处理选牌/拖拽，仅响应确认/取消按钮（super 转发给 widget）
            return super.mouseClicked(mouseX, mouseY, button);
        }
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
        if (isSpectator()) {
            drawSpectatorHands(g); // 旁观透视：底部显示三家完整手牌
        }
        if (confirmingExit) {
            drawExitConfirm(g);
        }
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
        // 顶部正中央：当前阶段 + 轮到谁/倒计时（截止刻未下发/已过期时不显示，避免陈旧值）
        DdzGui.centeredShadow(g, this.font, width, phaseText(), 13, 0xFFFFD700);
        if ((s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING)
                && s.turnEndGameTime > 0) {
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
        // 座位 1：中（头像+牌背+名字整块居中，名字恒在牌背右侧，不与牌背重叠；长名截断防溢出）
        drawHead(g, s.playerUuids[1], width / 2 - 8, 6, 16);
        String midText = nameLine(1);
        midText = this.font.plainSubstrByWidth(midText, Math.max(40, width / 2 - 80));
        drawCardBacks(g, width / 2 + 10, 9, s.countOf(1));
        g.drawString(this.font, midText, width / 2 + 30, 11,
                s.currentSeat == 1 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 座位 2：右（头像 + 牌背叠 + 名字右对齐）
        drawHead(g, s.playerUuids[2], width - 22, 6, 16);
        drawCardBacks(g, width - 42, 9, s.countOf(2));
        String rightText = nameLine(2);
        g.drawString(this.font, rightText, width - this.font.width(rightText) - 46, 11,
                s.currentSeat == 2 ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        // 左侧标注旁观状态；中央为阶段 + 轮到谁/倒计时（截止刻未下发/已过期时不显示，避免陈旧值）
        g.drawString(this.font, "旁观中", 6, 32, 0xFFAAAAAA, true);
        DdzGui.centeredShadow(g, this.font, width, phaseText(), 29, 0xFFFFD700);
        if ((s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING)
                && s.turnEndGameTime > 0) {
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

    /** 当前阶段标题（顶部正中央显示）；旁观 UI 调试时附加「（调试）」标记。 */
    private String phaseText() {
        DdzClientState s = DdzClientState.INSTANCE;
        String base = switch (s.phase) {
            case WAITING -> "等待游戏开始…";
            case DEALING -> "发牌中…";
            case CALLING -> "叫分阶段" + (s.callMaxScore > 0 ? "（当前最高 " + s.callMaxScore + " 分）" : "");
            case ROBBING -> "抢地主阶段（连续不抢 " + s.consecutivePasses + "/2，当前倍数 ×" + s.multiplier + "）";
            case PLAYING -> "出牌阶段（倍数 ×" + s.multiplier + "，底分 " + s.baseScore + "）";
            case SETTLED -> "本局结束";
        };
        return s.debugSpectate() ? base + "（调试）" : base;
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
        // 旁观者不重复绘制——底部全景手牌已含地主牌
        if (!isSpectator() && s.revealed && !s.revealedCards.isEmpty()) {
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

    /**
     * 旁观透视（传统斗地主布局）：
     * 左上角 = 座位 0（头像 + 名字 + 手牌）、右上角 = 座位 1、底部中央 = 座位 2；
     * 先画底部（确定其牌顶作为角落牌区的延伸边界），角落两列棋盘布局；
     * 均避开按钮区与中央面板。
     */
    private void drawSpectatorHands(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        if (s.spectatorHands.size() < 3) {
            return; // 尚未收到三家手牌快照
        }
        int bottomTop = drawBottomHand(g, 2); // 底部中央（返回其牌顶）
        drawCornerHand(g, 0, true, bottomTop); // 左上
        drawCornerHand(g, 1, false, bottomTop); // 右上
    }

    /** 中央面板几何（与 drawCenter 一致）。 */
    private int centerPanelW() {
        return Math.max(40, Math.min(240, width - 210));
    }

    private int centerPanelX() {
        return Math.max(8, width / 2 - centerPanelW() / 2);
    }

    /**
     * 左上/右上角手牌（两列棋盘布局）：
     * 列 1 = 头部右侧/左侧的牌区（从头像行开始向下），列 2 = 头像下方的空白（从头像下一行开始）。
     * 先填列 1，放不下时利用列 2（头像下方区域）；两列都用尽才压缩牌宽。
     * 牌区底界 = 底部牌区顶（bottomTop），永不重叠。
     */
    private void drawCornerHand(GuiGraphics g, int seat, boolean left, int bottomTop) {
        DdzClientState s = DdzClientState.INSTANCE;
        List<DdzCard> hand = s.spectatorHands.get(seat);
        if (hand.isEmpty()) {
            return;
        }
        int rowH = 20;
        int gap = 2;
        int limitL = left ? 8 : centerPanelX() + centerPanelW() + 8;
        int limitR = left ? centerPanelX() - 8 : width - 8;
        // 名字截断：头部（头像+名字）最多占区域一半，避免极长名把牌区挤到 1 列
        String name = s.nameOf(seat);
        int nameW = this.font.width(name);
        int maxLabelW = Math.max(40, (limitR - limitL) / 2);
        if (20 + nameW + 6 > maxLabelW) {
            name = this.font.plainSubstrByWidth(name, Math.max(4, maxLabelW - 26));
            nameW = this.font.width(name);
        }
        int labelW = 20 + nameW + 6;
        int availW = Math.max(20, limitR - limitL - labelW);
        int n = hand.size();
        int y = 58; // 顶部信息条（0~54）之下
        // 牌区底界：底部牌区上方，且不越过左下按钮（height-50）——两列可安全延伸
        int maxY = Math.min(bottomTop - 8, height - 56);
        // 类似底部：先利用头像左/右侧空间（列 1，从头像行起），再利用头像下方空间
        // （列 2，从头像下一行起，可多行）；列 1 + 列 2 合计最多 5 行，超出才压缩牌宽
        int maxRows = 5;
        int cardW = 20; // 目标牌宽（用户要求最小 20px）
        int perRow1 = Math.max(1, (availW + gap) / (cardW + gap));
        int rows1 = Math.max(0, Math.min(maxRows, (maxY - y) / (rowH + gap)));
        int avail2 = Math.max(20, labelW - 6);
        int perRow2 = Math.max(1, (avail2 + gap) / (cardW + gap));
        int rows2 = Math.max(0, Math.min(maxRows - rows1, (maxY - (y + rowH + gap)) / (rowH + gap)));
        int capacity = rows1 * perRow1 + rows2 * perRow2;
        if (capacity < n && rows1 > 0) {
            // 5 行内仍放不下（极限窄窗）：压缩列 1 牌宽（列 2 保持 20px）
            perRow1 = Math.max(perRow1, (n - rows2 * perRow2 + rows1 - 1) / rows1);
            cardW = Math.max(6, (availW - (perRow1 - 1) * gap) / perRow1);
        }
        // 行 1 头部：头像 + 名字
        int headY = y + (rowH - 16) / 2;
        if (left) {
            drawHead(g, s.playerUuids[seat], limitL, headY, 16);
            g.drawString(this.font, name, limitL + 20, y + 4, 0xFFFFFF88, true);
        } else {
            int headX = limitR - 16;
            g.drawString(this.font, name, headX - 6 - nameW, y + 4, 0xFFFFFF88, true);
            drawHead(g, s.playerUuids[seat], headX, headY, 16);
        }
        // 填充列 1：左角从左排、右角右对齐到头像左侧
        int idx = 0;
        for (int r = 0; r < rows1 && idx < n; r++) {
            int cy = y + r * (rowH + gap);
            int count = Math.min(perRow1, n - idx);
            int rowW = count * cardW + (count - 1) * gap;
            int x = left ? limitL + labelW : limitR - labelW - rowW;
            for (int c = 0; c < count; c++, idx++) {
                drawCard(g, hand.get(idx), x, cy, cardW, rowH);
                x += cardW + gap;
            }
        }
        // 填充列 2（头像下方）：左角从头像下 x=limitL 排；右角右对齐到头像左缘
        int y2 = y + rowH + gap;
        for (int r = 0; r < rows2 && idx < n; r++) {
            int cy = y2 + r * (rowH + gap);
            int count = Math.min(perRow2, n - idx);
            int rowW = count * cardW + (count - 1) * gap;
            int x = left ? limitL : limitR - 16 - 2 - rowW;
            for (int c = 0; c < count; c++, idx++) {
                drawCard(g, hand.get(idx), x, cy, cardW, rowH);
                x += cardW + gap;
            }
        }
    }

    /**
     * 底部中央手牌（座位 2）：头像 + 名字 + 牌，整块居中。
     * 避开左下角按钮区（x<140）；保持可读牌宽优先，放不下自动换行（行数随窗口高度动态）。
     *
     * @return 底部牌区顶 y（角落牌区以此为上界，保证不重叠）
     */
    private int drawBottomHand(GuiGraphics g, int seat) {
        DdzClientState s = DdzClientState.INSTANCE;
        List<DdzCard> hand = s.spectatorHands.get(seat);
        if (hand.isEmpty()) {
            return height - 8; // 无底部牌区：角落以左下按钮上方为界（drawCornerHand 内 clamp）
        }
        int rowH = 22;
        int gap = 2;
        int limitL = 140; // 避开左下「规则」「历史」按钮
        int limitR = width - 8;
        String name = s.nameOf(seat);
        int nameW = this.font.width(name);
        int maxLabelW = Math.max(40, (limitR - limitL) / 2);
        if (20 + nameW + 6 > maxLabelW) {
            name = this.font.plainSubstrByWidth(name, Math.max(4, maxLabelW - 26));
            nameW = this.font.width(name);
        }
        int labelW = 20 + nameW + 6;
        int availW = Math.max(20, limitR - limitL - labelW);
        int n = hand.size();
        // 保持可读牌宽（20px）优先：放不下自动换行；行数上限随窗口高度动态
        // （底部牌顶不低于 160，避免与角落牌区/中央面板重叠）
        int maxBottomRows = Math.max(1, (height - 160) / (rowH + gap));
        int[] layout = cardLayout(availW, n, 20, 20, maxBottomRows);
        int cardW = layout[0];
        int perRow = layout[1];
        int rows = (n + perRow - 1) / perRow;
        int rowW = Math.min(n, perRow) * cardW + (Math.min(n, perRow) - 1) * gap;
        int blockW = labelW + rowW;
        int startX = limitL + Math.max(0, (limitR - limitL - blockW) / 2);
        int y = height - rows * (rowH + gap) - 8;
        // 行 1 头部：头像 + 名字
        drawHead(g, s.playerUuids[seat], startX, y + (rowH - 16) / 2, 16);
        g.drawString(this.font, name, startX + 20, y + 5, 0xFFFFFF88, true);
        // 牌区（多行时第 1 行带头部缩进，其余行从整块左缘开始）
        for (int r = 0; r < rows; r++) {
            int from = r * perRow;
            int to = Math.min(n, from + perRow);
            int count = to - from;
            int rowWr = count * cardW + (count - 1) * gap;
            int x = r == 0 ? startX + labelW : startX;
            int cy = y + r * (rowH + gap);
            for (int i = from; i < to; i++) {
                drawCard(g, hand.get(i), x, cy, cardW, rowH);
                x += cardW + gap;
            }
        }
        return y; // 底部牌区顶（角落牌区延伸的边界）
    }

    /**
     * 牌布局计算：以「可读牌宽」为优先——先按最小可读宽度（minW）决定每行张数，
     * 放不下就换行；行数超过 maxRows（极端窄窗）才压缩牌宽，避免牌被压得过窄。
     * 牌宽按实际每行张数计算（牌少时用满可用宽度，牌宽可达 idealW）。
     *
     * @return {cardW, perRow}
     */
    private static int[] cardLayout(int availW, int n, int idealW, int minW, int maxRows) {
        int gap = 2;
        int perRow = Math.max(1, (availW + gap) / (minW + gap));
        int rows = (n + perRow - 1) / perRow;
        if (rows > maxRows) {
            // 极端窄窗：保持 maxRows 行，压缩牌宽（防溢出屏幕）
            perRow = Math.max(1, (n + maxRows - 1) / maxRows);
        }
        int perRowActual = Math.min(n, perRow);
        int cardW = Math.min(idealW, Math.max(1, (availW - (perRowActual - 1) * gap) / perRowActual));
        return new int[]{cardW, perRow};
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
