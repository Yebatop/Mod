package dev.yebatop.holyhelper.liteapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Реестр функций мода и блок-лист сервера.
 * <p>
 * При заходе на сервер мод честно перечисляет всё, что умеет, и спрашивает, что из
 * этого запрещено. Попавшая в блок-лист функция обязана исчезнуть целиком: не просто
 * перестать работать, а пропасть из меню, настроек и подсказок — так требует
 * документация LiteAPI.
 * <p>
 * Если сервер не ответил, мод считает всё разрешённым и работает дальше. Отсутствие
 * ответа — не повод отключаться: канал документирован только для Лайта, и на Прайме
 * его может не быть вовсе.
 */
public final class FeatureGate {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/features");

    /** Стабильный идентификатор мода. Сервер хранит по нему персональные блокировки — менять нельзя. */
    public static final String CLIENT_ID = "holyhelper";

    /**
     * Всё, что мод умеет или будет уметь. Список объявляется целиком и заранее:
     * сервер должен видеть честную картину, а не то, что включено прямо сейчас.
     */
    public static final List<String> FEATURES = List.of(
            "market-prices",    // история цен Маркета из просмотренных страниц
            "buyer-planner",    // планировщик Скупца
            "price-tooltip",    // подсказка с ценой на предмете
            "exchange-rate",    // курс Биржи
            "trade-journal",    // журнал собственных сделок
            "player-notes",     // заметки на игроков
            "hud-overlay"       // оверлей с таймерами и балансом
    );

    private final LiteApiChannel channel;
    private final Set<String> blocked = new CopyOnWriteArraySet<>();

    private volatile Status status = Status.NOT_ASKED;

    public enum Status {
        NOT_ASKED("не спрашивали"),
        ANSWERED("сервер ответил"),
        NO_CHANNEL("канал не объявлен сервером"),
        NO_ANSWER("сервер не ответил");

        public final String human;

        Status(String human) {
            this.human = human;
        }
    }

    public FeatureGate(LiteApiChannel channel) {
        this.channel = channel;
    }

    /** Спрашивает блок-лист. Вызывается один раз при входе на сервер. */
    public void check() {
        blocked.clear();
        status = Status.NOT_ASKED;

        if (!channel.serverDeclaresChannel()) {
            // Пробуем всё равно: сервер может принимать канал, не объявляя его.
            LOG.info("Сервер не объявил канал {} — пробую запрос вслепую",
                    LiteApiPayload.FEATURE_CONTROL_CHANNEL);
        }

        JsonArray features = new JsonArray();
        FEATURES.forEach(features::add);

        JsonObject payload = new JsonObject();
        payload.addProperty("client", CLIENT_ID);
        payload.add("features", features);

        channel.request("checkFeatures", payload)
                .thenAccept(this::apply)
                .exceptionally(error -> {
                    status = channel.serverDeclaresChannel() ? Status.NO_ANSWER : Status.NO_CHANNEL;
                    LOG.info("checkFeatures без ответа ({}). Считаю все функции разрешёнными.",
                            error.getMessage());
                    return null;
                });
    }

    private void apply(JsonObject response) {
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
            String error = response.has("error") ? response.get("error").getAsString() : "неизвестно";
            LOG.warn("checkFeatures вернул ошибку {}. Считаю все функции разрешёнными.", error);
            status = Status.ANSWERED;
            return;
        }

        JsonObject payload = response.getAsJsonObject("payload");
        if (payload != null && payload.has("blocklist")) {
            payload.getAsJsonArray("blocklist")
                    .forEach(element -> blocked.add(element.getAsString()));
        }
        status = Status.ANSWERED;
        LOG.info("checkFeatures: заблокировано {}", blocked.isEmpty() ? "ничего" : blocked);
    }

    /** Разрешена ли функция. Неизвестное имя считается разрешённым. */
    public boolean isAllowed(String feature) {
        return !blocked.contains(feature);
    }

    public Set<String> blocked() {
        return Collections.unmodifiableSet(blocked);
    }

    public Status status() {
        return status;
    }

    public void reset() {
        blocked.clear();
        status = Status.NOT_ASKED;
    }
}
