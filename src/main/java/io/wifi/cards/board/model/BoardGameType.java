package io.wifi.cards.board.model;

/**
 * 棋类游戏类型：棋盘规格与显示名。
 * <p>黑白棋 8×8、五子棋 15×15 固定；围棋在创建房间时可选 9 或 19 路。</p>
 */
public enum BoardGameType {
    OTHELLO(8, "黑白棋"),
    GOMOKU(15, "五子棋"),
    GO(19, "围棋");

    /** 默认棋盘边长（围棋为 19，创建时可选 9/19）。 */
    public final int defaultSize;
    public final String displayName;

    BoardGameType(int defaultSize, String displayName) {
        this.defaultSize = defaultSize;
        this.displayName = displayName;
    }

    /** 校验该类型是否支持指定棋盘尺寸；不支持（含越界序号）回退默认尺寸。 */
    public static int safeSize(BoardGameType type, int size) {
        if (type == GO) {
            return size == 9 ? 9 : 19;
        }
        return type.defaultSize;
    }
}
