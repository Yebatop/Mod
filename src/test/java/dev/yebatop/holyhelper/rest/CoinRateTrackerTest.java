package dev.yebatop.holyhelper.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Числа взяты из живого дампа /v2/prime/coins/trades. */
class CoinRateTrackerTest {

    private static String trades(double... rates) {
        StringBuilder json = new StringBuilder("[");
        long now = Instant.now().toEpochMilli();
        for (int i = 0; i < rates.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"rate\":").append(rates[i])
                    .append(",\"datetime\":").append(now - i * 1000L).append('}');
        }
        return json.append(']').toString();
    }

    @Test
    @DisplayName("Разбор живого ответа")
    void parsesLiveResponse() {
        CoinRateTracker tracker = new CoinRateTracker();
        int added = tracker.merge(trades(11252, 11251, 10251, 10250, 9010));

        assertEquals(5, added);
        assertEquals(5, tracker.size());
        // Курс — монетки за один жетон, сверено с заявкой на Бирже.
        assertEquals(11252, tracker.latest().orElseThrow().rate(), 1e-9);
    }

    @Test
    @DisplayName("Повторный опрос не плодит дубли")
    void deduplicates() {
        CoinRateTracker tracker = new CoinRateTracker();
        String json = trades(11252, 11251, 10251);

        assertEquals(3, tracker.merge(json));
        // Соседние опросы возвращают пересекающиеся списки — это норма, а не ошибка.
        assertEquals(0, tracker.merge(json));
        assertEquals(3, tracker.size());
    }

    @Test
    @DisplayName("Медиана, а не среднее: одна дикая сделка её не двигает")
    void medianResistsOutliers() {
        CoinRateTracker tracker = new CoinRateTracker();
        tracker.merge(trades(11000, 11100, 11200, 11300, 900000));

        double median = tracker.median(Duration.ofHours(1)).orElseThrow();
        assertEquals(11200, median, 1e-9);

        // Среднее на тех же числах было бы около 189 000 — то есть бессмысленным.
        assertTrue(median < 12000, "медиана уехала за выбросом: " + median);
    }

    @Test
    @DisplayName("Отклонение последней сделки от медианы")
    void reportsDeviation() {
        CoinRateTracker tracker = new CoinRateTracker();
        // Последняя по времени — первая в списке.
        tracker.merge(trades(12000, 10000, 10000, 10000));

        assertEquals(10000, tracker.median(Duration.ofHours(1)).orElseThrow(), 1e-9);
        assertEquals(20.0, tracker.deviationPercent(Duration.ofHours(1)).orElseThrow(), 1e-9);
    }

    @Test
    @DisplayName("Пустой и битый ответ не роняют мод и не притворяются рынком")
    void survivesGarbage() {
        CoinRateTracker tracker = new CoinRateTracker();

        assertEquals(0, tracker.merge(""));
        assertEquals(0, tracker.merge("не json"));
        assertEquals(0, tracker.merge("{\"error\":\"nope\"}"));
        assertEquals(0, tracker.merge("[{\"rate\":0,\"datetime\":0}]"));
        assertEquals(0, tracker.merge("[{\"нет\":\"полей\"}]"));

        assertEquals(0, tracker.size());
        assertTrue(tracker.latest().isEmpty());
        assertTrue(tracker.median(Duration.ofHours(1)).isEmpty());
        assertTrue(tracker.deviationPercent(Duration.ofHours(1)).isEmpty());
    }

    @Test
    @DisplayName("Старые сделки в медиану не попадают")
    void windowsHistory() {
        CoinRateTracker tracker = new CoinRateTracker();
        long old = Instant.now().minus(Duration.ofHours(5)).toEpochMilli();
        long now = Instant.now().toEpochMilli();

        tracker.merge("[{\"rate\":11000,\"datetime\":" + now + "},"
                + "{\"rate\":50,\"datetime\":" + old + "}]");

        assertEquals(11000, tracker.median(Duration.ofHours(1)).orElseThrow(), 1e-9);
        assertEquals(2, tracker.size());
    }
}
