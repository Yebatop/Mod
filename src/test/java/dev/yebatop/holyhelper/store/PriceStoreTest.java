package dev.yebatop.holyhelper.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceStoreTest {

    @TempDir
    Path dir;

    private PriceStore store() {
        return new PriceStore(dir.resolve("prices.json"));
    }

    @Test
    @DisplayName("Из нескольких наблюдений берётся самое дешёвое")
    void keepsCheapest() {
        PriceStore store = store();
        Instant now = Instant.now();

        // Живые числа с витрины: End Stone по 7 за штуку, Kelp по 31.
        store.record("minecraft:end_stone", "End Stone", 9, now.minusSeconds(300));
        store.record("minecraft:end_stone", "End Stone", 7, now.minusSeconds(200));
        store.record("minecraft:end_stone", "End Stone", 12, now.minusSeconds(100));

        assertEquals(7, store.cheapest("minecraft:end_stone", Duration.ofHours(1)).orElseThrow());

        PriceStore.Known known = store.known("minecraft:end_stone", Duration.ofHours(1)).orElseThrow();
        assertEquals("End Stone", known.name());
        assertEquals(7, known.cheapestUnitPrice());
        assertEquals(3, known.samples());
    }

    @Test
    @DisplayName("Устаревшие наблюдения в ответ не попадают")
    void ignoresStale() {
        PriceStore store = store();
        store.record("minecraft:kelp", "Kelp", 5, Instant.now().minus(Duration.ofDays(3)));

        assertTrue(store.cheapest("minecraft:kelp", Duration.ofHours(1)).isEmpty());
        assertTrue(store.known("minecraft:kelp", Duration.ofHours(1)).isEmpty());

        // Но при большем окне — находятся: запись не удалена, просто не свежая.
        assertEquals(5, store.cheapest("minecraft:kelp", Duration.ofDays(7)).orElseThrow());
    }

    @Test
    @DisplayName("Одно и то же наблюдение не плодится")
    void deduplicates() {
        PriceStore store = store();
        Instant now = Instant.now();

        // Витрину мод читает дважды в секунду; один и тот же лот не должен
        // превратиться в сотню записей.
        for (int i = 0; i < 50; i++) {
            store.record("minecraft:kelp", "Kelp", 31, now);
        }
        assertEquals(1, store.observationCount());
    }

    @Test
    @DisplayName("Наблюдения переживают перезаход")
    void survivesRestart() throws IOException {
        PriceStore first = store();
        first.record("minecraft:gold_ingot", "Gold Ingot", 100, Instant.now());
        first.save();

        PriceStore second = store();
        second.load();

        assertEquals(1, second.itemCount());
        PriceStore.Known known = second.known("minecraft:gold_ingot", Duration.ofDays(1)).orElseThrow();
        assertEquals(100, known.cheapestUnitPrice());
        assertEquals("Gold Ingot", known.name());
    }

    @Test
    @DisplayName("Битая база не роняет мод")
    void survivesCorruptFile() throws IOException {
        Path file = dir.resolve("prices.json");
        Files.writeString(file, "{ это не json", StandardCharsets.UTF_8);

        PriceStore store = new PriceStore(file);
        store.load();

        assertEquals(0, store.itemCount());
        store.record("minecraft:stone", "Stone", 1, Instant.now());
        assertEquals(1, store.itemCount());
    }

    @Test
    @DisplayName("На предмет копится не больше тридцати наблюдений")
    void boundsHistory() {
        PriceStore store = store();
        Instant now = Instant.now();
        for (int i = 0; i < 100; i++) {
            store.record("minecraft:stone", "Stone", 100 + i, now.minusSeconds(i * 10L));
        }
        assertTrue(store.observationCount() <= 30,
                "наблюдений накопилось " + store.observationCount());
    }

    @Test
    @DisplayName("Бессмысленные записи отбрасываются")
    void rejectsNonsense() {
        PriceStore store = store();
        Instant now = Instant.now();

        store.record("", "Пусто", 10, now);
        store.record("minecraft:stone", "Stone", 0, now);
        store.record("minecraft:stone", "Stone", -5, now);

        assertEquals(0, store.itemCount());
    }
}
