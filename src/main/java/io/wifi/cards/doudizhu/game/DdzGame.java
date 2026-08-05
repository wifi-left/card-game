package io.wifi.cards.doudizhu.game;

import io.wifi.cards.doudizhu.card.DdzCard;
import io.wifi.cards.doudizhu.card.DdzDeck;
import io.wifi.cards.doudizhu.manager.DdzRoom;
import io.wifi.cards.doudizhu.model.DdzGameMode;
import io.wifi.cards.doudizhu.model.DdzGamePhase;
import io.wifi.cards.doudizhu.model.DdzPlayer;
import io.wifi.cards.doudizhu.network.DdzPackets.CallBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameResultS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.HistoryS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.GameStartS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.LandlordS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.NoticeS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PassBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.PlayBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.ReconnectS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RevealS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TrustStateS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.RobBroadcastS2C;
import io.wifi.cards.doudizhu.network.DdzPackets.TurnS2C;
import io.wifi.cards.doudizhu.rule.DdzAutoPlay;
import io.wifi.cards.doudizhu.rule.DdzCardTypeRecognizer;
import io.wifi.cards.doudizhu.rule.DdzPlayResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 斗地主对局状态机（服务端权威，纯内存）。
 * <p>流程：发牌 → 叫分（不叫/1/2/3，须更高）→ 有人叫 3 进入抢地主（循环抢，连续 2 人不抢终止）
 * 或无人叫 3 取最高分者 → 出牌（地主先出，两 Pass 后自由出牌）→ 结算。</p>
 * <p>托管：主动开启 / 超时（15 秒）/ 断线自动触发；调试假人可自动托管行动。
 * 倍数 = 底分阶段倍数 × 2^(炸弹/王炸/含花牌炸弹出现次数)。
 * 出牌校验按房间规则集（标准/民间）与花牌模式过滤禁用的牌型（如花牌模式的三带二）。
 * 玩家断线自动托管续玩，重连时由 DdzMemoryManager 替换连接引用并同步快照（ReconnectS2C）。</p>
 */
public class DdzGame {
    /** 每回合行动时限（秒）。 */
    public static final int TURN_SECONDS = 30;

    private final DdzRoom room;
    private final DdzPlayer[] players = new DdzPlayer[3];
    private DdzGamePhase phase = DdzGamePhase.WAITING;

    private final List<DdzCard> bottomCards = new ArrayList<>();
    private int callSeat;
    private int callCount;
    private int maxScore;
    private int maxScoreSeat = -1;
    private int landlordSeat = -1;
    private int baseScore = 1;
    private int multiplier = 1;
    private int consecutivePasses;
    private int currentSeat;
    private int lastPlaySeat = -1;
    private DdzPlayResult lastPlay;
    private int passCount;
    private int turnSeconds;
    private long turnEndGameTime;
    private int tickCounter;
    /** 是否已明牌（地主公开手牌，出第一手牌前可选）。 */
    private boolean revealed;
    /** 调试假人是否自动托管行动（默认开；关闭后可手动指挥假人）。 */
    private boolean botAuto = true;
    /** 结算结果缓存（重发/重开结算界面用）。 */
    private boolean resultLandlordWin;
    private int[] resultDeltas = new int[3];
    /** 本局出牌历史（最新在前，上限 HISTORY_LIMIT 条；含"不出"记录）。 */
    private static final int HISTORY_LIMIT = 60;
    private final List<Integer> historySeats = new ArrayList<>();
    private final List<String> historyNames = new ArrayList<>();
    private final List<String> historyTypes = new ArrayList<>();
    private final List<String> historyCards = new ArrayList<>();
    /** 服务端世界引用（用于游戏刻计时；全假人房间为 null）。 */
    private final ServerLevel level;
    /** 随机源（叫分起始座位、全员不叫随机定地主等）。 */
    private final Random random = new Random();
    /** 托管/机器人在出牌阶段自动行动的延迟（tick，2 秒）。 */
    private static final int AUTO_ACT_DELAY_TICKS = 40;
    /** 是否在等待延迟后的自动行动（仅出牌阶段；叫分/抢地主立即行动）。 */
    private boolean pendingAutoAct;
    private int pendingAutoActSeat = -1;
    private long autoActDueGameTime;
    private int autoActDelayCounter;

