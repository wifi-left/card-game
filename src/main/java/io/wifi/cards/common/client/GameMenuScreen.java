package io.wifi.cards.common.client;

import io.wifi.cards.common.network.CommonPackets.MenuQueryC2S;
import io.wifi.cards.common.network.CommonPackets.OpenGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/**
 * 小游戏菜单界面：可滚动游戏列表（拓展性好——条目完全由服务端 OpenMenuS2C 驱动，
 * 新游戏在注册表登记后自动出现）。
 * <ul>
 *   <li>每行：左侧彩色图标（游戏名第一个字）+ 游戏名/简介 + 右侧实时统计（房间/在线）</li>
 *   <li>滚轮滚动；点击条目 → OpenGameC2S → 服务端路由打开对应游戏大厅</li>
 *   <li>当前进行中的游戏以金色左边条 +「（当前）」标记</li>
 *   <li>ESC 关闭时恢复进行中的游戏会话界面（数据仍在各 ClientState 中）</li>
 * </ul>
 */
public class GameMenuScreen extends Screen {
    /** 菜单条目（由 OpenMenuS2C 平行数组组装）。 */
    public record Entry(String gameId, String name, String icon, String desc, int color, int roomCount, int playerCount) {
    }

    private static final int ROW_H = 46;
    private static final int LIST_W = 320;

    /** 刷新按钮冷却时长（毫秒）：客户端层防连点刷包；服务端另有 500ms 限频兜底绕过客户端的恶意发包。 */
    private static final long REFRESH_COOLDOWN_MS = 1000;

    private final List<Entry> entries;
    /** 列表滚动偏移（≥0，滚轮向下滚动）。 */
    private float scroll;
    /** 刷新按钮（冷却中禁用并显示剩余秒数）。 */
    private Button refreshButton;
    /**
     * 上次刷新请求时间（毫秒）。静态：每次刷新成功后服务端回发菜单数据并新建本界面实例，
     * 实例字段会在重建时清零导致冷却丢失（看起来"没冷却"）；静态跨实例保留冷却状态。
     */
    private static long lastRefreshMillis;
    /** 滚动条拖拽状态（按下时的鼠标 y / 滚动偏移，用于增量换算）。 */
    private boolean draggingScroll;
    private double dragStartMouseY;
    private int dragStartScroll;

    public GameMenuScreen(List<Entry> entries) {
        super(Component.translatable("wifi_card_games.menu.title"));
        this.entries = entries;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不渲染模糊/纹理背景，仅由内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        // 刷新按钮放标题条内（列表从 y=40 开始，避免窄窗口下与列表重叠、吞掉点击）。
        // 客户端冷却 + 服务端限频双层防海量请求
        refreshButton = Button.builder(Component.translatable("wifi_card_games.menu.refresh"), b -> {
            if (System.currentTimeMillis() - lastRefreshMillis < REFRESH_COOLDOWN_MS) {
                return; // 冷却中（按钮已禁用，此处双保险）
            }
            lastRefreshMillis = System.currentTimeMillis();
            ClientPlayNetworking.send(new MenuQueryC2S());
        }).bounds(width - 66, 3, 60, 20).build();
        addRenderableWidget(refreshButton);
    }

    /** 每 tick 更新刷新按钮冷却状态：冷却中禁用并显示剩余秒数，结束恢复可点。 */
    @Override
    public void tick() {
        super.tick();
        if (refreshButton == null) {
            return;
        }
        long remaining = REFRESH_COOLDOWN_MS - (System.currentTimeMillis() - lastRefreshMillis);
        if (remaining > 0) {
            refreshButton.active = false;
            refreshButton.setMessage(Component.translatable("wifi_card_games.menu.refresh_cd", (remaining + 999) / 1000));
        } else {
            refreshButton.active = true;
            refreshButton.setMessage(Component.translatable("wifi_card_games.menu.refresh"));
        }
    }

    /** 关闭菜单（Esc）：若正在某个小游戏中，恢复其界面（防回不去）；否则关闭到桌面。 */
    @Override
    public void onClose() {
        if (GameMenuClient.tryRestoreSession()) {
            return;
        }
        super.onClose();
    }

    // ---------------- 列表布局/滚动 ----------------

    private int listLeft() {
        return (width - LIST_W) / 2;
    }

    private int listTop() {
        return 40;
    }

