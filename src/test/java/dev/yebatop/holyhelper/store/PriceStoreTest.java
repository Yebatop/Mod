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
        store.record("minecraft:end_stone", "End Stone", 9, "alfa", now.minusSeconds(300));
        store.record("minecraft:end_stone", "End Stone", 7, "beta", now.minusSeconds(200));
        store.record("minecraft:end_stone", "End Stone", 12, "gamma", now.minusSeconds(100));

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
        store.record("minecraft:kelp", "Kelp", 5, "alfa", Instant.now().minus(Duration.ofDays(3)));

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
            store.record("minecraft:kelp", "Kelp", 31, "alfa", now);
        }
        assertEquals(1, store.observationCount());
    }

    @Test
    @DisplayName("Наблюдения переживают перезаход")
    void survivesRestart() throws IOException {
        PriceStore first = store();
        first.record("minecraft:gold_ingot", "Gold Ingot", 100, "alfa", Instant.now());
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
        store.record("minecraft:stone", "Stone", 1, "alfa", Instant.now());
        assertEquals(1, store.itemCount());
    }

    @Test
    @DisplayName("На предмет копится не больше тридцати наблюдений")
    void boundsHistory() {
        PriceStore store = store();
        Instant now = Instant.now();
        for (int i = 0; i < 100; i++) {
            store.record("minecraft:stone", "Stone", 100 + i, "seller" + i, now.minusSeconds(i * 10L));
        }
        assertTrue(store.observationCount() <= 30,
                "наблюдений накопилось " + store.observationCount());
    }

    @Test
    @DisplayName("Бессмысленные записи отбрасываются")
    void rejectsNonsense() {
        PriceStore store = store();
        Instant now = Instant.now();

        store.record("", "Пусто", 10, "alfa", now);
        store.record("minecraft:stone", "Stone", 0, "alfa", now);
        store.record("minecraft:stone", "Stone", -5, "alfa", now);

        assertEquals(0, store.itemCount());
    }

    @Test
    @DisplayName("Один и тот же лот при перелистывании не становится новым наблюдением")
    void countsLotsNotSightings() {
        PriceStore store = store();
        Instant now = Instant.now();

        // Игрок листает витрину туда-обратно, и один и тот же лот попадается моду
        // снова и снова — но с разницей в минуты, а не в секунду.
        for (int i = 0; i < 8; i++) {
            store.record("minecraft:coal", "Coal", 449, "h1dosq", now.plusSeconds(i * 60L));
        }

        PriceStore.Known known = store.known("minecraft:coal", Duration.ofHours(12)).orElseThrow();
        assertEquals(1, known.samples(), "это один лот, а не восемь");

        // Отметка времени при этом освежилась: лот всё ещё висит, и выпадать из
        // памяти раньше срока ему незачем.
        assertEquals(now.plusSeconds(7 * 60L).toEpochMilli(), known.seenAt().toEpochMilli());
    }

    @Test
    @DisplayName("Разные продавцы по одной цене — разные лоты")
    void differentSellersAreDifferentLots() {
        PriceStore store = store();
        Instant now = Instant.now();

        store.record("minecraft:coal", "Coal", 449, "h1dosq", now);
        store.record("minecraft:coal", "Coal", 449, "Okokoy", now);
        store.record("minecraft:coal", "Coal", 449, "MrCube", now);

        // Три человека просят одинаково — вот это уже похоже на цену рынка.
        assertEquals(3, store.known("minecraft:coal", Duration.ofHours(12)).orElseThrow().samples());
    }

    @Test
    @DisplayName("Тот же продавец, но другая цена — другой лот")
    void samePersonDifferentPrice() {
        PriceStore store = store();
        Instant now = Instant.now();

        store.record("minecraft:coal", "Coal", 449, "h1dosq", now);
        store.record("minecraft:coal", "Coal", 300, "h1dosq", now.plusSeconds(60));

        assertEquals(2, store.known("minecraft:coal", Duration.ofHours(12)).orElseThrow().samples());
        assertEquals(300, store.cheapest("minecraft:coal", Duration.ofHours(12)).orElseThrow());
    }

    @Test
    @DisplayName("Записи без продавца из старой базы читаются и не плодятся")
    void oldRecordsWithoutSeller() throws IOException {
        // База копилась неделями до того, как в наблюдении появился продавец.
        // Выбросить её из-за нового поля нельзя, а считать такие записи разными
        // лотами при каждом заходе — тем более.
        Path file = dir.resolve("prices.json");
        Files.writeString(file, """
                {"items":{"minecraft:coal":[{"unitPrice":449,"seenAtMillis":%d}]},\
                "names":{"minecraft:coal":"Coal"}}"""
                .formatted(Instant.now().toEpochMilli()), StandardCharsets.UTF_8);

        PriceStore store = new PriceStore(file);
        store.load();
        assertEquals(1, store.observationCount());

        store.record("minecraft:coal", "Coal", 449, "", Instant.now().plusSeconds(120));
        assertEquals(1, store.observationCount(), "пустой продавец совпал с пустым");
    }

    @Test
    @DisplayName("Старые записи без продавца схлопываются при загрузке")
    void collapsesLegacyOnLoad() throws IOException {
        // Так выглядела база до того, как наблюдение стало помнить продавца:
        // одна и та же цена записана семь раз, потому что игрок семь раз
        // пролистнул мимо одного лота.
        long now = Instant.now().toEpochMilli();
        StringBuilder json = new StringBuilder("{\"items\":{\"minecraft:coal\":[");
        for (int i = 0; i < 7; i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"unitPrice\":449,\"seenAtMillis\":").append(now - i * 60_000L).append("}");
        }
        json.append(",{\"unitPrice\":300,\"seenAtMillis\":").append(now).append("}");
        json.append("]},\"names\":{\"minecraft:coal\":\"Coal\"}}");

        Path file = dir.resolve("prices.json");
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);

        PriceStore store = new PriceStore(file);
        store.load();

        // Две разные цены — два наблюдения. Семь одинаковых были одним лотом.
        assertEquals(2, store.observationCount());
        PriceStore.Known known = store.known("minecraft:coal", Duration.ofHours(12)).orElseThrow();
        assertEquals(2, known.samples());
        assertEquals(300, known.cheapestUnitPrice());
    }

    @Test
    @DisplayName("Записи с продавцом схлопывание не трогает")
    void keepsRecordsWithSeller() {
        PriceStore store = store();
        Instant now = Instant.now();

        // Три человека просят одинаково — это три лота, и схлопывать их нельзя.
        store.record("minecraft:coal", "Coal", 449, "alfa", now);
        store.record("minecraft:coal", "Coal", 449, "beta", now);
        store.record("minecraft:coal", "Coal", 449, "gamma", now);
        store.save();

        PriceStore reloaded = store();
        reloaded.load();
        assertEquals(3, reloaded.known("minecraft:coal", Duration.ofHours(12)).orElseThrow().samples());
    }
}