    public DdzGame(DdzRoom room) {
        this.room = room;
        ServerLevel foundLevel = null;
        for (int i = 0; i < 3; i++) {
            String name = room.seatName(i);
            if (name.isEmpty()) {
                name = "???"; // 防御：异常状态下的座位显示名兜底
            }
            UUID uuid = room.isBot(i)
                    ? UUID.nameUUIDFromBytes(("ddz-bot-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : room.members[i].getUUID();
            if (foundLevel == null && room.members[i] != null) {
                foundLevel = room.members[i].serverLevel();
            }
            players[i] = new DdzPlayer(uuid, name, i);
        }
        this.level = foundLevel;
    }

    public DdzGamePhase phase() {
        return phase;
    }

    /** 当前行动座位（调试命令用）。 */
    public int currentSeat() {
        return currentSeat;
    }

    /** 某座位的手牌（调试命令用）。 */
    public List<DdzCard> handOf(int seat) {
        return players[seat].hand();
    }

    /** 开局：洗牌发牌，随机起始叫分玩家。 */
    public void start() {
        phase = DdzGamePhase.DEALING;
        historySeats.clear();
        historyNames.clear();
        historyTypes.clear();
        historyCards.clear();
        DdzGameMode mode = room.flowerMode ? DdzGameMode.FLOWER : DdzGameMode.CLASSIC;
        List<DdzCard> deck = DdzDeck.shuffled(mode, random);
        int bottomCount = room.flowerMode ? 4 : 3;
        for (int i = 0; i < 3; i++) {
            DdzPlayer p = players[i];
            p.hand().clear();
            p.setLandlord(false);
            for (int j = 0; j < 17; j++) {
                p.hand().add(deck.get(i * 17 + j));
            }
            DdzCard.sortByRank(p.hand());
        }
        bottomCards.clear();
        for (int i = 51; i < deck.size(); i++) {
            bottomCards.add(deck.get(i));
        }
        landlordSeat = -1;
        baseScore = 1;
        multiplier = 1;
        consecutivePasses = 0;
        lastPlaySeat = -1;
        lastPlay = null;
        passCount = 0;
        callCount = 0;
        maxScore = 0;
        maxScoreSeat = -1;
        revealed = false;

        phase = DdzGamePhase.CALLING;
        callSeat = random.nextInt(3);
        for (int i = 0; i < 3; i++) {
            room.sendToSeat(i, new GameStartS2C((byte) i, ids(players[i].hand()), (byte) callSeat, (byte) bottomCards.size()));
        }
        turn(callSeat);
    }

    /** 叫分。score: 0=不叫, 1/2/3。 */
    public void onCall(ServerPlayer player, int score) {
        DdzPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != DdzGamePhase.CALLING) {
            reject(p, "现在不是叫分阶段");
            return;
        }
        if (p.seat() != callSeat) {
            reject(p, "还没轮到你叫分");
            return;
        }
        if (score < 0 || score > 3 || (score > 0 && score <= maxScore)) {
            reject(p, "叫分必须高于当前最高分");
            return;
        }
        room.broadcast(new CallBroadcastS2C(p.name(), (byte) score, (byte) Math.max(maxScore, score)));
        if (score == 3) {
            // 触发抢地主
            beginRobPhase(p.seat());
            return;
        }
        if (score > maxScore) {
            maxScore = score;
            maxScoreSeat = p.seat();
        }
        callCount++;
        if (callCount >= 3) {
            if (maxScoreSeat < 0) {
                if (allAuto()) {
                    // 全员托管/机器人且无人叫分：重发会陷入"不叫→重发"无限递归（栈溢出），
                    // 改为随机指定地主继续对局
                    int seat = random.nextInt(3);
                    room.broadcast(new NoticeS2C("全员托管且无人叫分，随机指定地主"));
                    becomeLandlord(seat, 1, 1);
                } else {
                    // 三人均不叫：本局作废，重新发牌
                    room.broadcast(new NoticeS2C("三人均未叫分，本局作废，重新发牌"));
                    start();
                }
                return;
            }
            becomeLandlord(maxScoreSeat, maxScore, 1);
            return;
        }
        callSeat = next(callSeat);
        turn(callSeat);
    }

    /** 所有座位是否都处于自动行动状态（托管或机器人），用于避免"全不叫重发"死循环。 */
    private boolean allAuto() {
        for (int i = 0; i < 3; i++) {
            if (!players[i].trusted() && !(botAuto && room.isBot(i))) {
                return false;
            }
        }
        return true;
    }

    /** 抢地主表态。 */
    public void onRob(ServerPlayer player, boolean rob) {
        DdzPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != DdzGamePhase.ROBBING) {
            reject(p, "现在不是抢地主阶段");
            return;
        }
        if (p.seat() != currentSeat) {
            reject(p, "还没轮到你");
            return;
        }
        if (rob) {
            landlordSeat = p.seat();
            multiplier *= 2;
            consecutivePasses = 0;
        } else {
            consecutivePasses++;
        }
        room.broadcast(new RobBroadcastS2C(p.name(), rob, multiplier, (byte) consecutivePasses));
        if (consecutivePasses >= 2) {
            endRobPhase();
            return;
        }
        currentSeat = next(currentSeat);
        turn(currentSeat);
    }

