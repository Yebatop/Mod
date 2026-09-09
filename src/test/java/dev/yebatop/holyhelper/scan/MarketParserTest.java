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

        // Случай, ради которого проверка заводилась, оказался не ошибкой разбора,
        // а лотом «только целиком»: сервер пишет цену за единицу равной полной цене.
        // Мод делит сам, и после этого числа сходятся — но признак остаётся, чтобы
        // было видно, что цена посчитана, а не прочитана.
        MarketParser.Lot whole = parser.parseLot("Coal", "minecraft:coal", 64, List.of(
                "▌ Цена: 449⛁",
                "▌ Цена за 1 ед.: 449⛁")).orElseThrow();
        assertTrue(whole.wholeOnly());
        assertTrue(whole.consistent());

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

    @Test
    @DisplayName("Лот, который можно купить только целиком, считается сам")
    void wholeOnlyLot() {
        // Снято с живой витрины. Сервер пишет цену за единицу равной полной цене:
        // раз единицу не купить, то и цены у неё как бы нет. Сравнивать со Скупцом
        // надо настоящую, поэтому мод делит сам.
        MarketParser.Lot lot = parser.parseLot("Packed Ice", "minecraft:packed_ice", 64, List.of(
                "▍ Категория: Блоки, Все подряд",
                "▍ Продавец: zzoile4",
                "▍ Истекает через: 19ч. 35мин. 23сек.",
                "▍ Цена: 94 222¤",
                "▍ Цена за 1 ед.: 94 222¤",
                "● Данный товар можно",
                ".  купить только полностью.",
                "▶ Нажмите ЛКМ, чтобы купить полностью")).orElseThrow();

        assertTrue(lot.wholeOnly());
        assertEquals(94222, lot.price());
        assertEquals(1472, lot.unitPrice());
        assertTrue(lot.consistent(), "после деления числа обязаны сойтись");
    }

    @Test
    @DisplayName("Совпадение цен при стопке больше одной — тот же признак без подписи")
    void wholeOnlyWithoutMarker() {
        // Цена за штуку, равная цене за сорок восемь, не бывает правдой — значит
        // это тот же случай, просто строку про «только полностью» не разобрали.
        MarketParser.Lot lot = parser.parseLot("Ice", "minecraft:ice", 48, List.of(
                "▍ Цена: 94 222¤",
                "▍ Цена за 1 ед.: 94 222¤")).orElseThrow();

        assertTrue(lot.wholeOnly());
        assertEquals(1963, lot.unitPrice());
    }

    @Test
    @DisplayName("Обычный лот из одного предмета признаком не считается")
    void singleItemIsNotWholeOnly() {
        // Тут цены совпадают законно: в лоте один предмет, и цена за него и есть
        // цена лота. Делить нечего, и признак срабатывать не должен.
        MarketParser.Lot lot = parser.parseLot("Gold Ingot", "minecraft:gold_ingot", 1, GOLD)
                .orElseThrow();

        assertFalse(lot.wholeOnly());
        assertEquals(100, lot.unitPrice());
        assertTrue(lot.consistent());
    }
}
