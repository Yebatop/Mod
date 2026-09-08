package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Numbers;
import dev.yebatop.holyhelper.core.Patterns;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;

/**
 * Разбор товаров Скупца из названия предмета и строк его подсказки.
 * <p>
 * Здесь намеренно нет ни одного класса Minecraft. Всё, что зависит от игры, живёт
 * в {@link BuyerScanner}, а сюда приходят уже готовые строки. Иначе эту логику
 * нельзя было бы проверить иначе как запуском игры, а её нужно проверять на строках,
 * снятых с живого сервера, — именно так вскрылось, что у особого предложения цена
 * подписана другими словами.
 */
public final class BuyerParser {

    private final Patterns patterns;

    public BuyerParser(Patterns patterns) {
        this.patterns = patterns;
    }

    /**
     * Товар Скупца.
     *
     * @param batchSize котировка сервера — сколько штук в объявленной цене.
     *                  Сейчас у всех товаров 16, но читаем из строки, а не считаем константой.
     * @param rotation  сколько осталось до обновления ассортимента; {@code null}, если
     *                  сервер не написал таймер.
     */
    public record Offer(
            String name,
            String itemId,
            boolean special,
            long available,
            int batchSize,
            long batchPrice,
            long finalPrice,
            Duration rotation) {

        /**
         * Цена за штуку. Дробная и обязана таковой остаться: у глины 346 за 16 даёт 21,62,
         * а округление до 21,6 уже врёт при сравнении с Маркетом.
         */
        public double unitPrice() {
            return batchSize <= 0 ? 0 : (double) finalPrice / batchSize;
        }

        /** Во сколько раз множители подняли цену. 1.0 — множителей нет. */
        public double multiplierFactor() {
            return batchPrice <= 0 ? 1 : (double) finalPrice / batchPrice;
        }
    }

    /** Множитель торговли: категория и остаток в стаках. */
    public record Multiplier(String category, int level, int stacks) {
    }

    /**
     * Собирает товар из подсказки. Пусто — значит это не товар: стекло-разделитель,
     * кнопка «Назад» или книга со справкой.
     */
    public Optional<Offer> parseOffer(String name, String itemId, List<String> lore) {
        OptionalLong available = firstNumber("buyer.available", lore);
        if (available.isEmpty()) {
            // Единственный обязательный признак товара. Всё остальное сервер может
            // и не написать, но объём приёма есть у каждого предложения.
            return Optional.empty();
        }

        int batchSize = 0;
        long batchPrice = 0;
        for (String line : lore) {
            Optional<Matcher> batch = patterns.match("buyer.batchPrice", line);
            if (batch.isPresent()) {
                batchSize = (int) Numbers.parse(batch.get().group(1)).orElse(0);
                batchPrice = Numbers.parse(batch.get().group(2)).orElse(0);
                break;
            }
        }

        // «Цена с множителями» у обычного товара и «с учётом множителей» у особого —
        // одна регулярка. Если строки нет вовсе, итог равен начальной цене.
        long finalPrice = firstNumber("buyer.finalPrice", lore).orElse(batchPrice);

        boolean special = lore.stream()
                .anyMatch(line -> patterns.match("buyer.special", line).isPresent());

        return Optional.of(new Offer(
                name, itemId, special, available.getAsLong(),
                batchSize, batchPrice, finalPrice, parseRotation(lore)));
    }

    /**
     * Множитель торговли.
     * <p>
     * Здесь единственное место, где метка и значение стоят на разных строках:
     * «Доступно:», а стаки — следующей строкой. Поэтому идём по строкам с оглядкой
     * на предыдущую, а не разбираем каждую саму по себе.
     */
    public Optional<Multiplier> parseMultiplier(String name, List<String> lore) {
        Optional<Matcher> title = patterns.match("multiplier.name", name);
        if (title.isEmpty()) {
            return Optional.empty();
        }
        String category = title.get().group(1);

        int level = patterns.match("multiplier.level", name)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .orElse(0);

        int stacks = 0;
        boolean afterLabel = false;
        for (String line : lore) {
            if (afterLabel) {
                Optional<Matcher> value = patterns.match("multiplier.stacks", line);
                if (value.isPresent()) {
                    stacks = Integer.parseInt(value.get().group(1));
                    break;
                }
            }
            afterLabel = patterns.match("multiplier.availableLabel", line).isPresent();
        }

        return Optional.of(new Multiplier(category, level, stacks));
    }

    /**
     * Периоды ротации из справки «Полезная информация».
     * <p>
     * Сервер печатает их сам — «обычные торги обновляются раз в 6 часов», «особые —
     * раз в 8». Читаем оттуда, а не зашиваем числа: поменяют правила, мод подхватит.
     */
    public Optional<Duration> parseRotationPeriod(List<String> lore, boolean special) {
        String key = special ? "info.specialRotation" : "info.regularRotation";
        return firstMatch(key, lore)
                .map(matcher -> Duration.ofHours(Long.parseLong(matcher.group(1))));
    }

    /** Остаток до обновления ассортимента. Часы сервер пишет не всегда. */
    private Duration parseRotation(List<String> lore) {
        return firstMatch("buyer.rotation", lore)
                .map(matcher -> Duration
                        .ofHours(group(matcher, 1))
                        .plusMinutes(group(matcher, 2))
                        .plusSeconds(group(matcher, 3)))
                .orElse(null);
    }

    private static long group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0 : Long.parseLong(value);
    }

    private Optional<Matcher> firstMatch(String key, List<String> lore) {
        for (String line : lore) {
            Optional<Matcher> matcher = patterns.match(key, line);
            if (matcher.isPresent()) {
                return matcher;
            }
        }
        return Optional.empty();
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
