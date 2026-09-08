package dev.yebatop.holyhelper.analytics;

import dev.yebatop.holyhelper.scan.BuyerParser;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Часы ротации ассортимента Скупца.
 * <p>
 * Сервер пишет в подсказке остаток, а не момент обновления: «Обновление товаров через:
 * 6 ч. 12 мин. 47 сек». Остаток протухает через секунду после того, как игрок закрыл
 * окно, поэтому запоминаем не его, а вычисленный из него момент — дальше часы идут сами,
 * и повторно открывать окно незачем.
 * <p>
 * Циклов два: обычные торги и особые. Они независимы, и сервер называет их периоды
 * в справке — 6 и 8 часов, — но сами периоды здесь не нужны: момент обновления считается
 * из остатка, а не из периода.
 * <p>
 * Классов Minecraft здесь нет намеренно: на вход идут уже разобранные товары, а не
 * снимок окна. Иначе часы нельзя было бы проверить иначе как запуском игры.
 */
public final class RotationTimer {

    private volatile Instant regularAt;
    private volatile Instant specialAt;

    /**
     * Пересчитывает моменты обновления по товарам, прочитанным в момент {@code seenAt}.
     * Товары без таймера пропускаются, а не обнуляют уже известные часы.
     */
    public void update(List<BuyerParser.Offer> offers, Instant seenAt) {
        Instant regular = null;
        Instant special = null;

        for (BuyerParser.Offer offer : offers) {
            if (offer.rotation() == null) {
                continue;
            }
            Instant deadline = seenAt.plus(offer.rotation());
            if (offer.special()) {
                special = later(special, deadline);
            } else {
                regular = later(regular, deadline);
            }
        }

        if (regular != null) {
            regularAt = regular;
        }
        if (special != null) {
            specialAt = special;
        }
    }

    /**
     * Сколько осталось до обновления. Пусто, если окно ещё не открывали.
     * <p>
     * Отрицательный остаток не возвращаем: если срок прошёл, ассортимент уже сменился,
     * и честный ответ — ноль, а не «минус две минуты».
     */
    public Optional<Duration> remaining(boolean special) {
        Instant deadline = special ? specialAt : regularAt;
        if (deadline == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(Instant.now(), deadline);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    public boolean known() {
        return regularAt != null || specialAt != null;
    }

    public void reset() {
        regularAt = null;
        specialAt = null;
    }

    /**
     * Внутри одной группы сервер печатает всем товарам один и тот же остаток.
     * Берём наибольший: так случайно разобранная короткая строка не сдвинет часы вперёд.
     */
    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
