package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Строки сняты с живого Маркета Прайма. */
class MarketParserTest {

    private final MarketParser parser = new MarketParser(Patterns.builtin());

    /** Подсказка золотого слитка, снятая символ в символ. */
    private static final List<String> GOLD = List.of(
            "▌ Категория: Драгоценности, Все подряд",
            "▌ Продавец: XDeadToEezzka1",
            "▌ Истекает через: 21ч. 12мин. 9сек.",
            "▌ Цена: 100⛁",
            "▌ Цена за 1 ед.: 100⛁",
            "▶ Нажмите ЛКМ, чтобы купить полностью",
            "▶ Нажмите ПКМ, чтобы купить поштучно");

    @Test
    @DisplayName("Лот Маркета")
    void parsesLot() {
        MarketParser.Lot lot = parser.parseLot("Gold Ingot", "minecraft:gold_ingot", 1, GOLD).orElseThrow();

        assertEquals("Gold Ingot", lot.name());
        assertEquals("XDeadToEezzka1", lot.seller());
        assertEquals(100, lot.price());
        assertEquals(100, lot.unitPrice());
        assertEquals(1, lot.quantity());
        assertEquals(Duration.ofHours(21).plusMinutes(12).plusSeconds(9), lot.expiresIn());
    }

    @Test
    @DisplayName("Срок подписан двумя разными формулировками")
    void acceptsBothTimeWordings() {
        // На свежих скриншотах «Истекает через», на прежних — «Осталось времени».
        // Если знать только одну, у половины лотов срок терялся бы молча.
        MarketParser.Lot fresh = parser.parseLot("A", "minecraft:stone", 1, List.of(
                "▌ Истекает через: 1ч. 2мин. 3сек.",
                "▌ Цена: 10⛁",
                "▌ Цена за 1 ед.: 10⛁")).orElseThrow();
        assertEquals(Duration.ofHours(1).plusMinutes(2).plusSeconds(3), fresh.expiresIn());

        MarketParser.Lot old = parser.parseLot("B", "minecraft:stone", 1, List.of(
                "▌ Осталось времени: 23ч. 20мин. 24сек.",
                "▌ Цена: 10⛁",
                "▌ Цена за 1 ед.: 10⛁")).orElseThrow();
        assertEquals(Duration.ofHours(23).plusMinutes(20).plusSeconds(24), old.expiresIn());
    }

    @Test
    @DisplayName("Один предмет лежит сразу в нескольких категориях")
    void splitsCategories() {
        MarketParser.Lot lot = parser.parseLot("Gold Ingot", "minecraft:gold_ingot", 1, GOLD).orElseThrow();
        assertEquals(List.of("Драгоценности", "Все подряд"), lot.categories());
    }

    @Test
    @DisplayName("Количество считается из цены и цены за единицу")
    void derivesQuantity() {
        MarketParser.Lot stack = parser.parseLot("Coal", "minecraft:coal", 7, List.of(
                "▌ Продавец: someone",
                "▌ Цена: 2100⛁",
                "▌ Цена за 1 ед.: 300⛁")).orElseThrow();

        assertEquals(7, stack.quantity());
    }

    @Test
    @DisplayName("Служебные предметы окна лотом не притворяются")
    void skipsNonLots() {
        assertTrue(parser.parseLot("Следующая страница", "minecraft:arrow", 1,
                List.of("Следующая страница ▶")).isEmpty());
        assertTrue(parser.parseLot("Категории предметов", "minecraft:chest", 1, List.of(
                "✓ Все подряд", "• Инструменты", "• Оружие")).isEmpty());
        assertTrue(parser.parseLot("", "minecraft:gray_stained_glass_pane", 1, List.of()).isEmpty());
    }

    @Test
    @DisplayName("Страница и общее число читаются из заголовка")
    void parsesPage() {
        MarketParser.Page page = parser.parsePage("Маркет (1/29)").orElseThrow();
        assertEquals(1, page.current());
        assertEquals(29, page.total());

        // Число страниц зависит от того, сколько выставили игроки: было 26, стало 29.
        assertEquals(26, parser.parsePage("Маркет (3/26)").orElseThrow().total());
        assertTrue(parser.parsePage("Скупец").isEmpty());
    }

    @Test
    @DisplayName("Галочка показывает выбранную сортировку и категорию")
    void readsActiveChoice() {
        // Без этого наблюдения лотов бессмысленны: неизвестно, какой срез рынка виден.
        assertEquals("Сначала дешевые", parser.parseActiveChoice(List.of(
                "✓ Сначала дешевые",
                "• Сначала дорогие",
                "• Сначала дешевые за ед. товара",
                "• Сначала новые")).orElseThrow());

        assertEquals("Все подряд", parser.parseActiveChoice(List.of(
                "✓ Все подряд",
                "• Инструменты",
                "• Оружие")).orElseThrow());

        assertTrue(parser.parseActiveChoice(List.of("• Инструменты", "• Оружие")).isEmpty());
    }

    @Test
    @DisplayName("Три числа лота проверяют друг друга")
    void checksItself() {
        // Цена лота, цена за единицу и размер стопки связаны жёстко. Это бесплатная
        // проверка разбора: если равенство не держится, одно из чисел прочитано не
        // оттуда, и запись в базу цен пойдёт враньём.
        MarketParser.Lot honest = parser.parseLot("Coal", "minecraft:coal", 7, List.of(
                "▌ Цена: 2100⛁",
                "▌ Цена за 1 ед.: 300⛁")).orElseThrow();
        assertTrue(honest.consistent());

        // Ровно та ошибка, ради которой проверка и заведена: цена лота попала
        // в поле цены за штуку. Сравнение со Скупцом после такого врёт в разы.
        MarketParser.Lot swapped = parser.parseLot("Coal", "minecraft:coal", 64, List.of(
                "▌ Цена: 449⛁",
                "▌ Цена за 1 ед.: 449⛁")).orElseThrow();
        assertFalse(swapped.consistent());

        // Сервер округляет цену за единицу, и на стопке накапливается расхождение.
        // Единица на предмет — это округление, а не ошибка разбора.
        MarketParser.Lot rounded = parser.parseLot("Coal", "minecraft:coal", 64, List.of(
                "▌ Цена: 449⛁",
                "▌ Цена за 1 ед.: 7⛁")).orElseThrow();
        assertTrue(rounded.consistent());

        // Размер стопки клиент отдаёт всегда, но если он нулевой — проверять нечего,
        // и запись такого лота была бы догадкой.
        MarketParser.Lot unknown = parser.parseLot("Coal", "minecraft:coal", 0, List.of(
                "▌ Цена: 2100⛁",
                "▌ Цена за 1 ед.: 300⛁")).orElseThrow();
        assertFalse(unknown.consistent());
    }
}
