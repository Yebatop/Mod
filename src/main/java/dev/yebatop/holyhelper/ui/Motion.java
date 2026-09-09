package dev.yebatop.holyhelper.ui;

/**
 * Арифметика анимаций: сглаживание, появление с задержкой, смешивание цветов.
 * <p>
 * Классов Minecraft здесь нет намеренно. Всё, что можно посчитать без игры,
 * считается без игры — и проверяется тестами, а не глазами на живом сервере.
 * <p>
 * Кривая та же, что в макетах: {@code cubic-bezier(0.2, 0.72, 0.2, 1)}. Она даёт
 * быстрый старт и долгое затухание — движение читается как «встало на место»,
 * а не как «доехало».
 */
public final class Motion {

    /** Контрольные точки кривой из макетов. */
    private static final double X1 = 0.2;
    private static final double Y1 = 0.72;
    private static final double X2 = 0.2;
    private static final double Y2 = 1.0;

    private Motion() {
    }

    /**
     * Значение кубической кривой Безье с концами в 0 и 1.
     * Точки {@code p1} и {@code p2} — управляющие.
     */
    static double bezier(double p1, double p2, double t) {
        double u = 1 - t;
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t;
    }

    /**
     * Сглаживание доли пути.
     * <p>
     * Кривая задана параметрически, поэтому по x приходится искать t. Делим отрезок
     * пополам: производная тут не нужна, а тридцати шагов хватает с запасом — ошибка
     * меньше одной миллиардной, то есть заведомо меньше пикселя.
     */
    public static double ease(double progress) {
        if (progress <= 0) {
            return 0;
        }
        if (progress >= 1) {
            return 1;
        }
        double low = 0;
        double high = 1;
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) / 2;
            if (bezier(X1, X2, mid) < progress) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return bezier(Y1, Y2, (low + high) / 2);
    }

    /**
     * Доля появления элемента: 0 — ещё не начал, 1 — уже на месте.
     *
     * @param elapsed сколько прошло с начала сборки экрана, мс
     * @param delay   когда элементу начинать, мс
     * @param length  сколько длится его появление, мс
     */
    public static double reveal(long elapsed, long delay, long length) {
        if (length <= 0) {
            return elapsed >= delay ? 1 : 0;
        }
        return ease((elapsed - delay) / (double) length);
    }

    /**
     * Насколько сдвинуть элемент, который ещё не доехал.
     * Возвращает смещение в пикселях: {@code distance} в начале, 0 в конце.
     */
    public static int rise(double reveal, int distance) {
        return (int) Math.round(distance * (1 - reveal));
    }

    /** Умножает альфу цвета. Нужен для появления: цвет тот же, видно его меньше. */
    public static int fade(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * clamp(alpha));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Смешивает два цвета покомпонентно, включая альфу. */
    public static int mix(int from, int to, double t) {
        double k = clamp(t);
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF;
            int b = (to >>> shift) & 0xFF;
            out |= ((int) Math.round(a + (b - a) * k)) << shift;
        }
        return out;
    }

    /**
     * Положение бегущего блика по верхней кромке панели, в долях ширины.
     * <p>
     * Блик проходит панель за первую часть цикла и потом ждёт: в макете это
     * {@code 0% → 44%} движения, дальше пауза. Возвращает {@code NaN}, пока пауза
     * идёт, — рисовать в это время нечего.
     */
    public static double sheen(long timeMillis, long periodMillis) {
        if (periodMillis <= 0) {
            return Double.NaN;
        }
        double phase = Math.floorMod(timeMillis, periodMillis) / (double) periodMillis;
        if (phase > 0.44) {
            return Double.NaN;
        }
        return -0.38 + (phase / 0.44) * 1.76;
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : value > 1 ? 1 : value;
    }
}
