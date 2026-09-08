package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.scan.BuyerParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplierMathTest {

    /** Надбавки, которые сервер называет в справке окна множителей. */
    private static final BuyerParser.Bonuses BONUSES = new BuyerParser.Bonuses(5, 10, 15);

    @Test
    @DisplayName("Округление дешёвого товара не выдаётся за второй множитель")
    void snapsRoundingNoise() {
        // Живой случай: ламинария показывала ×1.06, когда у всех соседей было ×1.05,
        // а множитель у игрока был ровно один. Начальная цена 48, итоговая 51.
        // Эти же числа выдают правило округления: 48 × 1,05 = 50,4, показано 51,
        // значит вверх — до ближайшего было бы 50.
        double observed = 51.0 / 48.0;
        MultiplierMath.Applied applied = MultiplierMath.explain(observed, 48, BONUSES).orElseThrow();

        assertEquals(1.05, applied.factor(), 1e-9);
        assertEquals(1, applied.count());
    }

    @Test
    @DisplayName("Точное отношение дорогих товаров узнаётся как есть")
    void recognisesExactFactor() {
        MultiplierMath.Applied applied = MultiplierMath.explain(1.05, 1000, BONUSES).orElseThrow();
        assertEquals(1.05, applied.factor(), 1e-9);
        assertEquals(1, applied.count());
    }

    @Test
    @DisplayName("Два множителя на одном товаре различаются")
    void detectsStackedMultipliers() {
        // 1,05 × 1,10 = 1,155 — то, что справка называет перемножением категорий.
        MultiplierMath.Applied applied = MultiplierMath.explain(1.155, 10000, BONUSES).orElseThrow();

        assertEquals(1.155, applied.factor(), 1e-9);
        assertEquals(2, applied.count());
    }

    @Test
    @DisplayName("Отсутствие множителя — тоже ответ")
    void recognisesNoMultiplier() {
        MultiplierMath.Applied applied = MultiplierMath.explain(1.0, 500, BONUSES).orElseThrow();
        assertEquals(1.0, applied.factor(), 1e-9);
        assertEquals(0, applied.count());
        assertTrue(!applied.any());
    }

    @Test
    @DisplayName("Необъяснимое отношение не подгоняется")
    void refusesToGuess() {
        // 1,4 не собирается ни из каких надбавок — значит мод чего-то не знает,
        // и честнее промолчать, чем прижать к ближайшему попавшемуся.
        assertTrue(MultiplierMath.explain(1.4, 10000, BONUSES).isEmpty());
    }

    @Test
    @DisplayName("Без надбавок из справки прижимать не к чему")
    void needsBonuses() {
        assertTrue(MultiplierMath.explain(1.05, 100, null).isEmpty());
    }

    @Test
    @DisplayName("У дешёвых товаров вилка шире, у дорогих уже")
    void toleranceFollowsPrice() {
        // При начальной цене 10 одна монетка — это целых 10 %, и «×1.10»
        // от «×1.05» уже не отличить. Побеждает ближайшее, но множитель всё равно один.
        MultiplierMath.Applied cheap = MultiplierMath.explain(11.0 / 10.0, 10, BONUSES).orElseThrow();
        assertEquals(1, cheap.count());

        // При цене 10 000 та же половина монетки — сотые доли процента,
        // и 1,10 уже нельзя спутать с 1,05.
        MultiplierMath.Applied dear = MultiplierMath.explain(1.10, 10000, BONUSES).orElseThrow();
        assertEquals(1.10, dear.factor(), 1e-9);
    }
}
