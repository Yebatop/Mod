package dev.yebatop.holyhelper.ui;

import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Кисти, из которых собираются экраны мода.
 * <p>
 * Клиент умеет рисовать прямоугольники и текст — всё остальное складывается из них.
 * Поэтому «скруглённый угол» здесь честно означает один срезанный пиксель, а
 * «градиент» — набор столбцов: {@code fillGradient} у клиента только вертикальный.
 * <p>
 * Ни одна кисть не знает, что именно рисует. Знание о смысле живёт в экранах,
 * здесь только форма — иначе первая же новая панель потянет за собой правку
 * всех предыдущих.
 */
public final class Paint {

    /** Ширина бегущего блика в долях панели. */
    private static final double SHEEN_WIDTH = 0.34;

    private Paint() {
    }

    /**
     * Панель: тело со срезанными углами, рамка и светлая кромка по верху.
     *
     * @param alpha общая видимость, для появления
     */
    public static void panel(DrawContext ctx, int x, int y, int w, int h, int body, double alpha) {
        if (w <= 2 || h <= 2) {
            return;
        }
        int fill = Motion.fade(body, alpha);

        ctx.fill(x + 1, y, x + w - 1, y + 1, fill);
        ctx.fill(x, y + 1, x + w, y + h - 1, fill);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, fill);

        int border = Motion.fade(Theme.BORDER, alpha);
        ctx.fill(x + 1, y, x + w - 1, y + 1, border);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        ctx.fill(x, y + 1, x + 1, y + h - 1, border);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, border);

        // Кромка идёт под рамкой, а не вместо неё: так грань читается как объём,
        // а не как вторая линия обводки.
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, Motion.fade(Theme.EDGE, alpha));
    }

    /**
     * Бегущий блик по верхней кромке панели.
     * <p>
     * Рисуется столбцами с треугольным затуханием к краям: полоса ровной яркости
     * выглядит как заплатка, а не как свет.
     */
    public static void sheen(DrawContext ctx, int x, int y, int w, long timeMillis, long period, double alpha) {
        double head = Motion.sheen(timeMillis, period);
        if (Double.isNaN(head) || w <= 0) {
            return;
        }
        int span = Math.max(2, (int) Math.round(w * SHEEN_WIDTH));
        int start = (int) Math.round(head * w);

        for (int i = 0; i < span; i++) {
            int px = start + i;
            if (px < 1 || px >= w - 1) {
                continue;
            }
            double edge = 1 - Math.abs((i / (double) span) * 2 - 1);
            int color = Motion.fade(Theme.SHEEN, 0.5 * edge * alpha);
            ctx.fill(x + px, y, x + px + 1, y + 1, color);
        }
    }

    /** Горизонтальный градиент столбцами: у клиента градиент только сверху вниз. */
    public static void gradient(DrawContext ctx, int x, int y, int w, int h, int from, int to) {
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int i = 0; i < w; i++) {
            int color = Motion.mix(from, to, w == 1 ? 1 : i / (double) (w - 1));
            ctx.fill(x + i, y, x + i + 1, y + h, color);
        }
    }

    /**
     * Полоса заполнения. Подложка рисуется всегда, заливка — на долю {@code value}.
     * Пустая подложка честнее исчезнувшей полосы: видно, что величина известна и мала.
     */
    public static void bar(DrawContext ctx, int x, int y, int w, int h,
                           double value, int from, int to, double alpha) {
        if (w <= 0 || h <= 0) {
            return;
        }
        ctx.fill(x, y, x + w, y + h, Motion.fade(Theme.TRACK, alpha));

        int filled = (int) Math.round(w * Math.max(0, Math.min(1, value)));
        if (filled <= 0) {
            return;
        }
        for (int i = 0; i < filled; i++) {
            int color = Motion.mix(from, to, w == 1 ? 1 : i / (double) (w - 1));
            ctx.fill(x + i, y, x + i + 1, y + h, Motion.fade(color, alpha));
        }
    }

    /**
     * Линия по точкам, вписанная в прямоугольник.
     * <p>
     * Соседние отсчёты соединяются вертикальным отрезком, иначе на крутых участках
     * график распадается на отдельные точки и перестаёт читаться как линия.
     * Доля {@code reveal} рисует его слева направо, как в макете.
     */
    public static void line(DrawContext ctx, int x, int y, int w, int h,
                            List<Double> values, int color, double reveal) {
        if (values == null || values.size() < 2 || w <= 1 || h <= 1) {
            return;
        }
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double span = max - min;

        int columns = (int) Math.round(w * Math.max(0, Math.min(1, reveal)));
        int previous = Integer.MIN_VALUE;

        for (int i = 0; i < columns; i++) {
            double at = i / (double) (w - 1) * (values.size() - 1);
            int index = (int) Math.floor(at);
            double frac = at - index;
            double a = values.get(Math.min(index, values.size() - 1));
            double b = values.get(Math.min(index + 1, values.size() - 1));
            double value = a + (b - a) * frac;

            // Плоский участок истории — рисуем по середине, а не по нижней кромке:
            // прижатая ко дну прямая читается как «курс упал в ноль».
            double norm = span <= 0 ? 0.5 : (value - min) / span;
            int py = y + h - 1 - (int) Math.round(norm * (h - 1));

            if (previous != Integer.MIN_VALUE && Math.abs(py - previous) > 1) {
                int top = Math.min(py, previous);
                int bottom = Math.max(py, previous);
                ctx.fill(x + i, top, x + i + 1, bottom + 1, color);
            } else {
                ctx.fill(x + i, py, x + i + 1, py + 1, color);
            }
            previous = py;
        }
    }

    /** Разделитель между секциями панели. */
    public static void separator(DrawContext ctx, int x, int y, int w, double alpha) {
        ctx.fill(x, y, x + w, y + 1, Motion.fade(Theme.BORDER, alpha));
    }

    /** Монетка: кольцо из четырёх сторон, 5×5. */
    public static void coin(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x + 1, y, x + 4, y + 1, color);
        ctx.fill(x + 1, y + 4, x + 4, y + 5, color);
        ctx.fill(x, y + 1, x + 1, y + 4, color);
        ctx.fill(x + 4, y + 1, x + 5, y + 4, color);
    }

    /** Гем: ромб, 5×5. */
    public static void gem(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x + 2, y, x + 3, y + 1, color);
        ctx.fill(x + 1, y + 1, x + 4, y + 2, color);
        ctx.fill(x, y + 2, x + 5, y + 3, color);
        ctx.fill(x + 1, y + 3, x + 4, y + 4, color);
        ctx.fill(x + 2, y + 4, x + 3, y + 5, color);
    }

    /** Жетон: шестигранник, 5×5. */
    public static void token(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x + 1, y, x + 4, y + 1, color);
        ctx.fill(x, y + 1, x + 5, y + 4, color);
        ctx.fill(x + 1, y + 4, x + 4, y + 5, color);
    }
}
