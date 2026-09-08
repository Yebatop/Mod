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

        // Уровень здесь намеренно не проверяется: в названии его нет. Прежняя версия
        // теста искала арабскую цифру в названии — это была догадка, и игра её
        // опровергла. Настоящий разбор уровня проверяется на строке из подсказки.
        BuyerParser.Multiplier stocked = parser.parseMultiplier("Множитель на все", List.of(
                "▌ Доступно:",
                "▌ 64 стака")).orElseThrow();

        assertEquals(64, stocked.stacks());

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

    /** Этап #17, закрытый. Снят с живого окна «Этапы и награды». */
    private static final List<String> STAGE_17 = List.of(
            "▌ Заработать 175 000 монеток, торгуя",
            "▌ со Скупцом любыми товарами, которые",
            "▌ можно получить при торговле с жителями",
            "✗ Сперва необходимо выполнить",
            "  предыдущие этапы!",
            "Награда:",
            "- Случайное яйцо из:",
            " - яйцо крипера",
            " - яйцо визер-скелета",
            " - яйцо всполоха",
            " - яйцо эндермена");

    @Test
    @DisplayName("Закрытый этап: цель, склеенное описание, отсутствующий прогресс")
    void parsesLockedStage() {
        BuyerParser.Stage stage = parser.parseStage("Этап #17", STAGE_17).orElseThrow();

        assertEquals(17, stage.number());
        assertEquals(175000, stage.goal());
        assertTrue(stage.locked());

        // Сервер разбивает текст цели переносами на три строки — склеиваем обратно.
        assertEquals("Заработать 175 000 монеток, торгуя со Скупцом любыми товарами, "
                + "которые можно получить при торговле с жителями", stage.description());

        // У закрытого этапа прогресса нет вовсе. Ноль здесь был бы враньём:
        // это «неизвестно», а не «нисколько».
        assertFalse(stage.hasProgress());
        assertEquals(-1, stage.progress());
        assertEquals(0, stage.completion(), 1e-9);
    }

    @Test
    @DisplayName("Открытый этап показывает прогресс")
    void parsesOpenStage() {
        BuyerParser.Stage stage = parser.parseStage("Этап #1", List.of(
                "▌ Заработать 1 000 монеток, торгуя со Скупцом",
                "▌ любыми предметами",
                "▌ Прогресс: 250 / 1 000",
                "Награда:")).orElseThrow();

        assertFalse(stage.locked());
        assertTrue(stage.hasProgress());
        assertEquals(250, stage.progress());
        assertEquals(0.25, stage.completion(), 1e-9);
    }

    @Test
    @DisplayName("Прогресс читается и когда съехал на следующую строку")
    void parsesProgressSplitAcrossLines() {
        // Живой Этап #1: «Прогресс:» в одной строке, «800 / 1 000» в следующей.
        // У ежедневной сделки та же пара умещалась в одну строку — сервер переносит
        // непоследовательно, поэтому значение ищется рядом с меткой, а не в ней.
        BuyerParser.Stage stage = parser.parseStage("Этап #1", List.of(
                "▌ Заработать 1 000 монеток, торгуя",
                "▌ со Скупцом любыми предметами",
                "⚡ Прогресс:",
                "⚡ 800 / 1 000",
                "Награда:",
                "- Особый ключ испытаний x1",
                "- Множитель I ур. на всё на 100 стаков")).orElseThrow();

        assertEquals(1, stage.number());
        assertEquals(1000, stage.goal());
        assertFalse(stage.locked());
        assertTrue(stage.hasProgress());
        assertEquals(800, stage.progress());
        assertEquals(0.8, stage.completion(), 1e-9);
    }

    @Test
    @DisplayName("Чужое число из соседнего раздела за прогресс не сходит")
    void doesNotGrabDistantNumbers() {
        // Между меткой и числом наград лежит достаточно строк, чтобы поиск не дотянулся.
        BuyerParser.Stage stage = parser.parseStage("Этап #5", List.of(
                "▌ Заработать 10 000 монеток, торгуя",
                "✗ Сперва необходимо выполнить",
                "  предыдущие этапы!",
                "Награда:",
                "- 40 / 60 чего-то постороннего")).orElseThrow();

        assertTrue(stage.locked());
        assertFalse(stage.hasProgress());
    }

    @Test
    @DisplayName("Цель этапа и цель ежедневной сделки — разные строки")
    void stageGoalDiffersFromDailyGoal() {
        // «Заработать N монеток, торгуя» против «Заработайте у Скупца N монеток».
        // Одной регуляркой их не поймать, и попытка обошлась бы молчащим разбором.
        BuyerParser.Stage stage = parser.parseStage("Этап #2",
                List.of("▌ Заработать 5 000 монеток, торгуя со Скупцом")).orElseThrow();
        assertEquals(5000, stage.goal());

        assertTrue(parser.parseStage("Яблоко", List.of("▌ Доступно к торговле: 16")).isEmpty());
    }

    @Test
    @DisplayName("Закрытая ячейка товара опознаётся отдельно")
    void parsesLockedSlot() {
        List<String> lore = List.of(
                "✗ Эта ячейка для Товаров будет",
                "  разблокирована после выполнения",
                "  15 Этапов торговли со Скупцом!");

        // Товаром она не считается — объёма приёма у неё нет.
        assertTrue(parser.parseOffer("", "minecraft:gray_stained_glass_pane", lore).isEmpty());

        BuyerParser.LockedSlot slot = parser.parseLockedSlot(lore).orElseThrow();
        assertEquals(15, slot.stagesRequired());

        assertTrue(parser.parseLockedSlot(APPLE).isEmpty());
    }

    @Test
    @DisplayName("Выполненный этап — третье состояние, а не текущий без прогресса")
    void parsesCompletedStage() {
        // У выполненного этапа нет ни прогресса, ни отметки о закрытии. Без отдельного
        // признака он выглядел бы ровно как текущий с неизвестным прогрессом.
        BuyerParser.Stage done = parser.parseStage("Этап #1", List.of(
                "▌ Заработать 1 000 монеток, торгуя",
                "▌ со Скупцом любыми предметами",
                "✓ Выполнено",
                "Награда:",
                "- Особый ключ испытаний x1",
                "- Множитель I ур. на всё на 100 стаков")).orElseThrow();

        assertTrue(done.completed());
        assertFalse(done.locked());
        assertFalse(done.hasProgress());

        BuyerParser.Stage current = parser.parseStage("Этап #2", List.of(
                "▌ Заработать 2 500 монеток, торгуя",
                "⚡ Прогресс:",
                "⚡ 0 / 2 500")).orElseThrow();

        assertFalse(current.completed());
        assertFalse(current.locked());
        assertTrue(current.hasProgress());
        assertEquals(0, current.progress());
    }

    @Test
    @DisplayName("Уровень множителя стоит римской цифрой в подсказке, не в названии")
    void parsesRomanLevelFromLore() {
        BuyerParser.Multiplier all = parser.parseMultiplier("Множитель на все", List.of(
                "▌ Доступно:",
                "▌ 100 стаков с множителем I ур.",
                "▶ Нажмите, чтобы посмотреть предметы,")).orElseThrow();

        assertEquals("все", all.category());
        assertEquals(100, all.stacks());
        assertEquals(1, all.level());

        // Пустой множитель уровня не называет вовсе.
        BuyerParser.Multiplier empty = parser.parseMultiplier("Множитель на блоки", List.of(
                "▌ Доступно:",
                "▌ 0 стаков")).orElseThrow();
        assertEquals(0, empty.level());
    }

    @Test
    @DisplayName("Надбавки уровней читаются из справки сервера")
    void parsesBonusesFromHelp() {
        // Формула выгоды напечатана сервером: I, II, III дают 5, 10 и 15 процентов,
        // а множители разных категорий перемножаются. Зашивать это в код незачем.
        List<String> help = List.of(
                "▌ На многие товары может действовать множитель.",
                "▌ Они бывают I, II и III уровней, прибавляя к стоимости",
                "▌ товаров по 5%, 10% и 15% соответственно.",
                "▌ Если один товар относится сразу к нескольким множителям,",
                "▌ то они между собой умножаются, и цена получается выше!");

        BuyerParser.Bonuses bonuses = parser.parseBonuses(help).orElseThrow();
        assertEquals(5, bonuses.levelOne());
        assertEquals(10, bonuses.levelTwo());
        assertEquals(15, bonuses.levelThree());

        assertEquals(0.05, bonuses.share(1), 1e-9);
        assertEquals(0.15, bonuses.share(3), 1e-9);
        assertEquals(0, bonuses.share(0), 1e-9);

        assertTrue(parser.parseBonuses(List.of("ничего похожего")).isEmpty());
    }

    @Test
    @DisplayName("Две категории с множителями перемножаются, а не складываются")
    void bonusesMultiply() {
        BuyerParser.Bonuses bonuses = new BuyerParser.Bonuses(5, 10, 15);

        // Товар в двух категориях с уровнями I и II: 1,05 × 1,10 = 1,155, а не 1,15.
        double combined = (1 + bonuses.share(1)) * (1 + bonuses.share(2));
        assertEquals(1.155, combined, 1e-9);
    }
}
