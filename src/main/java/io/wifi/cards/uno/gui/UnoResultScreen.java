package io.wifi.cards.uno.gui;

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
        super(Component.literal("本局结算"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        int cx = width / 2;
        UnoClientState s = UnoClientState.INSTANCE;
        if (s.mySeat < 0) {
            // 旁观者：无"再来一局"权限（新局由成员触发），仅提供退出旁观返回大厅
            addRenderableWidget(Button.builder(Component.literal("退出旁观"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 55, height / 2 + 44, 110, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("再来一局"), b ->
                ClientPlayNetworking.send(new NextGameC2S()))
                .bounds(cx - 120, height / 2 + 44, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回大厅"), b ->
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
        UnoGui.centeredShadow(g, this.font, width, "本局结算", 9, 0xFFFFD700);
        // 结算信息区半透明黑底（高度随人数自适应，最多 10 人时行不溢出面板；
        // 封顶 height-110 保证矮窗口下面板不遮"再来一局/返回大厅"按钮）
        int infoH = Math.min(50 + s.names.size() * 14 + 8, Math.max(60, height - 110));
        g.fill(cx - 200, 30, cx + 200, 30 + infoH, 0x55000000);
        String title = "🎉 " + s.winnerName + " 出完了所有牌，获得胜利！";
        UnoGui.centeredShadow(g, this.font, width, title, 42, 0xFFFFD700);
        UnoGui.centeredShadow(g, this.font, width, "先出完手牌者为胜", 62, 0xFFAAAAAA);
        // 各家剩余手牌
        for (int i = 0; i < s.names.size(); i++) {
            String name = s.names.get(i);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String line = name + "：剩余 " + s.countOf(i) + " 张"
                    + (i == s.winnerSeat ? "（胜者）" : "");
            UnoGui.centeredShadow(g, this.font, width, line, 80 + i * 14,
                    i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
        }
    }
}
