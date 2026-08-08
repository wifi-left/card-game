package io.wifi.cards.board.model;

/**
 * 棋类游戏类型：棋盘规格与显示名。
 * <p>三种游戏均可自定义棋盘尺寸（创建房间时选择）：
 * 黑白棋 6/8/10/12/14、五子棋 11/13/15/19、围棋 9/13/19 路。</p>
 */
public enum BoardGameType {
    OTHELLO(8, new int[]{6, 8, 10, 12, 14}, "wifi_card_games.board.name_othello"),
    GOMOKU(15, new int[]{11, 13, 15, 19}, "wifi_card_games.board.name_gomoku"),
    GO(19, new int[]{9, 13, 19}, "wifi_card_games.board.name_go");

    /** 默认棋盘边长（创建房间未选择/防御回退时使用）。 */
    public final int defaultSize;
    /** 创建房间时可选的棋盘边长（从小到大）。 */
    public final int[] sizeOptions;
    /** 显示名翻译键（展示时经 Component.translatable 解析）。 */
    public final String displayName;

    BoardGameType(int defaultSize, int[] sizeOptions, String displayName) {
        this.defaultSize = defaultSize;
        this.sizeOptions = sizeOptions;
        this.displayName = displayName;
    }

    /** 校验该类型是否支持指定棋盘尺寸；不支持（含越界序号）回退默认尺寸。 */
    public static int safeSize(BoardGameType type, int size) {
        for (int s : type.sizeOptions) {
            if (s == size) {
                return s;
            }
        }
        return type.defaultSize;
    }
}
