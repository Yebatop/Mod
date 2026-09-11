package dev.yebatop.holyhelper.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Клиент публичного API HolyWorld. Только GET, только чтение, без авторизации.
 * <p>
 * Явных лимитов у API нет, но есть анти-DDoS, поэтому здесь выдержка: при неудаче
 * пауза удваивается до потолка, а при успехе возвращается к обычной. Мод, который
 * долбится в упавший сервис раз в тридцать секунд, — это ровно то, из-за чего
 * такие API закрывают.
 */
public final class HolyApiClient {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/api");

    private static final String BASE = "https://api.holyworld.me";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Обычная пауза между опросами.
     * <p>
     * Первая проверка на живом сервере показала, что рынок Прайма медленный:
     * в ответе сто сделок, а самая свежая — сорокапятиминутной давности. Опрос раз
     * в минуту бил бы по API примерно в шестьдесят раз чаще, чем там вообще
     * что-то происходит. Пять минут ничего не теряют и никого не беспокоят.
     */
    public static final Duration NORMAL_INTERVAL = Duration.ofMinutes(5);

    /** Дальше выдержку не растим: сутки молчания и так означают, что чинить нечего. */
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Duration interval = NORMAL_INTERVAL;
    private volatile String lastError;

    /** Через сколько можно спрашивать снова. Растёт при неудачах. */
    public Duration interval() {
        return interval;
    }

    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /**
     * Запрашивает путь. Никогда не бросает наружу: при любой беде возвращается
     * пустая строка, а причина уходит в лог и в {@link #lastError()}.
     */
    public CompletableFuture<String> get(String path) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Accept", "application/json")
                    // Владельцу API полезно видеть, кто ходит: анонимный поток
                    // запросов выглядит как атака, подписанный — как мод.
                    .header("User-Agent", "HolyHelper (Minecraft mod)")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(fail("плохой адрес: " + e.getMessage()));
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        return fail(error.getClass().getSimpleName() + ": " + error.getMessage());
                    }
                    if (response.statusCode() / 100 != 2) {
                        return fail("HTTP " + response.statusCode());
                    }
                    lastError = null;
                    interval = NORMAL_INTERVAL;
                    return response.body();
                });
    }

    private String fail(String reason) {
        lastError = reason;
        Duration doubled = interval.multipliedBy(2);
        interval = doubled.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : doubled;
        LOG.info("Запрос к API не удался ({}), следующая попытка через {} с",
                reason, interval.toSeconds());
        return "";
    }
}
