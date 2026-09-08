package dev.yebatop.holyhelper.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка разбора на строках, снятых со скриншотов живого сервера.
 * <p>
 * Это фикстуры, а не выдумка: каждая строка здесь реально висела на экране.
 * Когда сервер поменяет формулировки, эти тесты покраснеют раньше, чем мод соврёт
 * игроку, — и сразу будет видно, какую именно строку править в patterns.json.
 */
class PatternsTest {

    private final Patterns patterns = Patterns.builtin();

    @Test
    @DisplayName("Разделители разрядов сервер печатает по-разному, число всё равно читается")
    void parsesEveryThousandSeparator() {
        assertEquals(11252, Numbers.parse("11,252").orElseThrow());
        assertEquals(1000, Numbers.parse("1 000").orElseThrow());
        assertEquals(16384, Numbers.parse("16384").orElseThrow());
        assertEquals(6000000, Numbers.parse("6,000,000").orElseThrow());
        assertEquals(800, Numbers.parse("800 ⛁").orElseThrow());
        assertTrue(Numbers.parse("нет цифр").isEmpty());
        assertTrue(Numbers.parse(null).isEmpty());
    }

    @Test
    @DisplayName("Тултип товара Скупца")
    void parsesBuyerTooltip() {
        assertEquals(16384, patterns.number("buyer.available", "Доступно к торговле: 16384").orElseThrow());
        assertEquals(268, patterns.number("buyer.finalPrice", "Цена с множителями: 268 монеток").orElseThrow());

        Matcher batch = patterns.match("buyer.batchPrice", "Начальная цена за 16 шт: 268 монеток").orElseThrow();
        assertEquals(16, Numbers.parse(batch.group(1)).orElseThrow());
        assertEquals(268, Numbers.parse(batch.group(2)).orElseThrow());

        Matcher rotation = patterns.match("buyer.rotation", "Обновление товаров через: 3 ч. 13 мин. 51 сек.").orElseThrow();
        assertEquals("3", rotation.group(1));
        assertEquals("13", rotation.group(2));
        assertEquals("51", rotation.group(3));
    }

    @Test
    @DisplayName("Этап и ежедневная сделка")
    void parsesStageAndDaily() {
        assertEquals(1, patterns.number("buyer.stage", "Этап #1").orElseThrow());

        Matcher progress = patterns.match("buyer.progress", "Прогресс: 0 / 1 000").orElseThrow();
        assertEquals(0, Numbers.parse(progress.group(1)).orElseThrow());
        assertEquals(1000, Numbers.parse(progress.group(2)).orElseThrow());

        assertEquals(15000, patterns.number("daily.goal",
                "Заработайте у Скупца 15 000 монеток, продавая любые предметы").orElseThrow());
    }

    @Test
    @DisplayName("Заголовок и лот Маркета")
    void parsesMarket() {
        Matcher title = patterns.match("market.screenTitle", "Маркет (1/26)").orElseThrow();
        assertEquals("1", title.group(1));
        assertEquals("26", title.group(2));

        assertEquals("lemonoff45", patterns.match("market.seller", "Продавец: lemonoff45").orElseThrow().group(1));
        assertEquals("Блоки, Все подряд",
                patterns.match("market.category", "Категория: Блоки, Все подряд").orElseThrow().group(1));
        assertEquals(2100, patterns.number("market.price", "Цена: 2100").orElseThrow());
        assertEquals(300, patterns.number("market.unitPrice", "Цена за 1 ед.: 300").orElseThrow());

        Matcher left = patterns.match("market.timeLeft", "Осталось времени: 23ч. 20мин. 24сек.").orElseThrow();
        assertEquals("23", left.group(1));
        assertEquals("20", left.group(2));
        assertEquals("24", left.group(3));
    }

