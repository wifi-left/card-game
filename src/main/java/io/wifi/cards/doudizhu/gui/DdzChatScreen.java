package io.wifi.cards.doudizhu.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * 打牌界面内的聊天屏（纯客户端）：原版聊天框的子类。
 * <p>原版 ChatScreen 在 Esc / 发送消息后会直接 <code>setScreen(null)</code>（回到游戏 HUD），
 * 这里覆盖 keyPressed 的 Esc/Enter 分支，关掉聊天框后回到打开前的打牌界面；
 * 背景渲染打牌界面（聊天历史由本类自行渲染，避免与打牌界面常驻聊天区重复）。</p>
 */
public class DdzChatScreen extends ChatScreen {
    private final Screen parent;

    public DdzChatScreen(Screen parent) {
        super("");
        this.parent = parent;
    }

    /** 打开聊天：保留原版输入框初始化（背景音乐由 DdzGameScreen.tickBgm 统一管理，不在此处理）。 */
    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {        if (parent != null) {
            // 背景：打牌界面（聊天历史由下方 ChatComponent 渲染，打牌界面本身不绘制聊天区）
            parent.render(g, 0, 0, partialTick);
            // 原版聊天内容：历史消息 + 输入框（不画虚化背景）
            Minecraft mc = Minecraft.getInstance();
            mc.gui.getChat().render(g, mc.gui.getGuiTicks(), mouseX, mouseY, true);
            g.fill(2, height - 14, width - 2, height - 2,
                    mc.options.getBackgroundColor(Integer.MIN_VALUE));
            this.input.render(g, mouseX, mouseY, partialTick);
        } else {
            super.render(g, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        if (keyCode == 256) { // Esc：原版直接 setScreen(null)，这里回到打牌界面
            mc.setScreen(parent);
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter：原版发送后 setScreen(null)，这里保持打牌界面
            super.handleChatInput(this.input.getValue(), true);
            mc.setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
