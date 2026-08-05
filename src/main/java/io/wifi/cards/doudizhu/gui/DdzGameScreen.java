package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.network.DdzPackets.CallScoreC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.PassC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayCardsC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.RobActionC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.ToggleTrustC2S;
import io.wifi.cards.doudizhu.rule.DdzAutoPlay;
import io.wifi.cards.doudizhu.rule.DdzPlayResult;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 游戏桌面界面（第一轮文字化牌面）：
 * <ul>
 *   <li>顶部：对手信息（名字 + 剩余张数 + 地主标记），当前行动高亮</li>
 *   <li>中央：阶段标题、轮到谁 + 倒计时、上一手出牌、底牌</li>
 *   <li>底部：手牌（点击选牌高亮，花牌金色）</li>
 *   <li>右下：动态操作按钮（叫分 / 抢地主 / 出牌 + 提示 + 托管）</li>
 * </ul>
 */
public class DdzGameScreen extends Screen {
    private static final int CARD_W = 26;
    private static final int CARD_H = 38;
    private static final int CARD_GAP = 22;
    private static final int SELECT_OFFSET = 10;

    private final Set<Integer> selected = new HashSet<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private int buttonSignature = -1;
    private int countdown;
    private int lastCountdownSeat = -1;

    public DdzGameScreen() {
        super(Component.literal("斗地主"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------- tick / 按钮 ----------------

    @Override
    public void tick() {
        super.tick();
        DdzClientState s = DdzClientState.INSTANCE;
        // 座位切换（或开局）时重置本地倒计时
        if (s.currentSeat != lastCountdownSeat) {
            lastCountdownSeat = s.currentSeat;
            countdown = s.turnSeconds;
        }
        if (countdown > 0) {
            countdown--;
        }
        // 阶段/轮到谁/托管/选牌变化时重建按钮
        int signature = (s.phase.ordinal() * 100 + (s.currentSeat + 1) * 10 + (s.myTrust ? 1 : 0)) * 2
                + (selected.isEmpty() ? 0 : 1);
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
        if (!s.isMyTurn()) {
            return;
        }
        int x = width - 100;
        int y = height - 150;
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
                actionButtons.add(button(x, y, "出牌", b -> sendPlay(), !selected.isEmpty()));
                actionButtons.add(button(x, y + 26, "不出", b -> sendPass(),
                        s.lastPlaySeat >= 0 && s.lastPlaySeat != s.mySeat));
                actionButtons.add(button(x, y + 52, "提示", b -> hint(), true));
                actionButtons.add(button(x, y + 78, s.myTrust ? "取消托管" : "托管", b -> sendTrust(), true));
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

    private void sendTrust() {
        DdzClientState s = DdzClientState.INSTANCE;
        s.myTrust = !s.myTrust;
        ClientPlayNetworking.send(new ToggleTrustC2S(s.myTrust));
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

    // ---------------- 鼠标选牌 ----------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            DdzClientState s = DdzClientState.INSTANCE;
            List<DdzCard> hand = s.hand;
            int n = hand.size();
            int totalW = CARD_W + (n - 1) * CARD_GAP;
            int x0 = Math.max(2, (width - totalW) / 2);
            int y = height - CARD_H - 8;
            for (int i = 0; i < n; i++) {
                DdzCard c = hand.get(i);
                int cx = x0 + i * CARD_GAP;
                int cy = selected.contains(c.id()) ? y - SELECT_OFFSET : y;
                if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                    if (!selected.remove(c.id())) {
                        selected.add(c.id());
                    }
                    buttonSignature = -1; // 触发按钮重建（出牌按钮可用性）
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        drawTopInfo(g);
        drawCenter(g);
        drawHand(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTopInfo(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        int leftSeat = (s.mySeat + 1) % 3;
        int rightSeat = (s.mySeat + 2) % 3;
        for (int side = 0; side < 2; side++) {
            int seat = side == 0 ? leftSeat : rightSeat;
            String text = nameLine(seat);
            int color = s.currentSeat == seat ? 0xFFFFFF55 : 0xFFFFFFFF;
            if (side == 0) {
                g.drawString(this.font, Component.literal(text), 8, 8, color);
            } else {
                g.drawString(this.font, Component.literal(text), width - this.font.width(text) - 8, 8, color);
            }
        }
        String me = "你" + (s.mySeat == s.landlordSeat ? "（地主）" : "") + "：" + s.hand.size() + " 张"
                + (s.myTrust ? "（托管中）" : "");
        g.drawString(this.font, Component.literal(me), 8, 26, 0xFFFFFFFF);
    }

    private String nameLine(int seat) {
        DdzClientState s = DdzClientState.INSTANCE;
        return s.nameOf(seat) + (seat == s.landlordSeat ? "（地主）" : "") + "：" + s.countOf(seat) + " 张";
    }

    private void drawCenter(GuiGraphics g) {
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        String phaseText = switch (s.phase) {
            case WAITING -> "等待游戏开始…";
            case DEALING -> "发牌中…";
            case CALLING -> "叫分阶段" + (s.callMaxScore > 0 ? "（当前最高 " + s.callMaxScore + " 分）" : "");
            case ROBBING -> "抢地主阶段（连续不抢 " + s.consecutivePasses + "/2，当前倍数 ×" + s.multiplier + "）";
            case PLAYING -> "出牌阶段（倍数 ×" + s.multiplier + "，底分 " + s.baseScore + "）";
            case SETTLED -> "本局结束";
        };
        g.drawCenteredString(this.font, Component.literal(phaseText), cx, 8, 0xFFFFFFFF);

        if (s.phase == DdzGamePhase.CALLING || s.phase == DdzGamePhase.ROBBING || s.phase == DdzGamePhase.PLAYING) {
            String turnText = s.isMyTurn()
                    ? "轮到你（剩余 " + Math.max(0, countdown) + " 秒）"
                    : "轮到 " + s.nameOf(s.currentSeat) + "（剩余 " + Math.max(0, countdown) + " 秒）";
            g.drawCenteredString(this.font, Component.literal(turnText), cx, 22,
                    s.isMyTurn() ? 0xFFFFFF55 : 0xFFAAAAAA);
        }

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
            g.drawCenteredString(this.font, Component.literal(actionText), cx, 40, 0xFFFFD700);
        }

        // 上一手出牌
        if (!s.lastPlayCards.isEmpty() && s.lastPlayType != null) {
            String label = s.lastPlayName + " 出了 " + s.lastPlayType.displayName();
            g.drawCenteredString(this.font, Component.literal(label), cx, 60, 0xFFFFFFFF);
            int n = s.lastPlayCards.size();
            int totalW = 20 * n - 4;
            int x0 = (width - totalW) / 2;
            for (int i = 0; i < n; i++) {
                drawCard(g, s.lastPlayCards.get(i), x0 + i * 20, 72, 20, 30);
            }
        }

        // 底牌（地主确定后亮出）
        if (s.landlordSeat >= 0 && !s.bottomCards.isEmpty()) {
            StringBuilder sb = new StringBuilder("底牌：");
            for (DdzCard c : s.bottomCards) {
                sb.append(c.display()).append(' ');
            }
            g.drawCenteredString(this.font, Component.literal(sb.toString()), cx, 110, 0xFFFFFF88);
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
            drawCard(g, c, cx, cy, CARD_W, CARD_H);
        }
    }

    /** 绘制一张牌（文字化：色块 + 点数/花色文字，花牌金色）。 */
    public static void drawCard(GuiGraphics g, DdzCard card, int x, int y, int w, int h) {
        int bg;
        if (card.isFlower()) {
            bg = 0xFFF7D94C;      // 金色花牌
        } else if (card.isRed()) {
            bg = 0xFFFFE6E6;      // 红牌浅红底
        } else {
            bg = 0xFFF0F0F0;
        }
        g.fill(x, y, x + w, y + h, 0xFF000000);      // 黑色描边
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        int color = card.isFlower() ? 0xFF7A4E00 : (card.isRed() ? 0xFFD00000 : 0xFF111111);
        g.drawString(Minecraft.getInstance().font, card.display(), x + 3, y + 3, color);
    }
}
