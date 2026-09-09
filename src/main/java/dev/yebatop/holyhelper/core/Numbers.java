package dev.yebatop.holyhelper.core;

import java.util.OptionalLong;

/**
 * Разбор чисел из игровых строк.
 * <p>
 * Сервер печатает разряды по-разному на разных экранах: Биржа через запятую
 * ({@code 11,252}), Этапы через пробел ({@code 1 000}), «Доступно к торговле: 16384»
 * вообще без разделителя. Поэтому единственный надёжный способ — выкинуть из строки
 * всё, что не цифра, а не подбирать формат.
 * <p>
 * Дробных величин сервер не показывает: цена за штуку получается делением уже у нас.
 */
public final class Numbers {

    private Numbers() {
    }

    /** Вытаскивает целое из строки, игнорируя любые разделители разрядов и значки валют. */
    public static OptionalLong parse(String raw) {
        if (raw == null) {
            return OptionalLong.empty();
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.isEmpty() || digits.length() > 18) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(digits.toString()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Сходится ли равенство с точностью до округления.
     * <p>
     * Нужно для дешёвой самопроверки разбора: у заявки Биржи
     * {@code Доступно / Курс = Ожидается}, у окна создания лота
     * {@code Вы отдадите / Вы получите = Курс}. Если не сходится — разобрали
     * неправильно, и запись надо выбросить, а не класть в базу.
     */
    /**
     * Русское окончание по числу: «1 позиция», «2 позиции», «8 позиций».
     * <p>
     * Мелочь, но её отсутствие видно сразу: «8 позиция» в подсказке читается как
     * недоделка, а подсказок мод показывает много.
     */
    public static String plural(long value, String one, String few, String many) {
        long tail = Math.abs(value) % 100;
        long last = tail % 10;
        if (tail >= 11 && tail <= 14) {
            return many;
        }
        if (last == 1) {
            return one;
        }
        return last >= 2 && last <= 4 ? few : many;
    }

    /** То же, но сразу с числом: {@code «8 позиций»}. */
    public static String counted(long value, String one, String few, String many) {
        return value + " " + plural(value, one, few, many);
    }

    public static boolean divisionHolds(long dividend, long divisor, long expected) {
        if (divisor == 0) {
            return false;
        }
        double actual = (double) dividend / divisor;
        return Math.abs(actual - expected) <= 1.0;
    }
}
