package io.wifi.cards.common.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 对局界面基类（纯客户端）：三个游戏的对局界面（斗地主/UNO/棋类）继承，
 * 统一界面生命周期与公共交互，游戏特有的画面/按钮由子类实现。
 * <p>统一提供：</p>
 * <ul>
 *   <li>isPauseScreen=false、renderBackground 空（无全局虚化）</li>
 *   <li>T 键打开聊天框（延迟到 tick，避免字符事件打入输入框）→ CardGameChatScreen</li>
 *   <li>tick 模板：开聊天 → 倒计时（服务端截止刻换算）→ 子类 onTick（签名计算与按钮重建）</li>
 *   <li>onClose 模板：退出确认弹层优先取消 → 子类 Esc 处理（如 UNO 选色弹层/调试回大厅）→
 *       关闭前钩子（如棋类调试标记）→ 重开提示</li>
 *   <li>removed()/resize() 统一重置按钮签名；resize 后钩子 onScreenResized</li>
 *   <li>操作按钮工厂、玩家头颅渲染、退出确认弹层（第一行文案子类提供）、
 *       左下角"规则/历史"按钮固定位置</li>
 * </ul>
 * 子类实现：isSpectator/turnEndGameTime/onTick/handleCloseRequest/reopenHint/
 * exitConfirmFirstLine/rebuildActionButtons + 全部画面渲染与鼠标交互。
 */
public abstract class AbstractGameScreen extends Screen {
    /** 手牌选中（斗地主多选 / UNO 单选；棋类不用）。 */
    protected final Set<Integer> selected = new HashSet<>();
    /** 动态操作按钮（退出/托管/出牌等，tick 按签名重建）。 */
    protected final List<Button> actionButtons = new ArrayList<>();
    /** 操作按钮签名：阶段/轮到谁/选中/弹层等变化时重建按钮。 */
    protected int buttonSignature = -1;
    /** 回合倒计时（服务端截止刻换算，见 updateCountdown）。 */
    protected int countdown = 30;
    /** 退出确认弹层：成员点「退出」后先询问（旁观者退出无需确认）。 */
    protected boolean confirmingExit;
    /** 待打开聊天框（延迟到 tick 执行，避免同按键的字符事件被新聊天框接收）。 */
    private boolean openChatPending;

