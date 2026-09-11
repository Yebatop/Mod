package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Numbers;
import dev.yebatop.holyhelper.core.Patterns;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;

/**
 * Разбор лотов Маркета. Классов Minecraft здесь нет — на вход идут готовые строки.
 * <p>
 * Маркет отличается от Скупца тем, что он <b>отсортирован по выбору игрока</b>.
 * Это не косметика, а свойство данных: под сортировкой «сначала дешёвые за ед.»
 * первая страница категории — это дно рынка, а под «сначала дорогие» — потолок.
 * Считать по таким страницам медиану нельзя, поэтому режим сортировки читается
 * вместе с лотами и хранится рядом с ними.
 */
public final class MarketParser {

    private final Patterns patterns;

    public MarketParser(Patterns patterns) {
        this.patterns = patterns;
    }

    /**
     * Лот на витрине.
     *
     * @param categories категории через запятую, как их пишет сервер: один предмет
     *                   может лежать сразу в нескольких
     * @param unitPrice  цена за штуку; сервер считает её сам, и это то, что сравнимо
     *                   с ценой Скупца
     * @param count      сколько предметов лежит в лоте — размер стопки в слоте
     * @param wholeOnly  лот продаётся только целиком; тогда цену за штуку сервер
     *                   не считает, и мод делит сам
     * @param expiresIn  сколько лоту осталось висеть; {@code null}, если не написано
     */
    public record Lot(
            String name,
            String itemId,
            List<String> categories,
            String seller,
            long price,
            long unitPrice,
            int count,
            boolean wholeOnly,
            Duration expiresIn) {

        /** Сколько штук в лоте, по отношению цены к цене за единицу. */
        public long quantity() {
            return unitPrice <= 0 ? 0 : price / unitPrice;
        }

        /**
         * Сходятся ли три числа лота между собой.
         * <p>
         * Цена лота, цена за единицу и размер стопки связаны жёстко, и это
         * бесплатная проверка разбора — та же, что уже стоит на Бирже. Если
         * равенство не держится, значит одно из чисел прочитано не оттуда, и
         * запись в базу цен пойдёт враньём: колонка «Маркет» у Скупца сравнивает
         * именно цену за штуку.
         * <p>
         * Допуск — по единице на предмет: сервер округляет цену за единицу, и на
         * стопке в 64 накопленная разница доходит до 64 монеток.
         */
        public boolean consistent() {
            if (count <= 0 || unitPrice <= 0 || price <= 0) {
                return false;
            }
            return Math.abs(price - unitPrice * (long) count) <= count;
        }
    }

    /** Страница витрины: сервер пишет её в заголовке окна и меняет по мере наполнения. */
    public record Page(int current, int total) {
    }

    /**
     * Лот из подсказки. Пусто — значит это не лот: кнопка листания, меню категорий,
     * книга справки или стекло-разделитель.
     */
    public Optional<Lot> parseLot(String name, String itemId, int count, List<String> lore) {
        OptionalLong price = firstNumber("market.price", lore);
        OptionalLong unitPrice = firstNumber("market.unitPrice", lore);
        if (price.isEmpty() || unitPrice.isEmpty()) {
            // Цена и цена за единицу есть у каждого лота и больше ни у чего в окне.
            return Optional.empty();
        }

        long lotPrice = price.getAsLong();
        long perUnit = unitPrice.getAsLong();

        // Лот, который нельзя купить поштучно, сервер подписывает ценой за единицу,
        // равной полной цене: раз единицу не купить, то и цены у неё как бы нет.
        // Сравнивать со Скупцом надо настоящую, поэтому делим сами. Совпадение цен
        // при стопке больше одной — тот же признак, только без подписи: цена за штуку,
        // равная цене за шестьдесят четыре, не бывает правдой.
        boolean wholeOnly = firstMatch("market.wholeOnly", lore).isPresent()
                || (perUnit == lotPrice && count > 1);
        if (wholeOnly && count > 0) {
            perUnit = Math.round(lotPrice / (double) count);
        }

        String seller = firstMatch("market.seller", lore).map(m -> m.group(1)).orElse("");
        List<String> categories = firstMatch("market.category", lore)
                .map(m -> splitCategories(m.group(1)))
                .orElse(List.of());

        Duration expires = firstMatch("market.timeLeft", lore)
                .map(m -> Duration
                        .ofHours(group(m, 1))
                        .plusMinutes(group(m, 2))
                        .plusSeconds(group(m, 3)))
                .orElse(null);

        return Optional.of(new Lot(
                name, itemId, categories, seller, lotPrice, perUnit, count, wholeOnly, expires));
    }

    /** Номер страницы из заголовка окна. Перечитывать при каждом заходе: всего страниц плавает. */
    public Optional<Page> parsePage(String title) {
        return patterns.match("market.screenTitle", title).map(matcher -> new Page(
                (int) Numbers.parse(matcher.group(1)).orElse(0),
                (int) Numbers.parse(matcher.group(2)).orElse(0)));
    }

    /**
     * Выбранный пункт меню — тот, что помечен галочкой.
     * <p>
     * Так читаются и текущая категория, и текущая сортировка. Без них наблюдения
     * лотов бессмысленны: неизвестно, какой именно срез рынка попал в выборку.
     */
    public Optional<String> parseActiveChoice(List<String> lore) {
        return firstMatch("market.activeChoice", lore).map(matcher -> matcher.group(1));
    }

    /** Категории сервер пишет одной строкой через запятую. */
    private static List<String> splitCategories(String value) {
        List<String> categories = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                categories.add(trimmed);
            }
        }
        return List.copyOf(categories);
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
