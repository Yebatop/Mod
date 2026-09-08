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
}
