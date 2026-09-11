package dev.yebatop.holyhelper.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки мода в {@code config/holyhelper/config.json}. */
public final class HolyHelperConfig {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Показывать панель в углу экрана. */
    public boolean hudEnabled = true;

    /** Показывать экран запуска при входе на сервер. */
    public boolean bootScreenEnabled = true;

    /** Показывать короткие сообщения о том, что мод прочитал окно. */
    public boolean toastsEnabled = true;

    /** Писать в чат отчёт о состоянии при входе на сервер. */
    public boolean announceOnJoin = true;

    /** Собирать цены из просмотренных окон Маркета и Скупца. */
    public boolean collectPrices = true;

    /**
     * Размер интерфейса мода: сколько пикселей монитора в одной его единице.
     * <p>
     * Ноль — сам: не мельче двух, дальше вслед за масштабом игры. Единица делает
     * мод вдвое мельче и вмещает вдвое больше данных, четвёрка — наоборот.
     * Настройка отдельна от игровой намеренно: мод рисован под свой размер и при
     * масштабе игры 1 в её сетке просто не читается.
     */
    public int uiScale = 0;

    private transient Path file;

    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("holyhelper");
    }

    public static HolyHelperConfig load() {
        Path dir = directory();
        Path file = dir.resolve("config.json");
        HolyHelperConfig config = new HolyHelperConfig();

        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                HolyHelperConfig loaded = GSON.fromJson(reader, HolyHelperConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Конфиг не прочитался ({}), беру значения по умолчанию", e.getMessage());
            }
        }

        config.file = file;
        config.save();
        return config;
    }

    public void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOG.warn("Конфиг не сохранился: {}", e.getMessage());
        }
    }
}
