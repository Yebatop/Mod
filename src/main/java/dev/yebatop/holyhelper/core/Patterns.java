package dev.yebatop.holyhelper.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Регулярки разбора игровых строк, вынесенные из кода в редактируемый JSON.
 * <p>
 * Сервер меняет формулировки при обновлениях. Чинить это должно быть правкой файла
 * {@code config/holyhelper/patterns.json}, а не пересборкой мода — иначе после каждого
 * апдейта Прайма мод лежит до следующего релиза.
 * <p>
 * Файл из ресурсов копируется в конфиг при первом запуске. Если пользовательский файл
 * не читается или в нём битая регулярка, берётся встроенный: сломанный конфиг не должен
 * ронять мод.
 */
public final class Patterns {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/patterns");
    private static final String RESOURCE = "/holyhelper/patterns.json";

    private final Map<String, Pattern> compiled = new HashMap<>();
    private final int version;

    private Patterns(JsonObject root) {
        this.version = root.has("version") ? root.get("version").getAsInt() : 0;
        JsonObject groups = root.getAsJsonObject("patterns");
        if (groups == null) {
            return;
        }
        for (String group : groups.keySet()) {
            JsonObject entries = groups.getAsJsonObject(group);
            for (String key : entries.keySet()) {
                String regex = entries.get(key).getAsString();
                try {
                    compiled.put(group + "." + key, Pattern.compile(regex));
                } catch (PatternSyntaxException e) {
                    LOG.warn("Битая регулярка {}.{}: {}", group, key, e.getDescription());
                }
            }
        }
    }

    public static Patterns load(Path configDir) {
        Path userFile = configDir.resolve("patterns.json");

        if (Files.isRegularFile(userFile)) {
            try (Reader reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                Patterns user = new Patterns(new Gson().fromJson(reader, JsonObject.class));
                if (!user.compiled.isEmpty()) {
                    LOG.info("Загружены пользовательские паттерны версии {}", user.version);
                    return user;
                }
                LOG.warn("В {} не нашлось ни одной рабочей регулярки — беру встроенные", userFile);
            } catch (IOException | RuntimeException e) {
                LOG.warn("Не удалось прочитать {} ({}) — беру встроенные", userFile, e.getMessage());
            }
        }

        Patterns builtin = builtin();
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(userFile)) {
                try (InputStream in = Patterns.class.getResourceAsStream(RESOURCE)) {
                    if (in != null) {
                        Files.copy(in, userFile);
                        LOG.info("Паттерны скопированы в {}", userFile);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Не удалось положить паттерны в конфиг: {}", e.getMessage());
        }
        return builtin;
    }

    public static Patterns builtin() {
        try (InputStream in = Patterns.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("в jar нет " + RESOURCE);
            }
            return new Patterns(new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class));
        } catch (IOException e) {
            throw new IllegalStateException("не читается " + RESOURCE, e);
        }
    }

    public int version() {
        return version;
    }

    public Optional<Matcher> match(String key, String line) {
        Pattern pattern = compiled.get(key);
        if (pattern == null || line == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Optional.of(matcher) : Optional.empty();
    }

    /** Достаёт число из первой группы совпадения, уже очищенное от разделителей. */
    public OptionalLong number(String key, String line) {
        return match(key, line)
                .map(matcher -> Numbers.parse(matcher.group(1)))
                .orElse(OptionalLong.empty());
    }
}
