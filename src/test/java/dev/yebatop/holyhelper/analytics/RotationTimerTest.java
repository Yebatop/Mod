package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.scan.BuyerParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTimerTest {

    private static BuyerParser.Offer offer(String name, boolean special, Duration rotation) {
        return new BuyerParser.Offer(name, "minecraft:stone", special, 100, 16, 100, 100, rotation);
    }

    @Test
    @DisplayName("Два независимых цикла считаются от момента съёмки")
    void tracksBothCycles() {
        RotationTimer timer = new RotationTimer();
        Instant seen = Instant.now().minusSeconds(60);

        // Реальные значения из игры: у яблока 12 мин 21 с, у снопа сена 6 ч 12 мин 47 с.
        timer.update(List.of(
                offer("Яблоко", false, Duration.ofMinutes(12).plusSeconds(21)),
                offer("Сноп сена", true, Duration.ofHours(6).plusMinutes(12).plusSeconds(47))), seen);

        // Минута уже прошла, поэтому остаток меньше исходного ровно на неё.
        long regular = timer.remaining(false).orElseThrow().toSeconds();
        long special = timer.remaining(true).orElseThrow().toSeconds();

        assertEquals(12 * 60 + 21 - 60, regular, 2);
        assertEquals(6 * 3600 + 12 * 60 + 47 - 60, special, 2);
    }

    @Test
    @DisplayName("Разные сроки у групп — часов двое")
    void separateClocksWhenDeadlinesDiffer() {
        RotationTimer timer = new RotationTimer();
        timer.update(List.of(
                offer("Яблоко", false, Duration.ofMinutes(12)),
                offer("Сноп сена", true, Duration.ofHours(6))), Instant.now());

        assertFalse(timer.singleClock());
    }

    @Test
    @DisplayName("Совпавшие сроки — это одни часы, а не двое")
    void singleClockWhenDeadlinesMatch() {
        RotationTimer timer = new RotationTimer();
        // Так пришло из игры: обе группы показали один и тот же остаток. Значит
        // наблюдение одно, и выдавать его за две проверенные величины нельзя.
        timer.update(List.of(
                offer("Уголь", false, Duration.ofHours(1).plusMinutes(32)),
                offer("Кувшинница", true, Duration.ofHours(1).plusMinutes(32))), Instant.now());

        assertTrue(timer.singleClock());
    }

    @Test
    @DisplayName("Секунды разницы — всё ещё одни часы, минуты — уже разные")
    void singleClockTolerance() {
        Instant seen = Instant.now();

        RotationTimer close = new RotationTimer();
        close.update(List.of(
                offer("Уголь", false, Duration.ofHours(1)),
                offer("Кувшинница", true, Duration.ofHours(1).plusSeconds(40))), seen);
        assertTrue(close.singleClock());

        RotationTimer apart = new RotationTimer();
        apart.update(List.of(
                offer("Уголь", false, Duration.ofHours(1)),
                offer("Кувшинница", true, Duration.ofHours(1).plusMinutes(9))), seen);
        assertFalse(apart.singleClock());
    }

    @Test
    @DisplayName("Одна известная группа — это ещё не одни часы")
    void singleClockNeedsBothSides() {
        RotationTimer timer = new RotationTimer();
        timer.update(List.of(offer("Уголь", false, Duration.ofHours(1))), Instant.now());

        assertFalse(timer.singleClock());
    }

    @Test
    @DisplayName("Пока окно не открывали, часы молчат")
    void silentUntilFirstSnapshot() {
        RotationTimer timer = new RotationTimer();
        assertFalse(timer.known());
        assertTrue(timer.remaining(false).isEmpty());
        assertTrue(timer.remaining(true).isEmpty());
    }

    @Test
    @DisplayName("Истёкший срок даёт ноль, а не отрицательное время")
    void clampsExpired() {
        RotationTimer timer = new RotationTimer();
        timer.update(List.of(offer("Яблоко", false, Duration.ofSeconds(10))),
                Instant.now().minusSeconds(600));

        assertEquals(Duration.ZERO, timer.remaining(false).orElseThrow());
    }

    @Test
    @DisplayName("Товары без таймера не сбивают уже известные часы")
    void keepsKnownDeadlines() {
        RotationTimer timer = new RotationTimer();
        Instant seen = Instant.now();
        timer.update(List.of(offer("Яблоко", false, Duration.ofMinutes(30))), seen);

        // Снимок окна, где таймера не оказалось вовсе, не должен стирать отсчёт.
        timer.update(List.of(offer("Глина", false, null)), seen.plusSeconds(5));

        assertTrue(timer.known());
        assertEquals(30 * 60, timer.remaining(false).orElseThrow().toSeconds(), 2);
    }

    @Test
    @DisplayName("Внутри группы берётся наибольший остаток")
    void prefersLongestWithinGroup() {
        RotationTimer timer = new RotationTimer();
        Instant seen = Instant.now();

        // Если одна строка разобралась криво и дала короткий остаток, часы не должны
        // уехать вперёд — обновление наступит не раньше самого дальнего срока.
        timer.update(List.of(
                offer("Яблоко", false, Duration.ofMinutes(30)),
                offer("Глина", false, Duration.ofMinutes(2))), seen);

        assertEquals(30 * 60, timer.remaining(false).orElseThrow().toSeconds(), 2);
    }

    @Test
    @DisplayName("Без длины цикла доли не бывает")
    void noFractionWithoutSpan() {
        RotationTimer timer = new RotationTimer();
        assertTrue(timer.remainingFraction(false).isEmpty());
        assertFalse(timer.periodExact(false));
    }

    @Test
    @DisplayName("Пока справку не открыли, длина цикла — наибольший увиденный остаток")
    void learnsSpanFromObservations() {
        RotationTimer timer = new RotationTimer();
        Instant seen = Instant.now();

        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(3))), seen);
        // Три часа из трёх: пока это всё, что мод видел, полоса полна.
        assertEquals(1.0, timer.remainingFraction(false).orElseThrow(), 0.01);

        // Заглянули позже и увидели больший остаток — знаменатель подрос.
        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(6))), seen);
        assertEquals(1.0, timer.remainingFraction(false).orElseThrow(), 0.01);

        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(3))), seen);
        assertEquals(0.5, timer.remainingFraction(false).orElseThrow(), 0.01);
        assertFalse(timer.periodExact(false));
    }

    @Test
    @DisplayName("Точная длина из справки сильнее наблюдений")
    void exactPeriodWins() {
        RotationTimer timer = new RotationTimer();
        Instant seen = Instant.now();

        timer.learnPeriod(false, Duration.ofHours(6));
        assertTrue(timer.periodExact(false));

        // Наблюдение больше объявленного периода не должно раздувать знаменатель:
        // справку пишет сервер, а наблюдение — это всего лишь «столько я видел».
        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(9))), seen);
        assertEquals(1.0, timer.remainingFraction(false).orElseThrow(), 0.01);

        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(3))), seen);
        assertEquals(0.5, timer.remainingFraction(false).orElseThrow(), 0.01);
    }

    @Test
    @DisplayName("Мусорный период из справки не принимается")
    void rejectsNonsensePeriod() {
        RotationTimer timer = new RotationTimer();
        timer.learnPeriod(false, null);
        timer.learnPeriod(false, Duration.ZERO);
        timer.learnPeriod(false, Duration.ofHours(-4));
        assertFalse(timer.periodExact(false));
        assertTrue(timer.remainingFraction(false).isEmpty());
    }

    @Test
    @DisplayName("Циклы независимы")
    void cyclesAreSeparate() {
        RotationTimer timer = new RotationTimer();
        timer.learnPeriod(true, Duration.ofHours(8));
        timer.update(List.of(offer("Изумруд", true, Duration.ofHours(2))), Instant.now());

        assertEquals(0.25, timer.remainingFraction(true).orElseThrow(), 0.01);
        assertTrue(timer.remainingFraction(false).isEmpty());
    }

    @Test
    @DisplayName("Сброс забывает и длину цикла")
    void resetForgetsSpan() {
        RotationTimer timer = new RotationTimer();
        timer.learnPeriod(false, Duration.ofHours(6));
        timer.update(List.of(offer("Яблоко", false, Duration.ofHours(3))), Instant.now());
        timer.reset();

        assertFalse(timer.known());
        assertFalse(timer.periodExact(false));
        assertTrue(timer.remainingFraction(false).isEmpty());
    }
}