    /**
     * 出牌；cards 为 null 表示不出。
     *
     * @return 是否成功执行（失败时已向该玩家发送拒绝提示）
     */
    public boolean onPlay(ServerPlayer player, List<DdzCard> cards) {
        DdzPlayer p = playerOf(player);
        if (p == null) {
            return false;
        }
        if (phase != DdzGamePhase.PLAYING) {
            reject(p, "现在不是出牌阶段");
            return false;
        }
        if (p.seat() != currentSeat) {
            reject(p, "还没轮到你出牌");
            return false;
        }
        if (cards == null || cards.isEmpty()) {
            if (lastPlaySeat < 0) {
                reject(p, "地主必须先出牌");
                return false;
            }
            if (lastPlaySeat == p.seat()) {
                reject(p, "轮到你自由出牌，不能不出");
                return false;
            }
            passCount++;
            addHistory(p.seat(), p.name(), "不出", "");
            room.broadcast(new PassBroadcastS2C(p.name(), remainingCounts()));
            if (passCount >= 2) {
                // 两家都不出，上家获得自由出牌权
                passCount = 0;
                turn(lastPlaySeat);
            } else {
                turn(next(p.seat()));
            }
            return true;
        }
        // 出牌校验
        Set<Integer> played = new HashSet<>();
        for (DdzCard c : cards) {
            played.add(c.id());
        }
        if (played.size() != cards.size() || !p.hand().containsAll(cards)) {
            reject(p, "出牌与手牌不符");
            return false;
        }
        DdzPlayResult chosen = choosePlay(cards, lastPlay == null || lastPlaySeat == p.seat() ? null : lastPlay);
        if (chosen == null) {
            reject(p, "牌型不合法或无法压过上家");
            return false;
        }
        p.hand().removeAll(cards);
        if (chosen.type.isBombLike()) {
            multiplier *= 2; // 炸弹/含花牌炸弹/王炸当场翻倍
        }
        lastPlay = chosen;
        lastPlaySeat = p.seat();
        passCount = 0;
        addHistory(p.seat(), p.name(), chosen.type.displayName(), cardsText(cards));
        room.broadcast(new PlayBroadcastS2C((byte) p.seat(), p.name(), ids(cards),
                (byte) chosen.type.ordinal(), chosen.key, multiplier, remainingCounts()));
        if (p.hand().isEmpty()) {
            settle(p.seat());
            return true;
        }
        turn(next(p.seat()));
        return true;
    }

