package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.scan.BuyerParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Приводит наблюдаемый множитель к тому, что сервер мог применить на самом деле.
 * <p>
 * Мод видит только две цены — начальную и итоговую, обе целые, — и делит одну
 * на другую. У дорогих товаров это даёт точный ответ, у дешёвых врёт. Живой случай:
 * ламинария при начальной цене 48 и одном множителе показала итоговую 51, отношение
 * 1,0625 и «×1.06», тогда как у соседей в том же списке стояло честное ×1.05.
 * <p>
 * Заодно эти числа говорят, как сервер округляет: 48 × 1,05 = 50,4, а показано 51 —
 * значит вверх, а не до ближайшего. Поэтому вилка здесь шириной в целую монетку,
 * а не в половину: правило округления известно только по одному наблюдению,
 * и занижать допуск опаснее, чем завышать.
 * <p>
 * Настоящих значений немного: надбавки уровней сервер называет сам (5, 10 и 15 %),
 * а множители разных категорий перемножаются. Значит достижимые множители — это
 * произведения нескольких таких надбавок, и наблюдаемое отношение достаточно
 * прижать к ближайшему из них, если оно укладывается в погрешность округления.
 * <p>
 * Побочная польза: если отношение сошлось с произведением двух надбавок, значит
 * на товар действуют два множителя сразу. Это ровно то, чего нельзя узнать из окна
 * «Доступные предметы»: списки категорий пересекаются, и по содержимому не понять,
 * к какой из них предмет относится.
 */
public final class MultiplierMath {

    /** Больше трёх множителей на одном товаре не встречалось — дальше перебирать незачем. */
    private static final int MAX_STACKED = 3;

    /** Совпавшее объяснение: итоговый множитель и сколько надбавок в нём сошлось. */
    public record Applied(double factor, int count) {

        public boolean any() {
            return count > 0;
        }
    }

    private MultiplierMath() {
    }

    /**
     * Ищет объяснение наблюдаемому отношению.
     *
     * @param observed   итоговая цена, делённая на начальную
     * @param batchPrice начальная цена партии — от неё зависит погрешность округления
     * @param bonuses    надбавки уровней из справки сервера; без них прижимать не к чему
     * @return ближайшее достижимое значение, если оно укладывается в погрешность
     */
    public static Optional<Applied> explain(double observed, long batchPrice, BuyerParser.Bonuses bonuses) {
        if (bonuses == null || batchPrice <= 0 || observed <= 0) {
            return Optional.empty();
        }

        // Округление стоит целой монетки, отсюда и ширина вилки. Чем дешевле товар,
        // тем она шире: при начальной цене 10 монетка — это уже десять процентов.
        double tolerance = 1.0 / batchPrice + 1e-9;

        return candidates(bonuses).stream()
                // «Множителя нет» — случай точный, а не округлённый: без множителя
                // сервер не пересчитывает цену, и числа совпадают ровно. Иначе у дешёвых
                // товаров отсутствие множителя объясняло бы вообще любое отношение.
                .filter(candidate -> candidate.count() > 0 || Math.abs(observed - 1) < 1e-9)
                .filter(candidate -> Math.abs(candidate.factor() - observed) <= tolerance)
                // При равной точности выбираем простейшее объяснение: один множитель
                // вероятнее двух, случайно давших то же число.
                .min(Comparator.comparingInt(Applied::count)
                        .thenComparingDouble(candidate -> Math.abs(candidate.factor() - observed)));
    }

    /** Все достижимые множители: произведения от нуля до трёх надбавок. */
    private static List<Applied> candidates(BuyerParser.Bonuses bonuses) {
        double[] shares = {bonuses.share(1), bonuses.share(2), bonuses.share(3)};

        List<Applied> result = new ArrayList<>();
        result.add(new Applied(1.0, 0));
        build(shares, 1.0, 0, 0, result);
        return result;
    }

    /**
     * Перебирает сочетания надбавок. Индекс не уменьшается, чтобы одно и то же
     * сочетание не попало в список дважды в разном порядке.
     */
    private static void build(double[] shares, double factor, int used, int from, List<Applied> result) {
        if (used == MAX_STACKED) {
            return;
        }
        for (int i = from; i < shares.length; i++) {
            if (shares[i] <= 0) {
                continue;
            }
            double next = factor * (1 + shares[i]);
            result.add(new Applied(next, used + 1));
            build(shares, next, used + 1, i, result);
        }
    }
}