    protected AbstractGameScreen(String titleKey) {
        super(Component.translatable(titleKey));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    // ---------------- 子类钩子 ----------------

    /** 旁观模式（服务端以 mySeat=-1 表示只读旁观，无操作权）。 */
    protected abstract boolean isSpectator();

    /** 服务端下发的回合截止游戏刻（0=尚未开始/未下发）。 */
    protected abstract long turnEndGameTime();

    /** 每 tick 的游戏特有逻辑（playRejected 清选中、签名计算与按钮重建等）。 */
    protected abstract void onTick();

    /** 重建动态操作按钮（移除旧按钮并添加当前阶段按钮）。 */
    protected abstract void rebuildActionButtons();

    /**
     * Esc 优先处理（在退出确认弹层判断之前）：子类可消费 Esc 不关闭界面，
     * 如 UNO 的选色弹层取消、调试旁观直接回大厅；默认不消费。
     */
    protected boolean onEscPressed() {
        return false;
    }

    /**
     * 关闭前钩子：返回 true 表示以特殊方式关闭（跳过重开提示，屏幕仍由 super.onClose 关闭），
     * 如棋类调试旁观模式（清标记且不提示）。
     */
    protected boolean handleCloseRequest() {
        return false;
    }

    /** 关闭对局界面提示（聊天栏重开提示，输入 /cardgames 或 /xxx 重新打开）。 */
    protected abstract void reopenHint();

    /** 退出确认弹层第一行文案翻译键（按游戏/模式动态，如棋类围棋无托管）。 */
    protected abstract String exitConfirmFirstLineKey();

    /** 窗口尺寸变化后的额外处理（如棋类重算棋盘变换）。 */
    protected void onScreenResized() {
    }

    // ---------------- 键盘 / 聊天 ----------------

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

    /** 延迟打开聊天框（等本次按键的字符事件处理完毕），tick 开头调用。 */
    protected void handlePendingChat() {
        if (openChatPending) {
            openChatPending = false;
            Minecraft.getInstance().setScreen(new CardGameChatScreen(this));
        }
    }

    // ---------------- tick / 关闭 / 生命周期 ----------------

    @Override
    public void tick() {
        super.tick();
        handlePendingChat();
        updateCountdown();
        onTick();
    }

    /** 用服务端下发的截止游戏刻计算剩余秒数：客户端 level.getGameTime() 与服务端同步，
     *  倒计时不受本地帧率/网络延迟影响。 */
    private void updateCountdown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && turnEndGameTime() > 0) {
            long remainingTicks = turnEndGameTime() - mc.level.getGameTime();
            countdown = (int) Math.max(0, (remainingTicks + 19) / 20); // 向上取整
        }
    }

    /** 关闭对局界面（Esc）：退出确认弹层打开时先取消弹层，再按才关闭界面。 */
    @Override
    public void onClose() {
        if (onEscPressed()) {
            return; // 子类已消费本次 Esc（取消选色弹层 / 调试模式回大厅）
        }
        if (confirmingExit) {
            confirmingExit = false; // 第一下 Esc：取消确认弹层（按钮由 tick 签名重建）
            return;
        }
        if (!handleCloseRequest()) {
            reopenHint();
        }
        super.onClose();
    }

    /**
     * 被替换为子界面（规则/历史/聊天）时调用：父类 removed() 会清空全部 widgets
     * （含操作按钮），返回本界面时因签名未变不会重建 → 强制重置签名，下个 tick 重建按钮；
     * 同时取消待打开聊天（按 T 后同一帧内点击"规则/历史"等按钮被替换时，返回后不再误弹聊天框）。
     */
    @Override
    public void removed() {
        super.removed();
        buttonSignature = -1;
        openChatPending = false;
    }

    /** 窗口 resize：父类会再次调用 init()（重复添加按钮），先清空全部再重建。 */
    @Override
    public void resize(Minecraft mc, int width, int height) {
        clearWidgets();
        actionButtons.clear();
        buttonSignature = -1;
        super.resize(mc, width, height);
        onScreenResized();
    }

    // ---------------- 公共辅助 ----------------

    /** 签名变化才重建操作按钮（子类 onTick 末尾调用）。 */
    protected void rebuildButtonsIfChanged(int signature) {
        if (signature != buttonSignature) {
            buttonSignature = signature;
            rebuildActionButtons();
        }
    }

    /** 操作按钮工厂（固定 90x20，可禁用）；label 为翻译键。 */
    protected Button button(int x, int y, String labelKey, Button.OnPress onPress, boolean active) {
        Button b = Button.builder(Component.translatable(labelKey), onPress).bounds(x, y, 90, 20).build();
        b.active = active;
        addRenderableWidget(b);
        return b;
    }

    /** 左下角"规则"子界面按钮（固定位置；打开动作由子类提供）。 */
    protected void addRulesButton(Runnable open) {
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.rules"), b -> open.run())
                .bounds(8, height - 26, 60, 20).build());
    }

    /** 左下角"历史"子界面按钮（固定位置；打开动作由子类提供，通常先发 HistoryC2S）。 */
    protected void addHistoryButton(Runnable open) {
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.common.button.history"), b -> open.run())
                .bounds(72, height - 26, 60, 20).build());
    }

    /** 退出确认弹层：半透明黑底 + 提示文本（按钮在右侧常驻行，见 rebuildActionButtons）。 */
    protected void drawExitConfirm(GuiGraphics g) {
        int w = Math.min(340, width - 40);
        int h = 54;
        int x0 = (width - w) / 2;
        int y0 = (height - h) / 2;
        g.fill(x0, y0, x0 + w, y0 + h, 0xE6000000); // 深色背景遮罩
        g.drawCenteredString(this.font, Component.translatable(exitConfirmFirstLineKey()), width / 2, y0 + 10, 0xFFFFD700);
        g.drawCenteredString(this.font, Component.translatable("wifi_card_games.common.confirm.exit_game"), width / 2, y0 + 26, 0xFFFFFFFF);
    }

    /**
     * 玩家头颅渲染（8x8 放大到目标尺寸）。
     * uuidStr 为空（假人/未知）、玩家不在 tab 列表或皮肤缺失时跳过。
     */
    protected void drawHead(GuiGraphics g, String uuidStr, int x, int y, int size) {
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
