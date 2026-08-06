package io.wifi.cards.common.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 大厅创建房间偏好（纯客户端）：记忆各游戏上次开房间的选项，
 * 下次打开大厅时默认选中上次的选项。
 * <p>持久化到 config 目录（FabricLoader.getConfigDir()）下的
 * <code>wifi-card-games-lobby-prefs.json</code>，按游戏 id 分组的 JSON 对象。</p>
 * <p>读取惰性加载（首次访问时）；写入在 UI 点击回调（客户端主线程）执行，
 * 文件极小，同步写盘即可；任何 IO/解析异常只记日志，绝不崩溃。</p>
 */
public final class LobbyPrefs {
    private static final Logger LOGGER = LoggerFactory.getLogger("wifi-card-games");

    private static final String FILE_NAME = "wifi-card-games-lobby-prefs.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 根对象（惰性加载，单线程客户端主线程访问）。 */
    private static JsonObject root;

    private LobbyPrefs() {
    }

    // ---------------- 读取 ----------------

    public static boolean getBool(String gameId, String key, boolean def) {
        JsonObject g = root().getAsJsonObject(gameId);
        if (g != null && g.has(key)) {
            try {
                return g.get(key).getAsBoolean();
            } catch (RuntimeException e) {
                // 类型不符（config 被手改）：回退默认值，不崩溃
                LOGGER.error("大厅偏好 {}/{} 类型不符，使用默认值", gameId, key);
            }
        }
        return def;
    }

    public static int getInt(String gameId, String key, int def) {
        JsonObject g = root().getAsJsonObject(gameId);
        if (g != null && g.has(key)) {
            try {
                return g.get(key).getAsInt();
            } catch (RuntimeException e) {
                // 类型不符（config 被手改）：回退默认值，不崩溃
                LOGGER.error("大厅偏好 {}/{} 类型不符，使用默认值", gameId, key);
            }
        }
        return def;
    }

    // ---------------- 写入（写后立即落盘） ----------------

    public static void set(String gameId, String key, boolean value) {
        game(gameId).addProperty(key, value);
        save();
    }

    public static void set(String gameId, String key, int value) {
        game(gameId).addProperty(key, value);
        save();
    }

    // ---------------- 内部 ----------------

    private static JsonObject root() {
        if (root == null) {
            root = load();
        }
        return root;
    }

    /** 获取某游戏的偏好子对象（不存在则创建并挂到根上）。 */
    private static JsonObject game(String gameId) {
        JsonObject r = root();
        JsonObject g = r.getAsJsonObject(gameId);
        if (g == null) {
            g = new JsonObject();
            r.add(gameId, g);
        }
        return g;
    }

    private static JsonObject load() {
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(reader);
                if (el.isJsonObject()) {
                    return el.getAsJsonObject();
                }
            } catch (IOException | RuntimeException e) {
                // 解析失败（文件损坏/版本不兼容）：忽略并重建
                LOGGER.error("读取大厅偏好失败，将使用默认值", e);
            }
        }
        return new JsonObject();
    }

    private static void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.error("保存大厅偏好失败", e);
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
