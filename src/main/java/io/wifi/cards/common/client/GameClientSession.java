package io.wifi.cards.common.client;

/**
 * 小游戏客户端会话接口（由各游戏 ClientState 单例实现并注册到 {@link GameMenuClient}）：
 * <p>解决"跨游戏界面互相覆盖、打开菜单后回不去"的问题——菜单/大厅关闭时，
 * 通过该接口找到玩家当前进行中的游戏会话并恢复其界面（对局数据仍在本地状态中）。</p>
 */
public interface GameClientSession {
    /** 游戏 id（与 GameRegistry 中的登记一致）。 */
    String gameId();

    /** 当前是否有进行中的会话（房间成员或旁观，含调试旁观）。 */
    boolean hasSession();

    /** 按当前会话状态重开对应界面（大厅/对局/结算）。 */
    void restoreScreen();
}
