package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.rest.CoinRateTracker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Сделки Биржи, сложенные в свечи.
 * <p>
 * API отдаёт совершённые сделки поштучно: курс и время. Для линии этого хватает,
 * но линия по сделкам врёт про плотность — десять сделок подряд по одной цене и
 * одна сделка за час выглядят одинаково. Свеча показывает и то, и другое: где
 * курс открылся и закрылся, куда ходил внутри часа и сколько раз за этот час
 * вообще торговали.
 * <p>
 * Это единственное место в моде, где данные приходят о <b>сделках</b>, а не о
 * запросах. Маркет показывает, сколько просят; Биржа через API — сколько дали.
 * Поэтому здесь можно говорить о цене прямо, без оговорок, которыми обвешано всё
 * остальное.
 * <p>
 * Классов Minecraft здесь нет: на вход список сделок, на выход список свечей,
 * и всё это проверяется тестами.
 */
public final class Candles {

    /**
     * Одна свеча.
     *
     * @param openMillis начало промежутка
     * @param open       курс первой сделки промежутка
     * @param high       наибольший курс внутри
     * @param low        наименьший
     * @param close      курс последней сделки
     * @param trades     сколько сделок в неё попало
     */
    public record Candle(long openMillis, double open, double high, double low, double close,
                         int trades) {

        /** Закрылась ли свеча выше, чем открылась. Равные считаем ростом: падения не было. */
        public boolean rising() {
            return close >= open;
        }
    }

    private Candles() {
    }

    /**
     * Складывает сделки в свечи заданной длины.
     * <p>
     * Промежутки нарезаются от начала эпохи, а не от первой сделки: иначе границы
     * свечей ездили бы при каждом новом опросе API, и одна и та же сделка попадала
     * бы то в одну свечу, то в соседнюю.
     * <p>
     * Пустые промежутки пропускаются, а не рисуются плоскими: на Бирже торгуют
     * неравномерно, и ночная тишина, показанная как ровный курс, — это выдуманная
     * цена. Отсутствие сделок значит, что курса в этот час просто не было.
     *
     * @param limit сколько свечей оставить, считая от свежих
     * @return от старых к новым — в том порядке, в каком график рисуется
     */
    public static List<Candle> of(List<CoinRateTracker.Trade> trades, Duration period, int limit) {
        if (trades == null || trades.isEmpty() || period == null || period.isZero()
                || period.isNegative() || limit <= 0) {
            return List.of();
        }
        long step = period.toMillis();

        List<CoinRateTracker.Trade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparingLong(CoinRateTracker.Trade::millis));

        List<Candle> candles = new ArrayList<>();
        long bucket = Long.MIN_VALUE;
        double open = 0;
        double high = 0;
        double low = 0;
        double close = 0;
        int count = 0;

        for (CoinRateTracker.Trade trade : sorted) {
            long at = Math.floorDiv(trade.millis(), step) * step;
            if (at != bucket) {
                if (count > 0) {
                    candles.add(new Candle(bucket, open, high, low, close, count));
                }
                bucket = at;
                open = trade.rate();
                high = trade.rate();
                low = trade.rate();
                count = 0;
            }
            high = Math.max(high, trade.rate());
            low = Math.min(low, trade.rate());
            close = trade.rate();
            count++;
        }
        if (count > 0) {
            candles.add(new Candle(bucket, open, high, low, close, count));
        }

        if (candles.size() > limit) {
            return List.copyOf(candles.subList(candles.size() - limit, candles.size()));
        }
        return List.copyOf(candles);
    }

    /** Наибольший курс среди свечей. Нужен графику, чтобы знать верх шкалы. */
    public static double high(List<Candle> candles) {
        return candles.stream().mapToDouble(Candle::high).max().orElse(0);
    }

    /** Наименьший курс. */
    public static double low(List<Candle> candles) {
        return candles.stream().mapToDouble(Candle::low).min().orElse(0);
    }
}