    private int listBottom() {
        return height - 40;
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * ROW_H - (listBottom() - listTop()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll() > 0) {
            scroll -= (float) verticalAmount * 12;
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    /** 点击列表行：播放点击提示音（行非 Button 控件，需手动播）并发送 OpenGameC2S 打开对应游戏。
     *  点击滚动条（轨道/滑块）：跳转到该处并进入拖拽。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hitScrollbar(mouseX, mouseY)) {
            draggingScroll = true;
            dragStartMouseY = mouseY;
            dragStartScroll = GuiUtil.scrollFromY((int) mouseY, scrollbarY(), scrollbarH(), maxScroll());
            scroll = dragStartScroll;
            return true;
        }
        if (button == 0 && mouseX >= listLeft() && mouseX < listLeft() + LIST_W
                && mouseY >= listTop() && mouseY < listBottom()) {
            int idx = (int) ((mouseY - listTop() + scroll) / ROW_H);
            if (idx >= 0 && idx < entries.size()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6F, 1.0F);
                }
                ClientPlayNetworking.send(new OpenGameC2S(entries.get(idx).gameId()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 拖拽滚动条：按鼠标位移增量换算滚动偏移（clamp 到合法范围）。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll) {
            int target = dragStartScroll
                    + (int) ((mouseY - dragStartMouseY) * maxScroll() / GuiUtil.sliderRange(scrollbarH(), maxScroll()));
            scroll = Math.max(0, Math.min(target, maxScroll()));
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

    // ---------------- 滚动条几何（与 render 绘制一致） ----------------

    private int scrollbarX() {
        return listLeft() + LIST_W + 4;
    }

    private int scrollbarY() {
        return listTop() - 4;
    }

    private int scrollbarH() {
        return listBottom() - listTop() + 8;
    }

    /** 命中滚动条轨道（含滑块）区域（轨道宽 2px，判定放宽到 6px 便于点击）。 */
    private boolean hitScrollbar(double mouseX, double mouseY) {
        return mouseX >= scrollbarX() - 2 && mouseX < scrollbarX() + 4
                && mouseY >= scrollbarY() && mouseY < scrollbarY() + scrollbarH();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 标题条先绘制（刷新按钮位于标题条内，super.render 后渲染按钮会盖住标题条半透明底）
        int cx = width / 2;
        g.fill(0, 0, width, 26, 0x66000000);
        g.drawCenteredString(this.font, Component.translatable("wifi_card_games.menu.title"), cx, 9, 0xFFFFD700);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        if (entries.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("wifi_card_games.menu.empty"), cx, listTop() + 20, 0xFFAAAAAA);
            return;
        }
        int left = listLeft();
        int top = listTop();
        int bottom = listBottom();
        String active = GameMenuClient.activeGameId();
        // 列表底板
        g.fill(left - 4, top - 4, left + LIST_W + 4, bottom + 4, 0x55000000);
        // 只绘制可见行（滚动裁剪）
        for (int i = (int) (scroll / ROW_H); i < entries.size(); i++) {
            int y = top + i * ROW_H - (int) scroll;
            if (y >= bottom) {
                break;
            }
            Entry e = entries.get(i);
            boolean current = e.gameId().equals(active);
            boolean hover = mouseX >= left && mouseX < left + LIST_W && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                g.fill(left, y, left + LIST_W, y + ROW_H, 0x22FFFFFF);
            }
            if (current) {
                // 当前游戏：金色左边条
                g.fill(left, y, left + 2, y + ROW_H, 0xFFD700);
            }
            // 左侧图标（游戏名第一个字）
            g.fill(left + 8, y + 8, left + 38, y + 38, e.color());
            g.drawCenteredString(this.font, Component.translatable(e.icon()), left + 23, y + 17, 0xFFFFFFFF);
            // 名称 + 简介（名称/简介为翻译键，客户端解析显示）
            MutableComponent name = Component.translatable(e.name());
            if (current) {
                name.append(Component.translatable("wifi_card_games.menu.current"));
            }
            g.drawString(this.font, name, left + 46, y + 8, 0xFFFFFFFF);
            g.drawString(this.font, Component.translatable(e.desc()), left + 46, y + 23, 0xFFAAAAAA);
            // 右侧统计
            Component stats = Component.translatable("wifi_card_games.menu.stats", e.roomCount(), e.playerCount());
            g.drawString(this.font, stats, left + LIST_W - 8 - this.font.width(stats), y + 15, 0xFFFFFF66);
        }
        // 底部提示
        g.drawCenteredString(this.font, Component.translatable("wifi_card_games.menu.footer_hint"), cx, height - 26, 0xFF777777);
        if (maxScroll() > 0) {
            // 滚动条（列表右缘外侧，滑块比例 = 可视/内容）
            GuiUtil.drawScrollbar(g, scrollbarX(), scrollbarY(), scrollbarH(), (int) scroll, maxScroll());
            g.drawCenteredString(this.font, Component.translatable("wifi_card_games.menu.scroll_hint"), cx, height - 14, 0xFF888888);
        }
    }
}
