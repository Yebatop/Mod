package dev.yebatop.holyhelper.liteapi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Транспорт LiteAPI: запрос-ответ поверх Plugin Messaging Channel.
 * <p>
 * Формат по документации сервера: запрос {@code {id, method, payload}},
 * ответ {@code {id, ok, payload}} либо {@code {id, ok:false, error, message}}.
 * Отдельно приходят push-события {@code {event, payload}} — у них нет {@code id},
 * и они не являются ответом ни на что.
 * <p>
 * Это единственный исходящий игровой трафик мода. Больше он не отправляет сервером
 * ничего и никогда.
 */
public final class LiteApiChannel {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/liteapi");

    /** Документация сервера: не чаще одного запроса в 10 секунд на игрока. */
    private static final long RATE_LIMIT_MS = 10_000L;
    private static final long TIMEOUT_MS = 5_000L;

    private final Gson gson = new Gson();
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSentByMethod = new ConcurrentHashMap<>();

    /** Ответил ли сервер хоть раз за сессию — это и есть проверка «живёт ли LiteAPI на Прайме». */
    private volatile boolean everAnswered = false;
    private volatile String lastError = null;

    public void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(
                LiteApiPayload.FEATURE_CONTROL,
                (payload, context) -> context.client().execute(() -> handle(payload.json())));
    }

    private void handle(String raw) {
        JsonObject root;
        try {
            root = JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warn("Пришёл неразбираемый ответ по каналу LiteAPI: {}", abbreviate(raw));
            return;
        }

        everAnswered = true;

        if (root.has("event")) {
            LOG.info("Push-событие LiteAPI: {}", root.get("event").getAsString());
            return;
        }

        if (!root.has("id")) {
            LOG.warn("Ответ LiteAPI без поля id, пропускаю: {}", abbreviate(raw));
            return;
        }

        CompletableFuture<JsonObject> future = pending.remove(root.get("id").getAsString());
        if (future == null) {
            // Ответ на запрос, который уже истёк по таймауту. Это нормально.
            return;
        }
        future.complete(root);
    }

    /**
     * Отправляет запрос и ждёт ответа. Никогда не бросает исключение наружу:
     * при любой беде вернётся уже завершённый неудачей future.
     */
    public CompletableFuture<JsonObject> request(String method, JsonObject payload) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            return failed("нет подключения к серверу");
        }

        long now = System.currentTimeMillis();
        Long last = lastSentByMethod.get(method);
        if (last != null && now - last < RATE_LIMIT_MS) {
            long waitMs = RATE_LIMIT_MS - (now - last);
            return failed("лимит запросов: до следующего " + (waitMs / 1000 + 1) + " с");
        }
        lastSentByMethod.put(method, now);

        String id = UUID.randomUUID().toString();
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("payload", payload);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);

        try {
            ClientPlayNetworking.send(new LiteApiPayload(gson.toJson(request)));
        } catch (RuntimeException e) {
            pending.remove(id);
            lastError = e.getMessage();
            return failed("не удалось отправить пакет: " + e.getMessage());
        }

        return future
                .orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> {
                    pending.remove(id);
                    if (error instanceof TimeoutException) {
                        lastError = "сервер не ответил за " + (TIMEOUT_MS / 1000) + " с";
                    }
                });
    }

    private static CompletableFuture<JsonObject> failed(String reason) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    /** Объявил ли сервер этот канал. Если нет — почти наверняка LiteAPI здесь не поднят. */
    public boolean serverDeclaresChannel() {
        return ClientPlayNetworking.canSend(LiteApiPayload.FEATURE_CONTROL);
    }

    public boolean everAnswered() {
        return everAnswered;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * Сбрасывает состояние соединения.
     * <p>
     * Карту лимита при этом намеренно не трогаем. Лимит документирован как «1 запрос
     * в 10 секунд на игрока» — он про игрока, а не про соединение. У сервера лобби
     * и Прайм разные, вход срабатывает дважды подряд, и чистка карты давала два
     * запроса за четыре секунды. Ограничение обязано переживать переподключение.
     */
    public void reset() {
        pending.clear();
        everAnswered = false;
        lastError = null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
