package io.wifi.cards.doudizhu.gui;

import io.wifi.cards.common.GameRegistry;
import io.wifi.cards.common.client.AbstractLobbyScreen;
import io.wifi.cards.common.client.LobbyPrefs;
import io.wifi.cards.doudizhu.network.DdzPackets.CreateRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.JoinRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.LeaveRoomC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.LobbyQueryC2S;
import io.wifi.cards.doudizhu.network.DdzPackets.SpectateC2S;
import io.wifi.cards.doudizhu.rule.DdzRuleSet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 斗地主大厅界面（继承 {@link AbstractLobbyScreen}，共享标题条/主菜单/房间列表/复制/滚动）：
 * <ul>
 *   <li>未在房间：选择模式（经典/花牌）、规则集（标准/民间）、是否公布房间到聊天栏、创建房间、
 *       输入房间码加入、规则介绍、公开房间列表（可加入/旁观/复制房间码）</li>
 *   <li>已在房间（等待中）：显示房间码/模式/规则/成员，可离开（点击房间码可复制）</li>
 * </ul>
 */
public class DdzLobbyScreen extends AbstractLobbyScreen {
    private boolean flowerMode;
    private DdzRuleSet ruleSet = DdzRuleSet.STANDARD;
    /** 创建房间时是否公布到聊天栏（全服玩家可点击加入）。 */
    private boolean announce = true;
    /** 创建房间时加入的机器人数量（0~2，补位自动开局）。 */
    private int botCount;