    /** 开启/关闭指定玩家托管；开启且正轮到该玩家时立即自动行动。 */
    public void setTrust(ServerPlayer player, boolean enabled) {
        DdzPlayer p = playerOf(player);
        if (p != null) {
            setTrustSeat(p.seat(), enabled);
        }
    }

    /** 开启/关闭指定座位托管（真人与假人均可）；开启且正轮到该座位时安排自动行动（出牌延迟 2 秒）。 */
    public void setTrustSeat(int seat, boolean enabled) {
        if (seat < 0 || seat >= 3) {
            return;
        }
        DdzPlayer p = players[seat];
        p.setTrusted(enabled);
        // 回传托管状态，客户端按钮（托管/取消托管）与服务端保持一致
        room.sendToSeat(seat, new TrustStateS2C(enabled));
        if (enabled && seat == currentSeat && isActivePhase()) {
            scheduleAutoAct(seat);
        }
    }

    /** 玩家断线：自动托管代打（对局继续），正轮到他时安排自动行动（出牌延迟 2 秒）。 */
    public void onPlayerDisconnect(int seat) {
        if (seat < 0 || seat >= 3) {
            return;
        }
        DdzPlayer p = players[seat];
        p.setTrusted(true);
        if (p.seat() == currentSeat) {
            scheduleAutoAct(p.seat());
        }
    }

    /** 玩家重连：恢复手动控制（对局状态由 ReconnectS2C 快照同步）。 */
    public void onPlayerReconnect(int seat) {
        if (seat < 0 || seat >= 3) {
            return;
        }
        players[seat].setTrusted(false);
    }

    /** 地主选择明牌：公开全部手牌给所有玩家（仅地主出第一手牌前有效）。 */
    public void onReveal(ServerPlayer player) {
        DdzPlayer p = playerOf(player);
        if (p == null) {
            return;
        }
        if (phase != DdzGamePhase.PLAYING) {
            reject(p, "现在不能明牌");
            return;
        }
        if (p.seat() != landlordSeat) {
            reject(p, "只有地主可以明牌");
            return;
        }
        if (revealed) {
            reject(p, "本局已经明牌");
            return;
        }
        if (lastPlaySeat >= 0) {
            reject(p, "已出过牌，不能明牌");
            return;
        }
        revealed = true;
        room.broadcast(new RevealS2C((byte) landlordSeat, ids(players[landlordSeat].hand())));
    }

    /** 发送当前对局完整快照给指定座位（断线重连用，含该玩家自己的手牌）。 */
    public void syncTo(int seat) {
        DdzPlayResult lp = lastPlay;
        room.sendToSeat(seat, new ReconnectS2C(
                (byte) phase.ordinal(),
                ids(players[seat].hand()),
                (byte) maxScore,
                (byte) currentSeat,
                turnEndGameTime,
                multiplier,
                (byte) consecutivePasses,
                (byte) baseScore,
                (byte) landlordSeat,
                landlordSeat >= 0 ? players[landlordSeat].name() : "",
                ids(bottomCards),
                (byte) (lp == null ? -1 : lastPlaySeat),
                lp == null ? "" : players[lastPlaySeat].name(),
                lp == null ? new int[0] : ids(lp.cards),
                lp == null ? (byte) -1 : (byte) lp.type.ordinal(),
                lp == null ? 0 : lp.key,
                remainingCounts()));
        // 已明牌时补发明牌快照（当前地主手牌）
        if (revealed) {
            room.sendToSeat(seat, new RevealS2C((byte) landlordSeat, ids(players[landlordSeat].hand())));
        }
        // 补发托管状态，重连后按钮与服务端保持一致
        room.sendToSeat(seat, new TrustStateS2C(players[seat].trusted()));
        // 结算中：重发结算结果，客户端打开结算界面（而非结束的对局界面）
        resendResult(seat);
    }

