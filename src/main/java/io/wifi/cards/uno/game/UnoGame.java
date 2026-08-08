package io.wifi.cards.uno.game;

import io.wifi.cards.uno.card.UnoCard;
import io.wifi.cards.uno.card.UnoColor;
import io.wifi.cards.uno.card.UnoDeck;
import io.wifi.cards.uno.manager.UnoRoom;
import io.wifi.cards.uno.model.UnoGamePhase;
import io.wifi.cards.uno.model.UnoPlayer;
import io.wifi.cards.uno.network.UnoPackets.DrawBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawPenaltyS2C;
import io.wifi.cards.uno.network.UnoPackets.DrawResultS2C;
import io.wifi.cards.uno.network.UnoPackets.GameResultS2C;
import io.wifi.cards.uno.network.UnoPackets.GameStartS2C;
import io.wifi.cards.uno.network.UnoPackets.HistoryS2C;
import io.wifi.cards.uno.network.UnoPackets.NoticeS2C;
import io.wifi.cards.uno.network.UnoPackets.PassBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.PlayBroadcastS2C;
import io.wifi.cards.uno.network.UnoPackets.ReconnectS2C;
import io.wifi.cards.uno.network.UnoPackets.SpectatorHandsS2C;
import io.wifi.cards.uno.network.UnoPackets.TrustStateS2C;
import io.wifi.cards.uno.network.UnoPackets.TurnS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoCatchS2C;
import io.wifi.cards.uno.network.UnoPackets.UnoDeclaredS2C;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * UNO 对局状态机（服务端权威，纯内存，最多 10 人）。
 * <p>流程：发牌（每人 7 张，翻第一张起牌须为数字牌）→ 座位 0 先出（随机起始）→
 * 出牌/抽牌 → 先出完手牌者获胜（单局制）。</p>
 * <p>规则要点：同色或同点数可打，万能牌任意可打（选色）；跳过跳下家；反转反转方向
 * （2 人局视为跳过）；+2 下家罚 2 张并跳过；万能+4 选色 + 下家罚 4 张并跳过；
 * 抽牌（私发牌面）：抽到可打的牌可打出或跳过，不可打自动跳过；牌堆空时洗回弃牌堆（保留顶牌）。
 * 不做"万能+4 须无同色可打"的合法限制（休闲规则）。</p>
 * <p>UNO 喊牌：打出倒数第二张（手牌剩 1）后进入抓捕窗口，须手动喊 UNO；
 * 未喊且被其他玩家抓到罚 2 张；窗口在下一次轮到自己时关闭；打出最后一张立即获胜。
 * 机器人/托管自动喊牌、不抓人。</p>
 * <p>托管：主动开启 / 超时（30 秒）/ 断线自动触发；假人常驻托管。重连由
 * UnoMemoryManager 替换连接引用并同步快照（ReconnectS2C）。</p>
 */
public class UnoGame {
    /** 每回合行动时限（秒）。 */
    public static final int TURN_SECONDS = 30;
    /** 开局每人手牌数。 */
    public static final int START_HAND = 7;
    /** 本局事件历史上限（最新在前，超出裁掉最旧的）。 */
    private static final int HISTORY_LIMIT = 100;

    private final UnoRoom room;
    private final List<UnoPlayer> players = new ArrayList<>();
    private UnoGamePhase phase = UnoGamePhase.WAITING;

    /** 抽牌堆（尾部为顶）。 */
    private final List<UnoCard> deck = new ArrayList<>();
    /** 弃牌堆（尾部为顶牌）。 */
    private final List<UnoCard> discard = new ArrayList<>();
    /** 当前行动座位（players 下标）。 */
    private int currentSeat;
    /** 出牌方向：+1 顺时针 / -1 逆时针。 */
    private int direction = 1;
    /** 当前有效颜色：普通牌顶牌颜色；万能牌为出牌者所选颜色。 */
    private UnoColor chosenColor = UnoColor.NONE;
    /** 本回合是否已抽牌且抽到的牌可打（此时可打出或跳过；否则不能跳过）。 */
    private boolean drawnPlayable;
    /** 各座位 UNO 抓捕窗口（手牌剩 1 且未喊 UNO 时其他玩家可抓）。 */
    private final boolean[] unoCatchable = new boolean[UnoRoom.MAX_PLAYERS];
    /** 各座位本局是否已喊 UNO。 */
    private final boolean[] declaredUno = new boolean[UnoRoom.MAX_PLAYERS];

