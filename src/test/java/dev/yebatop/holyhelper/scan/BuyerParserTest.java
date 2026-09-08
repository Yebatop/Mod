package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подсказки сняты с живого Скупца, символ в символ — вместе с полоской U+258C слева
 * и значком валюты справа.
 */
class BuyerParserTest {

    private final BuyerParser parser = new BuyerParser(Patterns.builtin());

    /** Яблоко, обычный товар. */
    private static final List<String> APPLE = List.of(
            "▌ Доступно к торговле: 16384",
            "▌",
            "▌ Начальная цена за 16 шт: 242 монеток ⛁",
            "▌ Цена с множителями: 242 монеток ⛁",
            "▌",
            "▌ Обновление товаров через: 12 мин. 21 сек",
            "▶ Нажмите ПКМ, чтобы продать 1 шт",
            "▶ Нажмите ЛКМ, чтобы продать 64 шт",
            "▶ Нажмите Shift + ПКМ, чтобы продать всё");

    /** Сноп сена, особое предложение — другая подпись итоговой цены. */
    private static final List<String> HAY = List.of(
            "✔ Особое предложение ✔",
            "▌ Доступно к торговле: 8192",
            "▌",
            "▌ Начальная цена за 16 шт: 757 монеток ⛁",
            "▌ с учётом множителей: 757 монеток ⛁",
            "▌",
            "▌ Обновление товаров через: 6 ч. 12 мин. 47 сек");

    @Test
    @DisplayName("Обычный товар")
    void parsesPlainOffer() {
        BuyerParser.Offer offer = parser.parseOffer("Яблоко", "minecraft:apple", APPLE).orElseThrow();

        assertEquals("Яблоко", offer.name());
        assertFalse(offer.special());
        assertEquals(16384, offer.available());
        assertEquals(16, offer.batchSize());
        assertEquals(242, offer.batchPrice());
        assertEquals(242, offer.finalPrice());
        assertEquals(Duration.ofMinutes(12).plusSeconds(21), offer.rotation());
        assertEquals(15.125, offer.unitPrice(), 1e-9);
        assertEquals(1.0, offer.multiplierFactor(), 1e-9);
    }

    @Test
    @DisplayName("Особое предложение: другая подпись цены и таймер на часы")
    void parsesSpecialOffer() {
        BuyerParser.Offer offer = parser.parseOffer("Сноп сена", "minecraft:hay_block", HAY).orElseThrow();

        assertTrue(offer.special());
        assertEquals(8192, offer.available());
        // Цена лежит в строке «с учётом множителей». Знай парсер только «Цена с
        // множителями», здесь молча оказался бы ноль.
        assertEquals(757, offer.finalPrice());
        assertEquals(Duration.ofHours(6).plusMinutes(12).plusSeconds(47), offer.rotation());
        assertEquals(47.3125, offer.unitPrice(), 1e-9);
    }

    @Test
    @DisplayName("Цена за штуку дробная")
    void keepsFractionalUnitPrice() {
        // Глина: 346 за 16 — это 21,625. Округление до целого врёт при сравнении с Маркетом.
        BuyerParser.Offer clay = parser.parseOffer("Глина", "minecraft:clay_ball", List.of(
                "▌ Доступно к торговле: 4096",
                "▌ Начальная цена за 16 шт: 346 монеток ⛁",
                "▌ Цена с множителями: 346 монеток ⛁")).orElseThrow();

        assertEquals(21.625, clay.unitPrice(), 1e-9);
    }

    @Test
    @DisplayName("Действующий множитель поднимает итоговую цену")
    void reportsMultiplierFactor() {
        BuyerParser.Offer boosted = parser.parseOffer("Уголь", "minecraft:coal", List.of(
                "▌ Доступно к торговле: 1024",
                "▌ Начальная цена за 16 шт: 100 монеток ⛁",
                "▌ Цена с множителями: 250 монеток ⛁")).orElseThrow();

        assertEquals(2.5, boosted.multiplierFactor(), 1e-9);
        assertEquals(15.625, boosted.unitPrice(), 1e-9);
    }

    @Test
    @DisplayName("Служебные предметы товаром не притворяются")
    void skipsNonOffers() {
        assertTrue(parser.parseOffer("Назад", "minecraft:firework_rocket",
                List.of("◀ Назад")).isEmpty());
        assertTrue(parser.parseOffer("Полезная информация", "minecraft:book", List.of(
                "▌ Торгуя ресурсами со Скупцом, можно заработать",
                "▌ - обычные торги обновляются раз в 6 часов")).isEmpty());
        assertTrue(parser.parseOffer("", "minecraft:orange_stained_glass_pane", List.of()).isEmpty());
    }

    @Test
    @DisplayName("Таймер без часов")
    void handlesMissingHours() {
        BuyerParser.Offer offer = parser.parseOffer("Яблоко", "minecraft:apple", APPLE).orElseThrow();
        assertEquals(741, offer.rotation().toSeconds());

        BuyerParser.Offer noTimer = parser.parseOffer("Яблоко", "minecraft:apple", List.of(
                "▌ Доступно к торговле: 10")).orElseThrow();
        assertNull(noTimer.rotation());
    }

    @Test
    @DisplayName("Множитель: метка и значение на разных строках")
    void parsesMultiplierAcrossLines() {
        BuyerParser.Multiplier zero = parser.parseMultiplier("Множитель на блоки", List.of(
                "▌ Доступно:",
                "▌ 0 стаков",
                "▶ Нажмите, чтобы посмотреть предметы,",
                "  на которые действует этот множитель")).orElseThrow();

        assertEquals("блоки", zero.category());
        assertEquals(0, zero.stacks());

        BuyerParser.Multiplier stocked = parser.parseMultiplier("Множитель на всё 1 уровня", List.of(
                "▌ Доступно:",
                "▌ 64 стака")).orElseThrow();

        assertEquals(64, stocked.stacks());
        assertEquals(1, stocked.level());

        assertTrue(parser.parseMultiplier("Яблоко", List.of("▌ Доступно к торговле: 16")).isEmpty());
    }

    @Test
    @DisplayName("Периоды ротации читаются из справки сервера")
    void readsRotationPeriodsFromHelp() {
        // Зашивать 6 и 8 часов в код незачем: сервер печатает их сам.
        List<String> help = List.of(
                "▌ Торгуя ресурсами со Скупцом, можно заработать",
                "▌ очень немало деньжат. Вот основная информация:",
                "▌ - обычные торги обновляются раз в 6 часов",
                "▌ - особые торги обновляются раз в 8 часов",
                "▌ - на многие товары можно заполучить множитель монеток;");

        assertEquals(Duration.ofHours(6), parser.parseRotationPeriod(help, false).orElseThrow());
        assertEquals(Duration.ofHours(8), parser.parseRotationPeriod(help, true).orElseThrow());
        assertTrue(parser.parseRotationPeriod(List.of("что-то другое"), false).isEmpty());
    }
}
