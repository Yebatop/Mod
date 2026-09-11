package dev.yebatop.holyhelper.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquidityTest {

    private static Liquidity.Sample lot(long price, int hoursLeft) {
        return new Liquidity.Sample(price, Duration.ofHours(hoursLeft));
    }

    @Test
    @DisplayName("Дешёвые лоты только свежие, дорогие доживают — значит дешёвые разбирают")
    void cheapSellsFaster() {
        // Так выглядит работающий рынок: по низкой цене старых лотов не бывает,
        // их покупают раньше, чем они успевают состариться.
        List<Liquidity.Sample> samples = List.of(
                lot(100, 23), lot(110, 22), lot(120, 23),
                lot(400, 3), lot(450, 2), lot(500, 5));

        Liquidity.Split split = Liquidity.split(samples).orElseThrow();

        assertTrue(split.cheapMovesFaster(), "дешёвые обязаны оказаться моложе");
        assertTrue(split.notable());
        assertEquals(110, split.cheaper().medianPrice());
        assertEquals(450, split.dearer().medianPrice());
        assertEquals(3, split.cheaper().lots());
        assertEquals(3, split.dearer().lots());
    }

    @Test
    @DisplayName("Возраст не связан с ценой — вывода нет")
    void noVerdictWhenAgesMatch() {
        List<Liquidity.Sample> samples = List.of(
                lot(100, 12), lot(110, 11), lot(120, 13),
                lot(400, 12), lot(450, 13), lot(500, 11));

        Liquidity.Split split = Liquidity.split(samples).orElseThrow();

        assertFalse(split.notable(), "разница в час — это рябь, а не сигнал");
        assertFalse(split.cheapMovesFaster());
    }

    @Test
    @DisplayName("Дорогие моложе дешёвых — так бывает, и это не «дешёвые уходят»")
    void dearMayBeYounger() {
        // Кто-то только что выставил дорогие лоты, а дешёвые висят давно. Связи
        // с ликвидностью тут нет, и делать вид, что есть, нельзя.
        List<Liquidity.Sample> samples = List.of(
                lot(100, 2), lot(110, 3), lot(120, 1),
                lot(400, 23), lot(450, 22), lot(500, 23));

        Liquidity.Split split = Liquidity.split(samples).orElseThrow();

        assertTrue(split.notable(), "разница есть");
        assertFalse(split.cheapMovesFaster(), "но она в другую сторону");
        assertTrue(split.gap().isNegative());
    }

    @Test
    @DisplayName("Лотов мало — половин не делаем")
    void needsEnoughLots() {
        List<Liquidity.Sample> few = new ArrayList<>();
        for (int i = 0; i < Liquidity.ENOUGH - 1; i++) {
            few.add(lot(100 + i, 20));
        }
        assertTrue(Liquidity.split(few).isEmpty());
        assertTrue(Liquidity.split(List.of()).isEmpty());
        assertTrue(Liquidity.split(null).isEmpty());
    }

    @Test
    @DisplayName("Все лоты одной цены — делить нечего")
    void needsPriceSpread() {
        List<Liquidity.Sample> same = List.of(
                lot(100, 23), lot(100, 2), lot(100, 20),
                lot(100, 4), lot(100, 22), lot(100, 1));

        // Разброс возраста тут есть, но с ценой он никак не связан: делить
        // одинаковые цены пополам значит выдумать половины.
        assertTrue(Liquidity.split(same).isEmpty());
    }

    @Test
    @DisplayName("Нечётное число лотов делится без потери середины")
    void oddCountSplitsEvenly() {
        List<Liquidity.Sample> samples = List.of(
                lot(100, 23), lot(110, 22), lot(120, 21),
                lot(400, 3), lot(450, 2), lot(500, 1), lot(550, 2));

        Liquidity.Split split = Liquidity.split(samples).orElseThrow();

        // Семь лотов: по три в каждой половине, средний не попадает ни туда,
        // ни туда — иначе он влиял бы на обе стороны сразу.
        assertEquals(3, split.cheaper().lots());
        assertEquals(3, split.dearer().lots());
        assertTrue(split.cheapMovesFaster());
    }
}