    /** 服务端每 tick 调用：托管出牌延迟到点判断 + 超时判断（与客户端共用 level.getGameTime() 基准）。 */
    public void tick() {
        if (!isActivePhase()) {
            return;
        }
        // 托管/机器人在出牌阶段延迟 2 秒后自动行动（让玩家看清上一手）
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

    /** 安排自动行动：出牌阶段延迟 {@value #AUTO_ACT_DELAY_TICKS} tick（2 秒）再行动，叫分/抢地主立即。 */
    private void scheduleAutoAct(int seat) {
        if (phase == DdzGamePhase.PLAYING) {
            pendingAutoAct = true;
            pendingAutoActSeat = seat;
            autoActDueGameTime = (level != null ? level.getGameTime() : 0) + AUTO_ACT_DELAY_TICKS;
        } else {
            autoAct(seat);
        }
    }

    private boolean isActivePhase() {
        return phase == DdzGamePhase.CALLING || phase == DdzGamePhase.ROBBING || phase == DdzGamePhase.PLAYING;
    }

    /** 自动行动：叫分→不叫；抢地主→不抢；出牌→找第一手能压的，否则不出。 */
    private void autoAct(int seat) {
        DdzPlayer p = players[seat];
        switch (phase) {
            case CALLING -> onCall(null, 0);
            case ROBBING -> onRob(null, false);
            case PLAYING -> {
                DdzPlayResult target = (lastPlay == null || lastPlaySeat == seat) ? null : lastPlay;
                List<DdzCard> play = DdzAutoPlay.findPlay(p.hand(), target, room.flowerMode, room.ruleSet);
                onPlay(null, play);
            }
            default -> {
            }
        }
    }

    private void beginRobPhase(int seat) {
        phase = DdzGamePhase.ROBBING;
        landlordSeat = seat;
        multiplier = 1;
        consecutivePasses = 0;
        room.broadcast(new RobBroadcastS2C(players[seat].name(), true, 1, (byte) 0));
        currentSeat = next(seat);
        turn(currentSeat);
    }

    private void endRobPhase() {
        phase = DdzGamePhase.PLAYING;
        baseScore = 3; // 触发抢地主后底分固定 3
        becomeLandlordInner();
    }

    private void becomeLandlord(int seat, int score, int mult) {
        phase = DdzGamePhase.PLAYING;
        landlordSeat = seat;
        baseScore = score;
        multiplier = mult;
        becomeLandlordInner();
    }

    /** 底牌并入地主手牌，广播地主与底牌，地主先出。 */
    private void becomeLandlordInner() {
        DdzPlayer landlord = players[landlordSeat];
        landlord.setLandlord(true);
        landlord.hand().addAll(bottomCards);
        DdzCard.sortByRank(landlord.hand());
        room.broadcast(new LandlordS2C((byte) landlordSeat, landlord.name(), ids(bottomCards),
                (byte) baseScore, multiplier));
        lastPlay = null;
        lastPlaySeat = -1;
        passCount = 0;
        turn(landlordSeat);
    }

    /** 结算：地主胜 +2×底分×倍数，农民各 -底分×倍数；地主败则相反。 */
    private void settle(int winnerSeat) {
        phase = DdzGamePhase.SETTLED;
        boolean landlordWin = winnerSeat == landlordSeat;
        int unit = baseScore * multiplier;
        int[] deltas = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i == landlordSeat) {
                deltas[i] = landlordWin ? 2 * unit : -2 * unit;
            } else {
                deltas[i] = landlordWin ? -unit : unit;
            }
        }
        resultLandlordWin = landlordWin;
        resultDeltas = deltas;
        room.broadcast(new GameResultS2C((byte) landlordSeat, players[landlordSeat].name(), landlordWin,
                (byte) baseScore, multiplier, deltas));
        room.settledAtMillis = System.currentTimeMillis();
    }

    /** 结算中重新下发结算结果（客户端关闭结算弹窗后 /doudizhu 重开，或重连补发）。 */
    public void resendResult(int seat) {
        if (phase != DdzGamePhase.SETTLED || seat < 0 || seat >= 3) {
            return;
        }
        room.sendToSeat(seat, new GameResultS2C((byte) landlordSeat, players[landlordSeat].name(),
                resultLandlordWin, (byte) baseScore, multiplier, resultDeltas));
    }

    /** 记录一条出牌历史（最新在前，超出上限裁掉最旧的）。 */
    private void addHistory(int seat, String name, String type, String cards) {
        historySeats.add(0, seat);
        historyNames.add(0, name);
        historyTypes.add(0, type);
        historyCards.add(0, cards);
        if (historyNames.size() > HISTORY_LIMIT) {
            historySeats.remove(historySeats.size() - 1);
            historyNames.remove(historyNames.size() - 1);
            historyTypes.remove(historyTypes.size() - 1);
            historyCards.remove(historyCards.size() - 1);
        }
    }

    /** 牌列表 → 紧凑文本（如 "333 55"）。 */
    private static String cardsText(List<DdzCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (DdzCard c : cards) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c.display());
        }
        return sb.toString();
    }

    /** 向指定座位下发本局出牌历史（HistoryC2S 请求响应）。 */
    public void sendHistory(int seat) {
        room.sendToSeat(seat, new HistoryS2C(
                historySeats.stream().mapToInt(Integer::intValue).toArray(),
                historyNames.toArray(new String[0]),
                historyTypes.toArray(new String[0]),
                historyCards.toArray(new String[0])));
    }

    private void turn(int seat) {
        currentSeat = seat;
        turnEndGameTime = (level != null ? level.getGameTime() : 0) + TURN_SECONDS * 20L;
        tickCounter = 0;
        autoActDelayCounter = 0;
        pendingAutoAct = false; // 回合推进时清除待行动状态（手动操作/上轮延迟已处理）
        pendingAutoActSeat = -1;
        room.broadcast(new TurnS2C((byte) seat, turnEndGameTime));
        if (players[seat].trusted() || (botAuto && room.isBot(seat))) {
            scheduleAutoAct(seat);
        }
    }

    private static int next(int seat) {
        return (seat + 1) % 3;
    }

    /** 按房间规则过滤后，选出可压过 target 的最优解读（target 为 null 表示自由出牌）。 */
    private DdzPlayResult choosePlay(List<DdzCard> cards, DdzPlayResult target) {
        for (DdzPlayResult r : DdzCardTypeRecognizer.recognize(cards, room.flowerMode, room.ruleSet)) {
            if (r.canBeat(target)) {
                return r;
            }
        }
        return null;
    }

    private DdzPlayer playerOf(ServerPlayer player) {
        if (player == null) {
            return players[currentSeat]; // 托管/断线自动行动
        }
        for (DdzPlayer p : players) {
            if (p.uuid().equals(player.getUUID())) {
                return p;
            }
        }
        return null;
    }

    private void reject(DdzPlayer p, String message) {
        room.sendToSeat(p.seat(), new NoticeS2C(message));
    }

    private byte[] remainingCounts() {
        byte[] counts = new byte[3];
        for (int i = 0; i < 3; i++) {
            counts[i] = (byte) players[i].hand().size();
        }
        return counts;
    }

    private static int[] ids(List<DdzCard> cards) {
        int[] ids = new int[cards.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = cards.get(i).id();
        }
        return ids;
    }
}
