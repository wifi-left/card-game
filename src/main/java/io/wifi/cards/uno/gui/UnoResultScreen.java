package io.wifi.cards.uno.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.uno.network.UnoPackets.LeaveRoomC2S;
import io.wifi.cards.uno.network.UnoPackets.NextGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 结算界面：显示本局胜者，可选择再来一局（房间不散重开）或返回大厅。
 */
public class UnoResultScreen extends Screen {
    public UnoResultScreen() {
        super(Component.translatable("wifi_card_games.uno.result.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    /** 关闭结算界面（Esc）：先恢复其它游戏的进行中会话，否则提示重开（房间仍在 SETTLED 保留）。 */
    @Override
    public void onClose() {
        if (GameMenuClient.tryRestoreOtherSession(GameRegistry.GAME_UNO)) {
            return;
        }
        UnoClientState.chatReopenHint(Component.translatable("wifi_card_games.uno.reopen.closed_result"));
        super.onClose();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        UnoClientState s = UnoClientState.INSTANCE;
        if (s.mySeat < 0) {
            // 旁观者：无"再来一局"权限（新局由成员触发），仅提供退出旁观返回大厅
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.button.exit_spectate"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 55, height / 2 + 44, 110, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.button.next_game"), b ->
                ClientPlayNetworking.send(new NextGameC2S()))
                .bounds(cx - 120, height / 2 + 44, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.uno.button.back_lobby"), b ->
                ClientPlayNetworking.send(new LeaveRoomC2S()))
                .bounds(cx + 10, height / 2 + 44, 110, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（含 renderBackground），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        UnoClientState s = UnoClientState.INSTANCE;
        int cx = width / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        UnoGui.centeredShadow(g, this.font, width, Component.translatable("wifi_card_games.uno.result.title"), 9, 0xFFFFD700);
        // 结算信息区半透明黑底（高度随人数自适应，最多 10 人时行不溢出面板；
        // 封顶 height/2-30 保证矮窗口下面板不遮"再来一局/返回大厅"按钮行（y=height/2+44））
        int infoH = Math.min(50 + s.names.size() * 14 + 8, Math.max(60, height / 2 - 30));
        g.fill(cx - 200, 30, cx + 200, 30 + infoH, 0x55000000);
        UnoGui.centeredShadow(g, this.font, width,
                Component.translatable("wifi_card_games.uno.result.winner", s.winnerName), 42, 0xFFFFD700);
        UnoGui.centeredShadow(g, this.font, width,
                Component.translatable("wifi_card_games.uno.result.subtitle"), 62, 0xFFAAAAAA);
        // 各家剩余手牌
        for (int i = 0; i < s.names.size(); i++) {
            String name = s.names.get(i);
            if (name == null || name.isEmpty()) {
                continue;
            }
            Component line = Component.literal(name)
                    .append(Component.translatable("wifi_card_games.uno.result.remaining", s.countOf(i)));
            if (i == s.winnerSeat) {
                line = line.copy().append(Component.translatable("wifi_card_games.uno.result.winner_tag"));
            }
            UnoGui.centeredShadow(g, this.font, width, line, 80 + i * 14,
                    i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
        }
    }
}
