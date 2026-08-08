package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.GameMenuClient;
import io.wifi.cards.doudizhu.network.DdzPackets.LeaveRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.NextGameC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 结算界面：显示胜负、分数明细（底分 × 倍数），可选择再来一局（房间不散重开）或返回大厅。
 */
public class DdzResultScreen extends Screen {
    public DdzResultScreen() {
        super(Component.translatable("wifi_card_games.ddz.result.title"));
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
        if (GameMenuClient.tryRestoreOtherSession(GameRegistry.GAME_DOUDIZHU)) {
            return;
        }
        DdzClientState.chatReopenHint(Component.translatable("wifi_card_games.ddz.reopen.closed_result"));
        super.onClose();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        DdzClientState s = DdzClientState.INSTANCE;
        if (s.mySeat < 0) {
            // 旁观者：无"再来一局"权限（新局由成员触发），仅提供退出旁观返回大厅
            addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.ddz.button.exit_spectate"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 55, height / 2 + 44, 110, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.ddz.button.next_game"), b ->
                ClientPlayNetworking.send(new NextGameC2S()))
                .bounds(cx - 120, height / 2 + 44, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("wifi_card_games.ddz.button.back_lobby"), b ->
                ClientPlayNetworking.send(new LeaveRoomC2S()))
                .bounds(cx + 10, height / 2 + 44, 110, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 背景与控件由 super 渲染（含 renderBackground），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        // 顶部标题条
        g.fill(0, 0, width, 26, 0x66000000);
        DdzGui.centeredShadow(g, this.font, width, Component.translatable("wifi_card_games.ddz.result.title"), 9, 0xFFFFD700);
        // 结算信息区半透明黑底（覆盖到最底部玩家行 124 之下）
        g.fill(cx - 200, 30, cx + 200, 138, 0x55000000);
        DdzGui.centeredShadow(g, this.font, width,
                Component.translatable(s.resultLandlordWin
                        ? "wifi_card_games.ddz.result.landlord_win" : "wifi_card_games.ddz.result.farmer_win"),
                40, s.resultLandlordWin ? 0xFFFFFF55 : 0xFFFF5555);
        DdzGui.centeredShadow(g, this.font, width,
                Component.translatable("wifi_card_games.ddz.result.landlord", s.resultLandlordName), 58, 0xFFFFFFFF);
        int unit = s.resultBaseScore * s.resultMultiplier;
        DdzGui.centeredShadow(g, this.font, width,
                Component.translatable("wifi_card_games.ddz.result.score_detail",
                        s.resultBaseScore, s.resultMultiplier, unit),
                76, 0xFFFFFFFF);
        for (int i = 0; i < 3; i++) {
            if (s.names[i] == null || s.names[i].isEmpty()) {
                continue;
            }
            String sign = s.resultDeltas[i] > 0 ? "+" : "";
            Component line = Component.literal(s.names[i])
                    .append(Component.translatable("wifi_card_games.ddz.result.delta", sign + s.resultDeltas[i]));
            if (i == s.landlordSeat) {
                line = line.copy().append(Component.translatable("wifi_card_games.ddz.tag.landlord"));
            }
            DdzGui.centeredShadow(g, this.font, width, line, 96 + i * 14,
                    i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
        }
    }
}
