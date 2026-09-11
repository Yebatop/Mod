package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Строки сняты с окна «Создание лота» на живом сервере. */
class ExchangeParserTest {

    private final ExchangeParser parser = new ExchangeParser(Patterns.builtin());

    @Test
    @DisplayName("Заявка, которую игрок собирается создать")
    void parsesOwnOffer() {
        ExchangeParser.Offer offer = parser.parseOwnOffer(List.of(
                "▌ Вы получите: 5 ⬤ (жетонов)",
                "▌ Вы отдадите: 85,040 ⛁ (монеток)",
                "▌ Курс: 17,008 ⛁ (за 1 жетон)",
                "⚡ Нажмите ЛКМ, чтобы создать заявку",
                "⚡ Нажмите ПКМ, чтобы изменить курс")).orElseThrow();

        assertEquals(5, offer.tokens());
        assertEquals(85040, offer.coins());
        assertEquals(17008, offer.rate());
    }

    @Test
    @DisplayName("Числа проверяют сами себя")
    void rejectsInconsistentNumbers() {
        // 85 040 / 5 = 17 008 — равенство обязано сходиться. Если разбор поймал
        // не то число, оно развалится, и лучше промолчать, чем соврать про курс.
        assertTrue(parser.parseOwnOffer(List.of(
                "▌ Вы получите: 5 ⬤ (жетонов)",
                "▌ Вы отдадите: 85,040 ⛁ (монеток)",
                "▌ Курс: 999 ⛁ (за 1 жетон)")).isEmpty());

        // Прежняя заявка с другими числами — тоже сходится, проверено на живых.
        assertEquals(11251, parser.parseOwnOffer(List.of(
                "▌ Вы получите: 5 (жетонов)",
                "▌ Вы отдадите: 56,255 (монеток)",
                "▌ Курс: 11,251 (за 1 жетон)")).orElseThrow().rate());
    }

    @Test
    @DisplayName("Неполная подсказка не разбирается")
    void needsAllThreeNumbers() {
        assertTrue(parser.parseOwnOffer(List.of("▌ Курс: 17,008 ⛁ (за 1 жетон)")).isEmpty());
        assertTrue(parser.parseOwnOffer(List.of()).isEmpty());
    }

    @Test
    @DisplayName("Своя заявка отличается от чужих")
    void recognisesOwnOffer() {
        assertTrue(parser.isOwnOffer("Ваша заявка"));
        assertFalse(parser.isOwnOffer("Заявка игрока h1dosq"));
        assertFalse(parser.isOwnOffer("Золотой слиток"));
    }
}
