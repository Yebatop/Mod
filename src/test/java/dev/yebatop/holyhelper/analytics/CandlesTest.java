package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.rest.CoinRateTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandlesTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();

    private static CoinRateTracker.Trade at(long millis, double rate) {
        return new CoinRateTracker.Trade(rate, millis);
    }

    @Test
    @DisplayName("Сделки часа складываются в одну свечу: открытие, крайности, закрытие")
    void buildsOneCandle() {
        List<Candles.Candle> candles = Candles.of(List.of(
                at(HOUR + 100, 11_000),
                at(HOUR + 200, 11_900),
                at(HOUR + 300, 10_500),
                at(HOUR + 400, 11_300)), Duration.ofHours(1), 10);

        assertEquals(1, candles.size());
        Candles.Candle candle = candles.get(0);
        assertEquals(11_000, candle.open());
        assertEquals(11_900, candle.high());
        assertEquals(10_500, candle.low());
        assertEquals(11_300, candle.close());
        assertEquals(4, candle.trades());
        assertTrue(candle.rising(), "закрылась выше открытия");
    }

    @Test
    @DisplayName("Порядок сделок в списке не важен — он приходит от API как попало")
    void orderDoesNotMatter() {
        List<Candles.Candle> shuffled = Candles.of(List.of(
                at(HOUR + 300, 10_500),
                at(HOUR + 100, 11_000),
                at(HOUR + 400, 11_300),
                at(HOUR + 200, 11_900)), Duration.ofHours(1), 10);

        assertEquals(11_000, shuffled.get(0).open(), "открытие — самая ранняя сделка");
        assertEquals(11_300, shuffled.get(0).close(), "закрытие — самая поздняя");
    }

    @Test
    @DisplayName("Часы без сделок пропускаются, а не рисуются плоскими")
    void skipsEmptyHours() {
        // На Бирже торгуют неравномерно. Ночная тишина, показанная ровным курсом, —
        // это выдуманная цена: сделок не было, а не курс стоял.
        List<Candles.Candle> candles = Candles.of(List.of(
                at(HOUR + 100, 11_000),
                at(HOUR * 5 + 100, 12_000)), Duration.ofHours(1), 10);

        assertEquals(2, candles.size(), "две свечи, а не шесть");
        assertEquals(HOUR, candles.get(0).openMillis());
        assertEquals(HOUR * 5, candles.get(1).openMillis());
    }

    @Test
    @DisplayName("Границы свечей не ездят при новом опросе API")
    void bucketsAreStable() {
        // Промежутки нарезаны от начала эпохи, а не от первой сделки. Иначе при
        // каждом опросе одна и та же сделка попадала бы то в одну свечу, то в соседнюю.
        List<Candles.Candle> first = Candles.of(List.of(at(HOUR * 3 + 500, 11_000)),
                Duration.ofHours(1), 10);
        List<Candles.Candle> withOlder = Candles.of(List.of(
                at(HOUR + 10, 9_000),
                at(HOUR * 3 + 500, 11_000)), Duration.ofHours(1), 10);

        assertEquals(first.get(0).openMillis(), withOlder.get(1).openMillis(),
                "свеча той же сделки обязана остаться на месте");
    }

    @Test
    @DisplayName("Оставляем только свежие свечи, а не первые попавшиеся")
    void keepsNewest() {
        List<CoinRateTracker.Trade> trades = List.of(
                at(HOUR, 1), at(HOUR * 2, 2), at(HOUR * 3, 3), at(HOUR * 4, 4));

        List<Candles.Candle> candles = Candles.of(trades, Duration.ofHours(1), 2);

        assertEquals(2, candles.size());
        assertEquals(3, candles.get(0).close());
        assertEquals(4, candles.get(1).close());
    }

    @Test
    @DisplayName("Падение отличается от роста, а равенство — это не падение")
    void risingIsInclusive() {
        Candles.Candle down = Candles.of(List.of(at(HOUR, 100), at(HOUR + 1, 90)),
                Duration.ofHours(1), 5).get(0);
        Candles.Candle flat = Candles.of(List.of(at(HOUR, 100), at(HOUR + 1, 100)),
                Duration.ofHours(1), 5).get(0);

        assertFalse(down.rising());
        assertTrue(flat.rising(), "курс не упал — значит падением это звать нельзя");
    }

    @Test
    @DisplayName("Пустой вход — пустой выход, без исключений")
    void emptyStaysEmpty() {
        assertTrue(Candles.of(List.of(), Duration.ofHours(1), 10).isEmpty());
        assertTrue(Candles.of(null, Duration.ofHours(1), 10).isEmpty());
        assertTrue(Candles.of(List.of(at(HOUR, 1)), Duration.ZERO, 10).isEmpty());
        assertTrue(Candles.of(List.of(at(HOUR, 1)), Duration.ofHours(1), 0).isEmpty());
    }

    @Test
    @DisplayName("Верх и низ шкалы берутся по крайностям, а не по закрытиям")
    void scaleUsesExtremes() {
        List<Candles.Candle> candles = Candles.of(List.of(
                at(HOUR, 11_000), at(HOUR + 1, 13_000),
                at(HOUR * 2, 10_000), at(HOUR * 2 + 1, 10_200)), Duration.ofHours(1), 10);

        assertEquals(13_000, Candles.high(candles));
        assertEquals(10_000, Candles.low(candles));
    }
}
