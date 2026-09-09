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

    // Длина цикла нужна только полосе в панели: чтобы показать остаток долей, надо
    // знать, долей чего. Сервер называет периоды в справке, но справка лежит в
    // отдельном окне и открыта не всегда, поэтому до неё длина берётся как
    // наибольший остаток, который мод сам застал. Это оценка снизу: пока игрок не
    // заглянул к Скупцу сразу после обновления, полоса покажет меньше правды.
    private volatile Duration regularSpan;
    private volatile Duration specialSpan;
    private volatile boolean regularExact;
    private volatile boolean specialExact;

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
                specialSpan = longer(specialSpan, offer.rotation(), specialExact);
            } else {
                regular = later(regular, deadline);
                regularSpan = longer(regularSpan, offer.rotation(), regularExact);
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

    /**
     * Запоминает точный период из справки Скупца. Точное значение сильнее
     * наблюдённого: справку пишет сервер, а наблюдение — это всего лишь «столько
     * я однажды видел».
     */
    public void learnPeriod(boolean special, Duration period) {
        if (period == null || period.isZero() || period.isNegative()) {
            return;
        }
        if (special) {
            specialSpan = period;
            specialExact = true;
        } else {
            regularSpan = period;
            regularExact = true;
        }
    }

    /**
     * Какая доля цикла ещё не прошла: 1 — только обновилось, 0 — вот-вот обновится.
     * Пусто, пока длина цикла неизвестна, — рисовать полосу без знаменателя нельзя.
     */
    public Optional<Double> remainingFraction(boolean special) {
        Duration span = special ? specialSpan : regularSpan;
        Optional<Duration> left = remaining(special);
        if (span == null || span.isZero() || left.isEmpty()) {
            return Optional.empty();
        }
        double value = left.get().toMillis() / (double) span.toMillis();
        return Optional.of(Math.max(0, Math.min(1, value)));
    }

    /** Взята ли длина цикла из справки, а не из наблюдений. */
    public boolean periodExact(boolean special) {
        return special ? specialExact : regularExact;
    }

    public void reset() {
        regularAt = null;
        specialAt = null;
        regularSpan = null;
        specialSpan = null;
        regularExact = false;
        specialExact = false;
    }

    /**
     * Наибольший из остатков. Точное значение из справки не перебивается
     * наблюдением: иначе одна подсказка с большим остатком испортила бы знаменатель.
     */
    private static Duration longer(Duration current, Duration candidate, boolean exact) {
        if (exact) {
            return current;
        }
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    /**
     * Внутри одной группы сервер печатает всем товарам один и тот же остаток.
     * Берём наибольший: так случайно разобранная короткая строка не сдвинет часы вперёд.
     */
    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
