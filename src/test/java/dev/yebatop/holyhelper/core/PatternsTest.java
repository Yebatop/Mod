package dev.yebatop.holyhelper.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("Особое предложение подписывает цену другими словами")
    void parsesSpecialOfferWording() {
        // Ровно та ловушка, ради которой стоило посмотреть на два товара, а не на один.
        // У обычного товара строка «Цена с множителями», у особого — «с учётом множителей».
        // Одна регулярка на оба случая, иначе у всех особых предложений цена терялась бы молча.
        assertEquals(242, patterns.number("buyer.finalPrice", "▌ Цена с множителями: 242 монеток ⛁").orElseThrow());
        assertEquals(757, patterns.number("buyer.finalPrice", "▌ с учётом множителей: 757 монеток ⛁").orElseThrow());

        assertTrue(patterns.match("buyer.special", "✔ Особое предложение ✔").isPresent());
        assertTrue(patterns.match("buyer.special", "Яблоко").isEmpty());
    }

    @Test
    @DisplayName("Строки Скупца сняты с двух живых тултипов")
    void parsesLiveBuyerTooltips() {
        assertEquals(8192, patterns.number("buyer.available", "▌ Доступно к торговле: 8192").orElseThrow());
        assertEquals(16384, patterns.number("buyer.available", "▌ Доступно к торговле: 16384").orElseThrow());

        Matcher hay = patterns.match("buyer.batchPrice", "▌ Начальная цена за 16 шт: 757 монеток ⛁").orElseThrow();
        assertEquals(16, Numbers.parse(hay.group(1)).orElseThrow());
        assertEquals(757, Numbers.parse(hay.group(2)).orElseThrow());

        // У особого предложения таймер шёл на часы, у обычного — на минуты.
        // Это два разных цикла ротации, и группа часов обязана быть необязательной.
        Matcher special = patterns.match("buyer.rotation", "▌ Обновление товаров через: 6 ч. 12 мин. 47 сек").orElseThrow();
        assertEquals("6", special.group(1));
        assertEquals("12", special.group(2));
        assertEquals("47", special.group(3));

        Matcher plain = patterns.match("buyer.rotation", "▌ Обновление товаров через: 12 мин. 21 сек").orElseThrow();
        assertNull(plain.group(1));
        assertEquals("12", plain.group(2));
        assertEquals("21", plain.group(3));
    }

    @Test
    @DisplayName("Скупец и Торговля — разные окна")
    void separatesBuyerScreens() {
        // Раньше оба заголовка ловились одной регуляркой, хотя это разные экраны:
        // «Скупец» — меню с разделами, «Торговля и заработок» — сетка товаров.
        assertTrue(patterns.match("buyer.hubTitle", "Скупец").isPresent());
        assertTrue(patterns.match("buyer.tradeTitle", "Скупец").isEmpty());

        assertTrue(patterns.match("buyer.tradeTitle", "Торговля и заработок").isPresent());
        assertTrue(patterns.match("buyer.hubTitle", "Торговля и заработок").isEmpty());

        // Падежи в подсказках не должны сходить за заголовок окна.
        assertTrue(patterns.match("buyer.hubTitle", "Сдавай накопившиеся ресурсы Скупцу").isEmpty());
        assertTrue(patterns.match("buyer.hubTitle", "Заработайте у Скупца 15 000 монеток").isEmpty());
    }

    @Test
    @DisplayName("Ежедневная сделка целиком")
    void parsesDailyDeal() {
        assertTrue(patterns.match("daily.title", "Ежедневная сделка").isPresent());
        assertEquals(15000, patterns.number("daily.goal",
                "▌ Заработайте у Скупца 15 000 монеток,").orElseThrow());

        // «Прогресс» одинаков у этапов и у ежедневной сделки, поэтому регулярка одна.
        Matcher progress = patterns.match("buyer.progressValue", "▌ Прогресс: 0 / 15 000").orElseThrow();
        assertEquals(0, Numbers.parse(progress.group(1)).orElseThrow());
        assertEquals(15000, Numbers.parse(progress.group(2)).orElseThrow());

        assertTrue(patterns.match("daily.indicator", "▌ Ваш показатель ежедневности:").isPresent());
        assertEquals(0, patterns.number("daily.percent", "████████████ 0%").orElseThrow());

        assertTrue(patterns.match("daily.rewardsHeader", "Случайная награда:").isPresent());
        assertEquals("10 000 монеток",
                patterns.match("daily.reward", "- 10 000 монеток").orElseThrow().group(1));
        assertEquals("множитель на всё 1 уровня на 64 стака",
                patterns.match("daily.reward", "- множитель на всё 1 уровня на 64 стака").orElseThrow().group(1));
    }

    @Test
    @DisplayName("Этап и ежедневная сделка")
    void parsesStageAndDaily() {
        assertEquals(1, patterns.number("stage.number", "Этап #1").orElseThrow());

        Matcher progress = patterns.match("buyer.progressValue", "Прогресс: 0 / 1 000").orElseThrow();
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
    @DisplayName("Невидимый хвост строки не попадает в число")
    void ignoresInvisibleSuffix() {
        // Сервер довешивает справа § и служебный символ, чтобы строки были уникальными.
        // Строки сняты с живого дампа: U+258C слева, U+00A7 U+00A6 справа.
        assertEquals(801, patterns.number("board.coins", "\u258C Монеток: 801 ⛁\u00A7\u00A6").orElseThrow());
        assertEquals(0, patterns.number("board.tokens", "\u258C Жетонов: 0 ⛎\u00A7\u009F").orElseThrow());

        // Здесь и видно, зачем нужен узкий класс захвата. Numbers.parse выкусывает
        // все нецифры подряд и на этой строке дал бы 8011 — если бы регулярка
        // отдала ему хвост целиком. Она обрывается на §, поэтому 801.
        assertEquals(8011, Numbers.parse("801 ⛁\u00A71").orElseThrow());
        assertEquals(801, patterns.number("board.coins", "\u258C Монеток: 801 ⛁\u00A71").orElseThrow());
    }

    @Test
    @DisplayName("Невидимый хвост не попадает и в текстовые значения")
    void ignoresInvisibleSuffixInText() {
        // Числа защищал класс захвата, а свободный текст — нет: жадное (.+) утаскивало
        // хвост целиком. Ник выглядел правильным на экране, потому что U+009D невидим,
        // но в сравнении и в базе это уже был другой ник.
        assertEquals("polyayak",
                patterns.match("board.nick", "\u258C Ник: polyayak\u00A7\u009D").orElseThrow().group(1));
        assertEquals("Нет",
                patterns.match("board.group", "\u258C Группа: Нет\u00A7\u009E").orElseThrow().group(1));
        assertEquals("lemonoff45",
                patterns.match("market.seller", "Продавец: lemonoff45\u00A7\u009C").orElseThrow().group(1));
        assertEquals("Блоки, Все подряд",
                patterns.match("market.category", "Категория: Блоки, Все подряд\u00A7\u009B").orElseThrow().group(1));
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