    @Test
    @DisplayName("Заявка Биржи сходится сама с собой")
    void parsesExchangeOffer() {
        long rate = patterns.number("exchange.rate", "Курс: 8,323 ⛁ (за 1 жетон)").orElseThrow();
        long expected = patterns.number("exchange.expected", "Ожидается: 10 (жетонов)").orElseThrow();
        long available = patterns.number("exchange.available", "Доступно: 83,230 (монеток)").orElseThrow();

        assertEquals(8323, rate);
        assertEquals(10, expected);
        assertEquals(83230, available);
        assertTrue(Numbers.divisionHolds(available, rate, expected),
                "Доступно / Курс должно давать Ожидается");
    }

    @Test
    @DisplayName("Окно создания лота тоже сходится")
    void parsesOfferCreation() {
        long get = patterns.number("exchange.willGet", "Вы получите: 5 (жетонов)").orElseThrow();
        long give = patterns.number("exchange.willGive", "Вы отдадите: 56,255 (монеток)").orElseThrow();
        long rate = patterns.number("exchange.rate", "Курс: 11,251 (за 1 жетон)").orElseThrow();

        assertEquals(5, get);
        assertEquals(56255, give);
        assertTrue(Numbers.divisionHolds(give, get, rate),
                "Вы отдадите / Вы получите должно давать Курс");
    }

    @Test
    @DisplayName("Битый разбор ловится проверкой равенства")
    void catchesBrokenParse() {
        // Если разделитель разрядов уедет в захват как лишняя цифра, равенство развалится.
        assertFalse(Numbers.divisionHolds(83230, 8323, 42));
        assertFalse(Numbers.divisionHolds(100, 0, 1));
    }

    @Test
    @DisplayName("Сайдбар")
    void parsesSidebar() {
        assertEquals("polyayak", patterns.match("board.nick", "Ник: polyayak").orElseThrow().group(1));
        assertEquals(800, patterns.number("board.coins", "Монеток: 800").orElseThrow());
        assertEquals(0, patterns.number("board.gems", "Гемов: 0").orElseThrow());
        assertEquals(0, patterns.number("board.tokens", "Жетонов: 0").orElseThrow());
        assertEquals(64, patterns.number("board.ping", "Пинг: 64").orElseThrow());

        Matcher server = patterns.match("board.server", "✖ Прайм #1 ✖").orElseThrow();
        assertEquals("Прайм", server.group(1));
        assertEquals("1", server.group(2));
    }

    @Test
    @DisplayName("Метку слева украшают, и разбор это переживает")
    void parsesDecoratedSidebar() {
        // На этом мод и споткнулся в игре: слева от метки сервер рисует цветную полоску,
        // «Баланс» уезжал в -1, хотя панель читалась. Якорь ^ такое не переживает.
        assertEquals(800, patterns.number("board.coins", "▌ Монеток: 800 ⛁").orElseThrow());
        assertEquals(0, patterns.number("board.gems", "\u258C Гемов: 0 \u2724").orElseThrow());
        assertEquals(65, patterns.number("board.ping", "  ▌Пинг: 65 ⚡").orElseThrow());
        assertEquals("polyayak",
                patterns.match("board.nick", "▌ Ник: polyayak").orElseThrow().group(1));
        assertEquals("Нет",
                patterns.match("board.group", "▌ Группа: Нет").orElseThrow().group(1));
    }

    @Test
    @DisplayName("Чужие строки не притворяются нашими")
    void ignoresUnrelatedLines() {
        assertTrue(patterns.match("board.coins", "Монет: 800").isEmpty());
        // Ослабление якоря не должно ловить метку, приклеенную к другому слову.
        assertTrue(patterns.match("board.coins", "ВсегоМонеток: 800").isEmpty());
        assertTrue(patterns.match("board.nick", "ПсевдоНик: polyayak").isEmpty());
        assertTrue(patterns.number("buyer.available", "Доступно: 83,230 (монеток)").isEmpty());
        assertTrue(patterns.match("market.screenTitle", "Биржа").isEmpty());
    }
}