    private int winnerSeat = -1;
    private String winnerName = "";

    /** 本局事件历史（最新在前）：(玩家名, 事件描述)。 */
    private final List<String> historyNames = new ArrayList<>();
    private final List<String> historyTexts = new ArrayList<>();
    /** 行动计数：玩家主动行动（出牌/抽牌/跳过）时递增，用于 UNO 自动抓取宽限判定。 */
    private int actionCount;
    /** 各座位抓捕窗口开启时的行动计数（按座位独立，防他人开窗覆盖）：
     *  自动抓取要求窗口开启后至少经过一次他人行动，
     *  否则 2 人局打出跳过/反转/+2/+4 后回合立即回轮自己，玩家来不及喊 UNO 就被罚。 */
    private final int[] unoWindowOpenedAction = new int[UnoRoom.MAX_PLAYERS];

    private long turnEndGameTime;
    private int tickCounter;
    /** 托管/机器人在出牌阶段自动行动的延迟（tick，1 秒：晚一秒再出，而非轮到立即出）。 */
    private static final int AUTO_ACT_DELAY_TICKS = 20;
    private boolean pendingAutoAct;
    private int pendingAutoActSeat = -1;
    private long autoActDueGameTime;
    private int autoActDelayCounter;
    /** 服务端世界引用（用于游戏刻计时；全假人房间为 null）。 */
    private final ServerLevel level;
    private final Random random = new Random();

    public UnoGame(UnoRoom room) {
        this.room = room;
        ServerLevel foundLevel = null;
        for (int i = 0; i < room.size(); i++) {
            String name = room.seatName(i);
            if (name.isEmpty()) {
                name = "???";
            }
            UUID uuid = room.isBot(i)
                    ? UUID.nameUUIDFromBytes(("uno-bot-" + i).getBytes(StandardCharsets.UTF_8))
                    : room.members.get(i).getUUID();
            if (foundLevel == null && room.members.get(i) != null) {
                foundLevel = room.members.get(i).serverLevel();
            }
            players.add(new UnoPlayer(uuid, name, i));
        }
        this.level = foundLevel;
    }

    public UnoGamePhase phase() {
        return phase;
    }

    /** 当前行动座位（调试命令用）。 */
    public int currentSeat() {
        return currentSeat;
    }

    /** 某座位的手牌（调试命令用）。 */
    public List<UnoCard> handOf(int seat) {
        return players.get(seat).hand();
    }

    /** 某座位是否处于托管状态（管理命令显示用）。 */
    public boolean isTrusted(int seat) {
        return seat >= 0 && seat < players.size() && players.get(seat).trusted();
    }

    /** 开局：洗牌发牌，翻起牌（须为数字牌），随机起始座位。 */
    public void start() {
        phase = UnoGamePhase.PLAYING;
        winnerSeat = -1;
        winnerName = "";
        historyNames.clear();
        historyTexts.clear();
        actionCount = 0;
        Arrays.fill(unoWindowOpenedAction, -1);
        deck.clear();
        deck.addAll(UnoDeck.shuffled(random));
        discard.clear();
        for (UnoPlayer p : players) {
            p.hand().clear();
            p.setDeclaredUno(false);
            p.setTrusted(false); // 新局重置托管：上局托管状态不得残留到下一局
        }
        Arrays.fill(unoCatchable, false);
        Arrays.fill(declaredUno, false);
        for (int i = 0; i < START_HAND; i++) {
            for (UnoPlayer p : players) {
                p.hand().add(drawFromDeck());
            }
        }
        // 起牌必须是数字牌（功能/万能牌放回重翻）
        UnoCard first;
        do {
            first = drawFromDeck();
        } while (!first.value().isNumber());
        discard.add(first);
        chosenColor = first.color();
        direction = 1;
        int starter = random.nextInt(players.size());
        for (int i = 0; i < players.size(); i++) {
            room.sendToSeat(i, new GameStartS2C((byte) i, ids(players.get(i).hand()), (byte) starter,
                    first.id(), (byte) chosenColor.ordinal(), remainingCounts()));
        }
        // 旁观者：重发新局快照（mySeat=-1、空手牌），重置其客户端上一局残留状态
        for (ServerPlayer sp : room.spectators) {
            room.sendToSpectator(sp, new GameStartS2C((byte) -1, new int[0], (byte) starter,
                    first.id(), (byte) chosenColor.ordinal(), remainingCounts()));
        }
        sendHandsToSpectators();
        turn(starter);
    }