    public DdzLobbyScreen() {
        super("斗地主大厅");
        // 记住上次开房间的选项（客户端 config 持久化），下次打开默认选中
        flowerMode = LobbyPrefs.getBool(GameRegistry.GAME_DOUDIZHU, "flowerMode", false);
        ruleSet = LobbyPrefs.getInt(GameRegistry.GAME_DOUDIZHU, "ruleSet", 0) == 1
                ? DdzRuleSet.FOLK : DdzRuleSet.STANDARD;
        announce = LobbyPrefs.getBool(GameRegistry.GAME_DOUDIZHU, "announce", true);
        botCount = Math.max(0, Math.min(LobbyPrefs.getInt(GameRegistry.GAME_DOUDIZHU, "botCount", 0), 2));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 取消全局背景虚化：不再渲染模糊/纹理背景，仅由各内容区块绘制半透明黑色背景。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    // ---------------- 基类钩子 ----------------

    @Override
    protected String gameId() {
        return GameRegistry.GAME_DOUDIZHU;
    }

    @Override
    protected boolean inRoomState() {
        return DdzClientState.INSTANCE.inRoom();
    }

    @Override
    protected String lobbyTitle() {
        return "斗地主大厅";
    }

    @Override
    protected int contentTop() {
        return Math.max(50, (height - 160) / 2) + (int) scroll;
    }

    @Override
    protected void sendRoomQuery() {
        ClientPlayNetworking.send(new LobbyQueryC2S());
    }

    @Override
    protected List<? extends RoomEntry> lobbyRoomList() {
        return DdzClientState.INSTANCE.roomList;
    }

    @Override
    protected void joinRoom(String code) {
        ClientPlayNetworking.send(new JoinRoomC2S(code));
    }

    @Override
    protected void spectateRoom(String code) {
        ClientPlayNetworking.send(new SpectateC2S(code));
    }

    @Override
    protected void lobbyChat(String message) {
        DdzClientState.chat(message);
    }

    @Override
    protected String currentRoomCode() {
        return DdzClientState.INSTANCE.roomCode;
    }

    @Override
    protected int[] roomInfoCodeRect() {
        if (!inRoomState()) {
            return null;
        }
        // 房间信息区"房间 XXX（模式 · 规则）"行（y≈34，行高 9，放宽点击区）
        return new int[]{width / 2 - 200, 30, width / 2 + 200, 44};
    }

    @Override
    protected void reopenHint() {
        DdzClientState.chatReopenHint("关闭大厅");
    }

    /** 房间操作按钮（离开房间）区底部 y："关闭界面"按钮放在其下方。 */
    @Override
    protected int roomActionBottomY() {
        return Math.max(40, height / 2 + 56) + 26;
    }

    /** 房间视图内容超高（小窗口）时同样可滚动。 */
    @Override
    protected int scrollLimit() {
        return inRoomState() ? roomMaxScroll() : maxScroll();
    }

    /** 房间内容区底部（信息面板 + 离开/关闭界面按钮行）。 */
    private int roomBottom() {
        return roomActionBottomY() + 20;
    }

    /** 房间内容超高时允许的滚动量（0 = 无需滚动）。 */
    private int roomMaxScroll() {
        return Math.max(0, roomBottom() - (height - 30));
    }

    // ---------------- 内容区控件 ----------------

    @Override
    protected void buildContent() {
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            // 两列紧凑布局：左列选项（模式/规则/公布/机器人），右列操作（创建/输入框/加入/规则介绍）
            int top = contentTop();
            int lx = cx - 172;
            int rx = cx + 12;
            addRenderableWidget(Button.builder(Component.literal("模式：" + (flowerMode ? "花牌（万能牌）" : "经典")), b -> {
                flowerMode = !flowerMode;
                LobbyPrefs.set(GameRegistry.GAME_DOUDIZHU, "flowerMode", flowerMode);
                b.setMessage(Component.literal("模式：" + (flowerMode ? "花牌（万能牌）" : "经典")));
            }).bounds(lx, top, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则：" + ruleSet.displayName()), b -> {
                ruleSet = ruleSet == DdzRuleSet.STANDARD ? DdzRuleSet.FOLK : DdzRuleSet.STANDARD;
                LobbyPrefs.set(GameRegistry.GAME_DOUDIZHU, "ruleSet", ruleSet.ordinal());
                b.setMessage(Component.literal("规则：" + ruleSet.displayName()));
            }).bounds(lx, top + 24, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("公布房间：" + (announce ? "开" : "关")), b -> {
                announce = !announce;
                LobbyPrefs.set(GameRegistry.GAME_DOUDIZHU, "announce", announce);
                b.setMessage(Component.literal("公布房间：" + (announce ? "开" : "关")));
            }).bounds(lx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")), b -> {
                botCount = (botCount + 1) % 3; // 关 → 1 个 → 2 个
                LobbyPrefs.set(GameRegistry.GAME_DOUDIZHU, "botCount", botCount);
                b.setMessage(Component.literal("机器人：" + (botCount == 0 ? "关" : botCount + " 个")));
            }).bounds(lx, top + 72, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("创建房间"), b ->
                    ClientPlayNetworking.send(new CreateRoomC2S(flowerMode, (byte) ruleSet.ordinal(), announce, (byte) botCount)))
                    .bounds(rx, top, 160, 20).build());
            codeBox = new EditBox(this.font, rx, top + 24, 160, 20, Component.literal("房间码"));
            codeBox.setMaxLength(8);
            codeBox.setFilter(str -> str.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '-'));
            addRenderableWidget(codeBox);
            addRenderableWidget(Button.builder(Component.literal("加入房间"), b ->
                    ClientPlayNetworking.send(new JoinRoomC2S(codeBox.getValue().trim().toUpperCase())))
                    .bounds(rx, top + 48, 160, 20).build());
            addRenderableWidget(Button.builder(Component.literal("规则介绍"), b ->
                    Minecraft.getInstance().setScreen(new DdzRulesScreen()))
                    .bounds(rx, top + 72, 160, 20).build());
        } else {
            // 离开房间按钮：随滚动偏移（小窗口房间视图滚动时保持可见可点）
            addRenderableWidget(Button.builder(Component.literal("离开房间"), b ->
                    ClientPlayNetworking.send(new LeaveRoomC2S()))
                    .bounds(cx - 80, Math.max(40, height / 2 + 56) + (int) scroll, 160, 20).build());
        }
    }

    // ---------------- 渲染 ----------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 顶部标题条先绘制（主菜单按钮位于标题条内右上角，super.render 后渲染按钮盖住标题条半透明底）
        drawTitleBar(g);
        // 背景与控件由 super 渲染（renderBackground 已覆盖为空，无全局虚化），自定义内容绘制在其上
        super.render(g, mouseX, mouseY, partialTick);
        DdzClientState s = DdzClientState.INSTANCE;
        int cx = width / 2;
        if (!s.inRoom()) {
            int top = contentTop();
            int lx = cx - 172;
            // 提示区半透明黑底（位于内容区下方，不与按钮重叠）
            g.fill(cx - 180, top + 94, cx + 180, top + 126, 0x55000000);
            DdzGui.centeredShadow(g, this.font, width, "创建房间邀请好友一起玩，或输入房间码加入", top + 100, 0xFFAAAAAA);
            DdzGui.centeredShadow(g, this.font, width, "提示：房主可用 /cardgames invite <玩家名> 邀请", top + 114, 0xFF777777);
            // 公开房间列表（标题 + 行文本 + 滚动条，点击行操作按钮加入/旁观、点房间码复制）
            drawRoomList(g);
        } else {
            // 房间信息区半透明黑底（覆盖到最底部提示行 116 之下）
            g.fill(cx - 200, 30, cx + 200, 130, 0x55000000);
            DdzGui.centeredShadow(g, this.font, width,
                    "房间 " + s.roomCode + "（" + (s.flowerMode ? "花牌模式" : "经典模式")
                            + " · " + s.ruleSet.displayName() + "）",
                    34, 0xFFFFFF88);
            DdzGui.centeredShadow(g, this.font, width, "玩家 " + s.roomSize() + " / 3", 50, 0xFFFFFFFF);
            for (int i = 0; i < 3; i++) {
                String line = (i == s.mySeat ? "▶ " : "  ") + (i + 1) + ". "
                        + (s.names[i] == null || s.names[i].isEmpty() ? "等待加入…" : s.names[i]);
                DdzGui.centeredShadow(g, this.font, width, line, 68 + i * 14,
                        i == s.mySeat ? 0xFFFFFF55 : 0xFFFFFFFF);
            }
            DdzGui.centeredShadow(g, this.font, width, "满 3 人自动开始", 116, 0xFFAAAAAA);
        }
    }
}
