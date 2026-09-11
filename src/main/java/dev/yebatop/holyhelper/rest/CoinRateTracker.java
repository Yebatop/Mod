package dev.yebatop.holyhelper.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * История курса монеток на Прайме.
 * <p>
 * Источник — {@code /v2/prime/coins/trades}: список совершённых сделок, у каждой
 * курс и время. Курс — это <b>монетки за один жетон</b>; сверено с заявкой на Бирже,
 * где то же число стояло в строке «Курс: 11,252 (за 1 жетон)».
 * <p>
 * Важно, чем это отличается от того, что видно в окне Биржи. Здесь <b>уже совершённые
 * сделки</b>, а в окне — открытые заявки, которые ещё никто не принял. Разброс заявок
 * бывает двукратным, поэтому «курс» из истории и «курс» из окна — разные величины,
 * и путать их нельзя.
 * <p>
 * Классов Minecraft здесь нет: на вход JSON строкой, поэтому разбор и статистика
 * проверяются тестами.
 */
public final class CoinRateTracker {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/rate");

    /** Сколько сделок держим. Хватает на сутки при плотном рынке. */
    private static final int LIMIT = 500;

    /** Одна сделка: курс и когда она прошла. */
    public record Trade(double rate, long millis) {

        public Instant at() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private final List<Trade> history = new ArrayList<>();

    /**
     * Добавляет сделки из ответа API. Возвращает, сколько записей оказались новыми.
     * <p>
     * Дубли неизбежны: соседние опросы возвращают пересекающиеся списки. Отсекаем
     * по паре «курс и время» — она уникальна для сделки.
     */
    public synchronized int merge(String json) {
        List<Trade> parsed = parse(json);
        int added = 0;

        for (Trade trade : parsed) {
            boolean known = history.stream().anyMatch(existing ->
                    existing.millis() == trade.millis() && existing.rate() == trade.rate());
            if (!known) {
                history.add(trade);
                added++;
            }
        }

        if (added > 0) {
            history.sort(Comparator.comparingLong(Trade::millis).reversed());
            if (history.size() > LIMIT) {
                history.subList(LIMIT, history.size()).clear();
            }
        }
        return added;
    }

    /** Последняя известная сделка. */
    public synchronized Optional<Trade> latest() {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(0));
    }

    /**
     * Медиана курса за окно. Именно медиана, а не среднее: одна сделка по дикому
     * курсу сдвинет среднее и не сдвинет медиану.
     */
    public synchronized Optional<Double> median(Duration window) {
        Instant since = Instant.now().minus(window);
        List<Double> rates = history.stream()
                .filter(trade -> trade.at().isAfter(since))
                .map(Trade::rate)
                .sorted()
                .toList();

        if (rates.isEmpty()) {
            return Optional.empty();
        }
        int middle = rates.size() / 2;
        return Optional.of(rates.size() % 2 == 1
                ? rates.get(middle)
                : (rates.get(middle - 1) + rates.get(middle)) / 2);
    }

    /**
     * Насколько последняя сделка отклонилась от медианы, в процентах.
     * Положительное — курс выше обычного, отрицательное — ниже.
     */
    public synchronized Optional<Double> deviationPercent(Duration window) {
        Optional<Trade> last = latest();
        Optional<Double> median = median(window);
        if (last.isEmpty() || median.isEmpty() || median.get() == 0) {
            return Optional.empty();
        }
        return Optional.of((last.get().rate() - median.get()) / median.get() * 100);
    }

    /**
     * Последние курсы по порядку времени, от старых к новым.
     * <p>
     * Нужны линии в панели: она рисуется слева направо, а история хранится
     * новыми вперёд. Разворачивать её на месте нельзя — порядок хранения
     * нужен всему остальному.
     */
    public synchronized List<Double> recentRates(int max) {
        int count = Math.min(Math.max(0, max), history.size());
        List<Double> rates = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            rates.add(history.get(i).rate());
        }
        return rates;
    }

    /**
     * Вся сохранённая история сделок.
     * <p>
     * Копией, а не самим списком: он меняется из потока опроса API, и отдавать
     * наружу изменяемую ссылку значит однажды получить исключение посреди
     * отрисовки графика.
     */
    public synchronized List<Trade> trades() {
        return List.copyOf(history);
    }

    public synchronized int size() {
        return history.size();
    }

    public synchronized void clear() {
        history.clear();
    }

    /**
     * Разбор ответа. Формат подтверждён живым дампом: массив объектов с полями
     * {@code rate} и {@code datetime}.
     * <p>
     * Битый ответ не должен ронять мод и не должен молча притворяться пустым рынком,
     * поэтому неразобранное пишется в лог, а не проглатывается.
     */
    static List<Trade> parse(String json) {
        List<Trade> trades = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return trades;
        }

        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                LOG.warn("Ответ /v2/prime/coins/trades — не массив, пропускаю");
                return trades;
            }
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (!object.has("rate") || !object.has("datetime")) {
                    continue;
                }
                double rate = object.get("rate").getAsDouble();
                long millis = object.get("datetime").getAsLong();
                if (rate > 0 && millis > 0) {
                    trades.add(new Trade(rate, millis));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Не удалось разобрать историю курса: {}", e.toString());
        }
        return trades;
    }
}
