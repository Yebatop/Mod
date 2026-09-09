package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Numbers;
import dev.yebatop.holyhelper.core.Patterns;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Разбор окна Биржи. Классов Minecraft здесь нет.
 * <p>
 * У заявки три числа, и они связаны жёстко: {@code отдадите / получите = курс}.
 * Это бесплатная самопроверка разбора — если равенство не сходится, значит регулярка
 * поймала не то, и запись выбрасывается. Дешёвый способ заметить, что сервер поменял
 * формат, до того как мод соврёт игроку про курс.
 */
public final class ExchangeParser {

    private final Patterns patterns;

    public ExchangeParser(Patterns patterns) {
        this.patterns = patterns;
    }

    /**
     * Заявка: сколько жетонов получит игрок, сколько монеток отдаст и по какому курсу.
     * Курс — монетки за один жетон.
     */
    public record Offer(long tokens, long coins, long rate) {
    }

    /**
     * Заявка, которую игрок собирается создать, из окна «Создание лота».
     * <p>
     * Пусто, если чисел нет или они не сходятся между собой.
     */
    public Optional<Offer> parseOwnOffer(List<String> lore) {
        OptionalLong tokens = firstNumber("exchange.willGet", lore);
        OptionalLong coins = firstNumber("exchange.willGive", lore);
        OptionalLong rate = firstNumber("exchange.rate", lore);

        if (tokens.isEmpty() || coins.isEmpty() || rate.isEmpty()) {
            return Optional.empty();
        }
        if (!Numbers.divisionHolds(coins.getAsLong(), tokens.getAsLong(), rate.getAsLong())) {
            // Числа не бьются друг с другом — разбор неверен, и молчать честнее,
            // чем показать курс, взятый неизвестно откуда.
            return Optional.empty();
        }
        return Optional.of(new Offer(tokens.getAsLong(), coins.getAsLong(), rate.getAsLong()));
    }

    /** Опознаёт подсказку собственной заявки среди прочих предметов окна. */
    public boolean isOwnOffer(String name) {
        return patterns.match("exchange.yourOffer", name).isPresent();
    }

    private OptionalLong firstNumber(String key, List<String> lore) {
        for (String line : lore) {
            OptionalLong value = patterns.number(key, line);
            if (value.isPresent()) {
                return value;
            }
        }
        return OptionalLong.empty();
    }
}