    /** 出牌。colorOrdinal 仅万能牌时有效（0~3 选色），普通牌忽略。 */
    public void onPlay(ServerPlayer player, int cardId, byte colorOrdinal) {
        UnoPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != UnoGamePhase.PLAYING) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_playing_phase"));
            return;
        }
        if (p.seat() != currentSeat) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_your_play"));
            return;
        }
        if (cardId < 0 || cardId >= UnoCard.TOTAL_COUNT) {
            reject(p, Component.translatable("wifi_card_games.uno.error.invalid_card"));
            return;
        }
        UnoCard card = UnoCard.byId(cardId);
        if (!p.hand().contains(card)) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_have_card"));
            return;
        }
        if (!canPlay(card, topCard(), chosenColor)) {
            reject(p, Component.translatable("wifi_card_games.uno.error.card_cannot_play"));
            return;
        }
        UnoColor color;
        if (card.value().isWild()) {
            if (colorOrdinal < 0 || colorOrdinal >= UnoColor.values().length || !UnoColor.values()[colorOrdinal].isColored()) {
                reject(p, Component.translatable("wifi_card_games.uno.error.choose_color"));
                return;
            }
            color = UnoColor.values()[colorOrdinal];
        } else {
            // 普通牌：当前有效颜色更新为所打牌的颜色。
            // 此前保持旧值导致"同点数换色"（如红 5 上打绿 5）后仍按旧颜色判定，
            // 顶牌显示绿色却打不出绿牌（canPlay 按旧颜色匹配被拒）
            color = card.color();
        }
        p.hand().remove(card);
        discard.add(card);
        chosenColor = color;
        drawnPlayable = false;
        room.broadcast(new PlayBroadcastS2C((byte) p.seat(), p.name(), cardId,
                (byte) chosenColor.ordinal(), remainingCounts()));
        // 历史：打出 X（万能牌带所选颜色）
        addHistory(p.name(), (card.value().isWild()
                ? "wifi_card_games.uno.history.played_color|" + card.display() + "|" + color.displayName()
                : "wifi_card_games.uno.history.played|" + card.display()));
        // 旁观者：出牌后同步各家手牌（透视视角实时更新）
        sendHandsToSpectators();
        // 行动计数（自动抓取宽限判定）后开窗：记录本座位窗口开启时的计数，
        // 自动抓取要求其后至少经过一次他人行动（2 人局功能牌回轮不立即罚；
        // 按座位独立记录，他人开窗不覆盖本座位计数）
        actionCount++;
        if (p.hand().size() == 1) {
            // 打出倒数第二张：进入 UNO 抓捕窗口。这是新的"剩 1 张"周期——
            // 重置已喊标记（此前若喊过 UNO 后又因罚牌离开 1 张状态，须重新喊；
            // 不重置会导致第二次窗口期无人可抓、窗口永不关闭），机器人/托管立即自动喊牌
            unoCatchable[p.seat()] = true;
            declaredUno[p.seat()] = false;
            unoWindowOpenedAction[p.seat()] = actionCount;
            if (room.isBot(p.seat()) || p.trusted()) {
                declareUnoInternal(p.seat());
            }
        }
        if (p.hand().isEmpty()) {
            settle(p.seat());
            return;
        }
        applyEffect(card, p.seat());
    }

    /** 抽牌：抽 1 张（牌面只发给本人）。抽到可打的牌可打出或跳过，否则自动跳过。 */
    public void onDraw(ServerPlayer player) {
        UnoPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != UnoGamePhase.PLAYING) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_playing_phase"));
            return;
        }
        if (p.seat() != currentSeat) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_your_turn"));
            return;
        }
        if (drawnPlayable) {
            reject(p, Component.translatable("wifi_card_games.uno.error.already_drawn"));
            return;
        }
        UnoCard card = drawFromDeck();
        if (card == null) {
            // 极端兜底：无牌可抽（全部牌都在玩家手中），视为跳过（同样计入行动计数）
            room.broadcast(new PassBroadcastS2C((byte) p.seat(), remainingCounts()));
            actionCount++;
            addHistory(p.name(), "wifi_card_games.uno.history.skipped");
            turn(nextSeat(p.seat()));
            return;
        }
        p.hand().add(card);
        // 手牌离开"剩 1 张"状态：关闭抓捕窗口（与 drawTo 的罚牌处理一致，
        // 否则手牌 2 张时仍显示可被抓/喊 UNO 按钮）
        if (p.hand().size() != 1) {
            unoCatchable[p.seat()] = false;
        }
        boolean playable = canPlay(card, topCard(), chosenColor);
        room.broadcast(new DrawBroadcastS2C((byte) p.seat(), remainingCounts()));
        actionCount++;
        addHistory(p.name(), "wifi_card_games.uno.history.draw");
        room.sendToSeat(p.seat(), new DrawResultS2C(new int[]{card.id()}, playable));
        // 旁观者：抽牌后同步各家手牌（透视视角实时更新，否则面板显示过期手牌）
        sendHandsToSpectators();
        if (playable) {
            // 抽到可打的牌：刷新回合时限，可打出或跳过（按钮由客户端 drawnPlayable 状态驱动）
            drawnPlayable = true;
            turnEndGameTime = nowGameTime() + TURN_SECONDS * 20L;
            room.broadcast(new TurnS2C((byte) p.seat(), turnEndGameTime));
            if (p.trusted() || room.isBot(p.seat())) {
                scheduleAutoAct(p.seat());
            }
        } else {
            // 抽到的牌不可打：自动跳过
            room.broadcast(new PassBroadcastS2C((byte) p.seat(), remainingCounts()));
            turn(nextSeat(p.seat()));
        }
    }

    /** 跳过（仅限"抽到可打的牌"之后选择不打）。 */
    public void onPass(ServerPlayer player) {
        UnoPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != UnoGamePhase.PLAYING) {
            reject(p, Component.translatable("wifi_card_games.uno.error.cannot_skip"));
            return;
        }
        if (p.seat() != currentSeat) {
            reject(p, Component.translatable("wifi_card_games.uno.error.not_your_turn"));
            return;
        }
        if (!drawnPlayable) {
            reject(p, Component.translatable("wifi_card_games.uno.error.skip_without_draw"));
            return;
        }
        room.broadcast(new PassBroadcastS2C((byte) p.seat(), remainingCounts()));
        actionCount++;
        addHistory(p.name(), "wifi_card_games.uno.history.skipped");
        turn(nextSeat(p.seat()));
    }

    /** 喊 UNO（手牌剩 1 张时须喊；他人可抓未喊者罚 2 张）。 */
    public void onDeclareUno(ServerPlayer player) {
        UnoPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != UnoGamePhase.PLAYING) {
            reject(p, Component.translatable("wifi_card_games.uno.error.cannot_declare"));
            return;
        }
        if (p.hand().size() != 1 || !unoCatchable[p.seat()]) {
            reject(p, Component.translatable("wifi_card_games.uno.error.no_uno_needed"));
            return;
        }
        if (p.declaredUno()) {
            reject(p, Component.translatable("wifi_card_games.uno.error.already_declared"));
            return;
        }
        declareUnoInternal(p.seat());
    }

    /** 抓未喊 UNO 的玩家（成功罚 2 张，抓捕窗口关闭；已喊过则提示）。 */
    public void onCatchUno(ServerPlayer player, int targetSeat) {
        UnoPlayer catcher = playerOf(player);
        if (catcher == null) {
            return;
        }
        if (phase != UnoGamePhase.PLAYING) {
            reject(catcher, Component.translatable("wifi_card_games.uno.error.cannot_catch"));
            return;
        }
        if (targetSeat < 0 || targetSeat >= players.size()) {
            reject(catcher, Component.translatable("wifi_card_games.uno.error.invalid_target"));
            return;
        }
        if (targetSeat == catcher.seat()) {
            reject(catcher, Component.translatable("wifi_card_games.uno.error.cannot_catch_self"));
            return;
        }
        if (!unoCatchable[targetSeat] || players.get(targetSeat).declaredUno()) {
            reject(catcher, Component.translatable("wifi_card_games.uno.error.no_violation"));
            return;
        }
        drawTo(targetSeat, 2);
        unoCatchable[targetSeat] = false;
        room.broadcast(new UnoCatchS2C((byte) catcher.seat(), (byte) targetSeat, remainingCounts()));
        addHistory(catcher.name(), "wifi_card_games.uno.history.caught|" + players.get(targetSeat).name());
    }

    /** 开启/关闭指定玩家托管；开启且正轮到该玩家时立即安排自动行动。 */
    public void setTrust(ServerPlayer player, boolean enabled) {
        UnoPlayer p = playerOf(player);
        if (p != null) {
            setTrustSeat(p.seat(), enabled);
        }
    }

    /** 开启/关闭指定座位托管（真人与假人均可）；开启且正轮到该座位时安排自动行动（延迟 1 秒）。 */
    public void setTrustSeat(int seat, boolean enabled) {
        if (seat < 0 || seat >= players.size()) {
            return;
        }
        UnoPlayer p = players.get(seat);
        p.setTrusted(enabled);
        // 回传托管状态，客户端按钮（托管/取消托管）与服务端保持一致
        room.sendToSeat(seat, new TrustStateS2C(enabled));
        if (enabled && seat == currentSeat && phase == UnoGamePhase.PLAYING) {
            scheduleAutoAct(seat);
        } else if (!enabled && pendingAutoAct && pendingAutoActSeat == seat) {
            pendingAutoAct = false;
            pendingAutoActSeat = -1;
        }
    }

    /** 玩家断线：自动托管代打（对局继续），正轮到他时安排自动行动（延迟 1 秒）。 */
    public void onPlayerDisconnect(int seat) {
        if (seat < 0 || seat >= players.size()) {
            return;
        }
        players.get(seat).setTrusted(true);
        if (seat == currentSeat) {
            scheduleAutoAct(seat);
        }
    }

    /** 玩家重连：恢复手动控制（对局状态由 ReconnectS2C 快照同步）。 */
    public void onPlayerReconnect(int seat) {
        if (seat < 0 || seat >= players.size()) {
            return;
        }
        players.get(seat).setTrusted(false);
        if (pendingAutoAct && pendingAutoActSeat == seat) {
            pendingAutoAct = false;
            pendingAutoActSeat = -1;
        }
    }

    /** 发送当前对局完整快照给指定座位（断线重连用，含该玩家自己的手牌）。 */
    public void syncTo(int seat) {
        room.sendToSeat(seat, new ReconnectS2C(
                (byte) phase.ordinal(),
                ids(players.get(seat).hand()),
                (byte) currentSeat,
                turnEndGameTime,
                (byte) direction,
                topCard() != null ? topCard().id() : -1,
                (byte) chosenColor.ordinal(),
                remainingCounts(),
                drawnPlayable,
                Arrays.copyOf(unoCatchable, players.size()),
                Arrays.copyOf(declaredUno, players.size()),
                (byte) winnerSeat,
                winnerName));
        // 补发托管状态，重连后按钮与服务端保持一致
        room.sendToSeat(seat, new TrustStateS2C(players.get(seat).trusted()));
        resendResult(seat);
    }

    /** 向旁观者发送当前对局完整快照（无手牌：hand 为空数组，mySeat=-1 由客户端结合 RoomState 判定）。 */
    public void syncToSpectator(ServerPlayer spectator) {
        room.sendToSpectator(spectator, new ReconnectS2C(
                (byte) phase.ordinal(),
                new int[0],
                (byte) currentSeat,
                turnEndGameTime,
                (byte) direction,
                topCard() != null ? topCard().id() : -1,
                (byte) chosenColor.ordinal(),
                remainingCounts(),
                false,
                Arrays.copyOf(unoCatchable, players.size()),
                Arrays.copyOf(declaredUno, players.size()),
                (byte) winnerSeat,
                winnerName));
        sendHandsToSpectators();
    }

    /** 结算中重新下发结算结果（客户端关闭结算界面后 /uno 重开，或重连补发）。 */
    public void resendResult(int seat) {
        if (phase != UnoGamePhase.SETTLED || seat < 0 || seat >= players.size()) {
            return;
        }
        room.sendToSeat(seat, new GameResultS2C((byte) winnerSeat, winnerName));
    }

    /** 向旁观者下发各家完整手牌（透视视角；加入/出牌/罚牌/新局/结算时同步）。 */
    private void sendHandsToSpectators() {
        int[][] hands = new int[players.size()][];
        for (int i = 0; i < players.size(); i++) {
            hands[i] = ids(players.get(i).hand());
        }
        for (ServerPlayer sp : room.spectators) {
            room.sendToSpectator(sp, new SpectatorHandsS2C(hands));
        }
    }

    /** 服务端每 tick 调用：托管出牌延迟到点判断 + 超时判断（与客户端共用 level.getGameTime() 基准）。 */
    public void tick() {
        if (phase != UnoGamePhase.PLAYING) {
            return;
        }
        if (pendingAutoAct && currentSeat == pendingAutoActSeat) {
            boolean due = level != null
                    ? level.getGameTime() >= autoActDueGameTime
                    : ++autoActDelayCounter >= AUTO_ACT_DELAY_TICKS;
            if (due) {
                pendingAutoAct = false;
                autoAct(currentSeat);
                return;
            }
        }
        if (level != null) {
            if (level.getGameTime() >= turnEndGameTime) {
                autoAct(currentSeat);
            }
        } else {
            // 全假人房间（无世界引用）：退化为本地 tick 计数
            tickCounter++;
            if (tickCounter >= TURN_SECONDS * 20) {
                tickCounter = 0;
                autoAct(currentSeat);
            }
        }
    }

    /** 自动行动（托管/机器人/超时）：先补喊 UNO，再按策略出牌，无牌可打则抽牌。 */
    private void autoAct(int seat) {
        UnoPlayer p = players.get(seat);
        if (unoCatchable[seat] && !p.declaredUno()) {
            declareUnoInternal(seat);
        }
        if (drawnPlayable) {
            // 已抽到可打的牌：打出任意可打的牌；没有则跳过
            UnoCard play = findBotPlay(p.hand());
            if (play != null) {
                onPlay(null, play.id(), botColorOrdinal(seat));
            } else {
                onPass(null);
            }
            return;
        }
        UnoCard play = findBotPlay(p.hand());
        if (play != null) {
            onPlay(null, play.id(), botColorOrdinal(seat));
        } else {
            onDraw(null);
            if (drawnPlayable) {
                // 抽到了可打的牌：继续打出
                UnoCard drawn = findBotPlay(players.get(seat).hand());
                if (drawn != null) {
                    onPlay(null, drawn.id(), botColorOrdinal(seat));
                }
            }
        }
    }

    /**
     * 机器人选牌：优先打出非万能的可打牌（保持控色），全部不可打时用万能牌。
     * 同条件取手牌顺序第一张（手牌已按颜色/点数排序）。
     */
    private UnoCard findBotPlay(List<UnoCard> hand) {
        UnoCard wild = null;
        for (UnoCard c : hand) {
            if (c.value().isWild()) {
                if (wild == null) {
                    wild = c;
                }
                continue;
            }
            if (canPlay(c, topCard(), chosenColor)) {
                return c;
            }
        }
        return wild;
    }

    /** 机器人选色：手中数量最多的颜色（统计非万能牌）。 */
    private byte botColorOrdinal(int seat) {
        int[] counts = new int[4];
        for (UnoCard c : players.get(seat).hand()) {
            if (c.color().isColored()) {
                counts[c.color().ordinal()]++;
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return (byte) best;
    }

    /** 安排自动行动（延迟 {@value #AUTO_ACT_DELAY_TICKS} tick 即 1 秒）。 */
    private void scheduleAutoAct(int seat) {
        pendingAutoAct = true;
        pendingAutoActSeat = seat;
        autoActDueGameTime = (level != null ? level.getGameTime() : 0) + AUTO_ACT_DELAY_TICKS;
    }

    /** 内部喊 UNO：置标记并广播（真人与机器人共用）。 */
    private void declareUnoInternal(int seat) {
        if (!unoCatchable[seat] || players.get(seat).declaredUno()) {
            return;
        }
        players.get(seat).setDeclaredUno(true);
        room.broadcast(new UnoDeclaredS2C((byte) seat));
        addHistory(players.get(seat).name(), "wifi_card_games.uno.history.declared");
    }

    /** 应用功能牌效果并推进回合。 */
    private void applyEffect(UnoCard card, int seat) {
        switch (card.value()) {
            case SKIP -> turn(nextSeat(nextSeat(seat)));
            case REVERSE -> {
                if (players.size() == 2) {
                    // 2 人局反转视为跳过下家（自己继续）
                    turn(nextSeat(nextSeat(seat)));
                } else {
                    direction = -direction;
                    turn(nextSeat(seat));
                }
            }
            case DRAW2 -> {
                int victim = nextSeat(seat);
                drawTo(victim, 2);
                room.broadcast(new DrawPenaltyS2C((byte) victim, (byte) 2, remainingCounts()));
                turn(nextSeat(victim));
            }
            case WILD4 -> {
                int victim = nextSeat(seat);
                drawTo(victim, 4);
                room.broadcast(new DrawPenaltyS2C((byte) victim, (byte) 4, remainingCounts()));
                turn(nextSeat(victim));
            }
            default -> turn(nextSeat(seat));
        }
    }

    /** 罚牌：目标抽 n 张（牌面私发），离开 1 张状态时关闭其抓捕窗口。 */
    private void drawTo(int seat, int n) {
        List<Integer> drawn = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UnoCard card = drawFromDeck();
            if (card == null) {
                break; // 极端兜底：无牌可抽（全部牌都在玩家手中），少罚几张
            }
            players.get(seat).hand().add(card);
            drawn.add(card.id());
        }
        if (players.get(seat).hand().size() != 1) {
            unoCatchable[seat] = false;
        }
        if (!drawn.isEmpty()) {
            int[] ids = new int[drawn.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = drawn.get(i);
            }
            room.sendToSeat(seat, new DrawResultS2C(ids, false));
        }
        addHistory(players.get(seat).name(), "wifi_card_games.uno.history.penalty|" + n);
        // 旁观者：罚牌（+2/+4/抓 UNO/自动抓）后同步各家手牌（透视视角实时更新）
        sendHandsToSpectators();
    }

    /** 结算：先出完手牌者获胜（单局制）。 */
    private void settle(int seat) {
        phase = UnoGamePhase.SETTLED;
        winnerSeat = seat;
        winnerName = players.get(seat).name();
        room.broadcast(new GameResultS2C((byte) seat, winnerName));
        // 旁观者：结算时同步各家剩余手牌（透视视角看残局）
        sendHandsToSpectators();
        room.settledAtMillis = System.currentTimeMillis();
    }

    /** 推进回合到指定座位：重置回合状态、自动抓取未喊 UNO 者、广播轮到谁。 */
    private void turn(int seat) {
        if (phase != UnoGamePhase.PLAYING) {
            return;
        }
        currentSeat = seat;
        drawnPlayable = false;
        // 自动抓取（数字版标准行为）：窗口期内（打出倒数第二张后到本次轮到自己前）
        // 未点"喊 UNO"、也未被其他玩家抓住 → 轮到自己时系统自动罚 2 张并提示。
        // 宽限：须在窗口开启后至少经过一次他人行动（2 人局打出跳过/反转/+2/+4 后
        // 回合立即回轮自己，玩家仍有机会先喊 UNO 再行动，不会被秒罚）。
        // 机器人/托管在打出倒数第二张时已自动喊牌，不会走到这里。
        UnoPlayer p = players.get(seat);
        if (unoCatchable[seat] && !p.declaredUno()
                && actionCount > unoWindowOpenedAction[seat]) {
            unoCatchable[seat] = false;
            drawTo(seat, 2);
            room.broadcast(new NoticeS2C(Component.translatable("wifi_card_games.uno.history.auto_caught_name", p.name())));
            // catcherSeat=-1 表示系统自动抓取（客户端据此显示"被自动罚 2 张"）
            room.broadcast(new UnoCatchS2C((byte) -1, (byte) seat, remainingCounts()));
            addHistory(p.name(), "wifi_card_games.uno.history.auto_caught");
        }
        turnEndGameTime = nowGameTime() + TURN_SECONDS * 20L;
        tickCounter = 0;
        autoActDelayCounter = 0;
        pendingAutoAct = false; // 回合推进时清除待行动状态（手动操作/上轮延迟已处理）
        pendingAutoActSeat = -1;
        room.broadcast(new TurnS2C((byte) seat, turnEndGameTime));
        if (p.trusted() || room.isBot(seat)) {
            scheduleAutoAct(seat);
        }
    }

    private long nowGameTime() {
        return level != null ? level.getGameTime() : 0;
    }

    /** 弃牌堆顶牌（未开局为 null）。 */
    private UnoCard topCard() {
        return discard.isEmpty() ? null : discard.get(discard.size() - 1);
    }

    /** 从抽牌堆抽一张；堆空时把弃牌堆（保留顶牌）洗回。
     *  极端情况（全部 108 张都在玩家手中，连顶牌都所剩无几）返回 null，调用方兜底跳过。 */
    private UnoCard drawFromDeck() {
        if (deck.isEmpty()) {
            if (discard.size() > 1) {
                UnoCard top = discard.remove(discard.size() - 1);
                deck.addAll(discard);
                discard.clear();
                discard.add(top);
                Collections.shuffle(deck, random);
            } else if (discard.size() == 1) {
                // 仅剩顶牌：抽出它（弃牌堆变空，下一手打出的牌成为新顶牌）
                return discard.remove(0);
            } else {
                return null; // 无牌可抽
            }
        }
        return deck.remove(deck.size() - 1);
    }

    private UnoPlayer playerOf(ServerPlayer player) {
        if (player == null) {
            return players.get(currentSeat); // 托管/断线自动行动
        }
        for (UnoPlayer p : players) {
            if (p.uuid().equals(player.getUUID())) {
                return p;
            }
        }
        return null;
    }

    private void reject(UnoPlayer p, Component message) {
        room.sendToSeat(p.seat(), new NoticeS2C(message));
    }

    /** 记录一条事件历史（最新在前，超出上限裁掉最旧的）。 */
    private void addHistory(String name, String text) {
        historyNames.add(0, name);
        historyTexts.add(0, text);
        if (historyNames.size() > HISTORY_LIMIT) {
            historyNames.remove(historyNames.size() - 1);
            historyTexts.remove(historyTexts.size() - 1);
        }
    }

    /** 向指定座位下发本局事件历史（HistoryC2S 请求响应）。 */
    public void sendHistory(int seat) {
        room.sendToSeat(seat, new HistoryS2C(
                historyNames.toArray(new String[0]), historyTexts.toArray(new String[0])));
    }

    /** 向旁观者下发本局事件历史。 */
    public void sendHistoryToSpectator(ServerPlayer spectator) {
        room.sendToSpectator(spectator, new HistoryS2C(
                historyNames.toArray(new String[0]), historyTexts.toArray(new String[0])));
    }

    private byte[] remainingCounts() {
        byte[] counts = new byte[players.size()];
        for (int i = 0; i < players.size(); i++) {
            counts[i] = (byte) players.get(i).hand().size();
        }
        return counts;
    }

    private static int[] ids(List<UnoCard> cards) {
        int[] ids = new int[cards.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = cards.get(i).id();
        }
        return ids;
    }

    // ---------------- 规则静态工具（JUnit 测试用） ----------------

    /** 座位推进（按方向循环）。 */
    public static int nextSeat(int seat, int direction, int size) {
        return (seat + direction + size) % size;
    }

    /**
     * 判定一张牌能否打出：万能牌任意可打；与当前颜色相同或与顶牌点数相同可打。
     * chosenColor 为当前有效颜色（普通顶牌即其颜色，万能牌为所选的色）。
     */
    public static boolean canPlay(UnoCard card, UnoCard top, UnoColor chosenColor) {
        if (card.value().isWild()) {
            return true;
        }
        if (card.color() == chosenColor) {
            return true;
        }
        return top != null && card.value() == top.value();
    }

    private int nextSeat(int seat) {
        return nextSeat(seat, direction, players.size());
    }
}
