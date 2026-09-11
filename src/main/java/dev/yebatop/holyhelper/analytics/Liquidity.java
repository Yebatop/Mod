package dev.yebatop.holyhelper.analytics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * За сколько лоты на Маркете действительно уходят.
 * <p>
 * Мод видит только выставленные лоты, то есть цены запроса. За сколько покупают,
 * он не видит: купленный лот просто исчезает, и в этот момент никто на витрину не
 * смотрит. Поэтому «от 400 за штуку» значит «столько просили», а не «столько
 * дадут», и опираться на это, выставляя свой лот, — значит подрезать цену,
 * которая сама может висеть третьи сутки.
 * <p>
 * Но ответ всё-таки достаётся, и без всякой слежки за исчезновениями. Сервер
 * пишет у каждого лота, сколько ему осталось висеть. Значит по любому снимку
 * витрины видно не только цену, но и <b>возраст</b>: лот, у которого остался час,
 * провисел почти сутки и никому не понадобился. А лот, встреченный свежим, про
 * свою судьбу ещё ничего не говорит.
 * <p>
 * Отсюда и весь вывод. Если по дешёвым лотам попадаются только свежие, а по
 * дорогим — доживающие, значит дешёвые разбирают, а дорогие висят. Старых лотов
 * по ходовой цене не бывает: их покупают раньше, чем они успевают состариться.
 * <p>
 * Считается это по остатку, а не по возрасту, и намеренно. Возраст требует знать,
 * сколько лот живёт всего, а этого числа сервер нигде не называет — его пришлось
 * бы угадывать по наибольшему увиденному остатку и ошибаться на новых предметах.
 * Остаток же сервер печатает прямо, и для сравнения двух половин витрины между
 * собой большего не нужно: у кого остаток больше, тот моложе.
 */
public final class Liquidity {

    /**
     * Меньше этого числа лотов сравнивать половины бессмысленно: одна случайная
     * цена перевесит всё остальное, и вывод будет про неё, а не про рынок.
     */
    public static final int ENOUGH = 6;

    /**
     * Насколько половины должны разойтись по остатку, чтобы это была разница,
     * а не рябь. Два часа — примерно десятая часть суток, которые живёт лот.
     */
    private static final Duration NOTABLE = Duration.ofHours(2);

    /** Один увиденный лот: почём и сколько ему оставалось висеть. */
    public record Sample(long unitPrice, Duration remaining) {
    }

    /**
     * Половина витрины по цене.
     *
     * @param medianPrice     цена в середине половины
     * @param medianRemaining сколько в середине оставалось висеть
     * @param lots            по скольким лотам это посчитано
     */
    public record Half(long medianPrice, Duration medianRemaining, int lots) {
    }

    /**
     * Дешёвая и дорогая половины рядом.
     *
     * @param cheaper лоты дешевле середины
     * @param dearer  лоты дороже
     */
    public record Split(Half cheaper, Half dearer) {

        /**
         * Расходятся ли половины по возрасту настолько, чтобы об этом стоило
         * говорить.
         */
        public boolean notable() {
            // По модулю, а не по знаку: «дорогие заметно моложе» — тоже разница,
            // и молчать о ней значит выдавать наблюдение за его отсутствие.
            return gap().abs().compareTo(NOTABLE) >= 0;
        }

        /** Насколько дешёвые лоты моложе дорогих. Отрицательное — наоборот. */
        public Duration gap() {
            return cheaper.medianRemaining().minus(dearer.medianRemaining());
        }

        /**
         * Разбирают ли дешёвые быстрее.
         * <p>
         * У молодых лотов остаток больше. Если среди дешёвых встречаются только
         * молодые, а среди дорогих попадаются доживающие — значит дешёвые
         * покупают, а дорогие висят.
         */
        public boolean cheapMovesFaster() {
            return notable() && !gap().isNegative();
        }
    }

    private Liquidity() {
    }

    /**
     * Делит наблюдения пополам по цене и смотрит, чем половины отличаются
     * по остатку.
     *
     * @return пусто, если лотов слишком мало или все они одной цены — делить
     *         тогда нечего, и любой вывод был бы выдуман
     */
    public static Optional<Split> split(List<Sample> samples) {
        if (samples == null || samples.size() < ENOUGH) {
            return Optional.empty();
        }
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(Sample::unitPrice));

        int middle = sorted.size() / 2;
        List<Sample> cheaper = sorted.subList(0, middle);
        List<Sample> dearer = sorted.subList(sorted.size() - middle, sorted.size());

        // Все лоты по одной цене — половины получились бы одинаковыми, и разница
        // между ними означала бы только разброс возраста, а не связь с ценой.
        if (medianPrice(cheaper) == medianPrice(dearer)) {
            return Optional.empty();
        }
        return Optional.of(new Split(half(cheaper), half(dearer)));
    }

    private static Half half(List<Sample> samples) {
        return new Half(medianPrice(samples), medianRemaining(samples), samples.size());
    }

    private static long medianPrice(List<Sample> samples) {
        long[] prices = samples.stream().mapToLong(Sample::unitPrice).sorted().toArray();
        return prices[prices.length / 2];
    }

    private static Duration medianRemaining(List<Sample> samples) {
        long[] millis = samples.stream()
                .mapToLong(sample -> sample.remaining().toMillis())
                .sorted()
                .toArray();
        return Duration.ofMillis(millis[millis.length / 2]);
    }
}
