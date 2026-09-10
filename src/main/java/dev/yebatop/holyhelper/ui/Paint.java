package dev.yebatop.holyhelper.ui;

import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Кисти, из которых собираются экраны мода.
 * <p>
 * Клиент рисует прямоугольниками и текстом, поэтому всё остальное складывается из
 * них. Скругление здесь настоящее: угол считается по окружности построчно, а
 * крайний пиксель гасится по доле — иначе на скруглении видна лесенка, и панель
 * читается как ванильная менюшка, а не как то, что нарисовано в макете.
 * <p>
 * Ни одна кисть не знает, что именно рисует. Знание о смысле живёт в экранах,
 * здесь только форма — иначе первая же новая панель потянет за собой правку всех
 * предыдущих.
 */
public final class Paint {

    /** Ширина бегущего блика в долях панели. */
    private static final double SHEEN_WIDTH = 0.34;

    private Paint() {
    }

    /**
     * Прямоугольник со скруглёнными углами.
     * <p>
     * Строка за строкой: у каждой свой отступ от края, посчитанный по окружности.
     * Дробная часть отступа рисуется отдельным пикселем с уменьшенной альфой —
     * это и даёт мягкий угол вместо ступенек.
     */
    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        roundRect(ctx, x, y, w, h, radius, color, color);
    }

    /**
     * То же скругление, но тело залито сверху вниз от одного цвета к другому.
     * <p>
     * Ровная заливка читается как бумага: у неё нет верха и низа. Панель в макете
     * чуть светлее по верхнему краю — этого хватает, чтобы она читалась телом, на
     * которое падает свет, а не вырезанным прямоугольником.
     */
    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int radius,
                                 int top, int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));

        for (int row = 0; row < h; row++) {
            int color = top == bottom
                    ? top
                    : Motion.mix(top, bottom, h == 1 ? 0 : row / (double) (h - 1));
            double inset = insetAt(row, h, r);
            int whole = (int) Math.floor(inset);
            double frac = inset - whole;

            int left = x + whole;
            int right = x + w - whole;
            if (right - left <= 0) {
                continue;
            }
            if (frac > 0.02 && right - left > 2) {
                int soft = Motion.fade(color, alphaOf(color) * (1 - frac));
                ctx.fill(left, y + row, left + 1, y + row + 1, soft);
                ctx.fill(right - 1, y + row, right, y + row + 1, soft);
                left++;
                right--;
            }
            ctx.fill(left, y + row, right, y + row + 1, color);
        }
    }

    /** Отступ строки от края скруглённого прямоугольника. */
    private static double insetAt(int row, int h, int r) {
        if (r <= 0) {
            return 0;
        }
        double center = row + 0.5;
        double dy;
        if (center < r) {
            dy = r - center;
        } else if (center > h - r) {
            dy = center - (h - r);
        } else {
            return 0;
        }
        return r - Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    private static double alphaOf(int argb) {
        return ((argb >>> 24) & 0xFF) / 255.0;
    }

    /**
     * Панель: скруглённое тело, рамка в пиксель и светлая кромка по верху.
     * <p>
     * Рамка рисуется как скруглённый прямоугольник под телом, а тело — на пиксель
     * внутрь. Так рамка повторяет скругление и не расходится с ним на углах.
     */
    public static void panel(DrawContext ctx, int x, int y, int w, int h, int radius,
                             int body, double alpha) {
        if (w <= 2 || h <= 2) {
            return;
        }
        // Тень рисуется первой и из картинки: без неё панель лежит на подложке
        // вплотную, и в макете именно тень отделяет одно от другого.
        Sprites.shadow(ctx, x, y, w, h, alpha);

        roundRect(ctx, x, y, w, h, radius, Motion.fade(Theme.BORDER_SOLID, alpha));
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1),
                Motion.fade(Motion.lighten(body, Theme.LIT_SHARE), alpha),
                Motion.fade(body, alpha));

        // Кромка идёт внутри тела и не доходит до углов: свет по грани, а не вторая
        // линия обводки.
        int edgeInset = Math.max(2, radius);
        ctx.fill(x + edgeInset, y + 1, x + w - edgeInset, y + 2, Motion.fade(Theme.EDGE, alpha));
    }

    /**
     * Цветная грань у левого края панели — то, чем в макетах различаются карточки:
     * золото у Скупца, бирюза у Маркета.
     */
    public static void accent(DrawContext ctx, int x, int y, int h, int color, double alpha) {
        roundRect(ctx, x + 1, y + 2, 3, h - 4, 1, Motion.fade(color, alpha));
    }

    /**
     * Бегущий блик по верхней кромке панели.
     * <p>
     * Столбцами с треугольным затуханием к краям: полоса ровной яркости выглядит
     * как заплатка, а не как свет.
     */
    public static void sheen(DrawContext ctx, int x, int y, int w, int radius,
                             long timeMillis, long period, double alpha) {
        double head = Motion.sheen(timeMillis, period);
        if (Double.isNaN(head) || w <= 0) {
            return;
        }
        int span = Math.max(2, (int) Math.round(w * SHEEN_WIDTH));
        int start = (int) Math.round(head * w);
        int guard = Math.max(1, radius);

        for (int i = 0; i < span; i++) {
            int px = start + i;
            if (px < guard || px >= w - guard) {
                continue;
            }
            double edge = 1 - Math.abs((i / (double) span) * 2 - 1);
            ctx.fill(x + px, y + 1, x + px + 1, y + 2,
                    Motion.fade(Theme.SHEEN, 0.55 * edge * alpha));
        }
    }


    /**
     * Полоса заполнения со скруглёнными концами. Подложка рисуется всегда: пустая
     * подложка честнее исчезнувшей полосы — видно, что величина известна и мала.
     */
    public static void bar(DrawContext ctx, int x, int y, int w, int h,
                           double value, int from, int to, double alpha) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int radius = h / 2;
        roundRect(ctx, x, y, w, h, radius, Motion.fade(Theme.TRACK, alpha));

        int filled = (int) Math.round(w * Math.max(0, Math.min(1, value)));
        if (filled <= 0) {
            return;
        }
        // Цвет шагает отрезками по четыре пикселя: на глаз это тот же градиент,
        // а клиенту вчетверо меньше четырёхугольников.
        int step = 4;
        for (int row = 0; row < h; row++) {
            int inset = (int) Math.round(insetAt(row, h, radius));
            int left = x + inset;
            int right = Math.min(x + filled, x + w - inset);
            for (int px = left; px < right; px += step) {
                int chunk = Math.min(step, right - px);
                double at = w == 1 ? 1 : (px - x) / (double) (w - 1);
                ctx.fill(px, y + row, px + chunk, y + row + 1,
                        Motion.fade(Motion.mix(from, to, at), alpha));
            }
        }
    }


    /**
     * Линия по точкам, вписанная в прямоугольник.
     * <p>
     * Соседние отсчёты соединяются отрезком, иначе на крутых участках график
     * распадается на точки. Доля {@code reveal} рисует его слева направо.
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
        double previous = Double.NaN;

        for (int i = 0; i < columns; i++) {
            double at = i / (double) (w - 1) * (values.size() - 1);
            int index = (int) Math.floor(at);
            double frac = at - index;
            double a = values.get(Math.min(index, values.size() - 1));
            double b = values.get(Math.min(index + 1, values.size() - 1));
            double value = a + (b - a) * frac;

            // Плоский участок истории рисуем по середине, а не по нижней кромке:
            // прижатая ко дну прямая читается как «курс упал в ноль».
            double norm = span <= 0 ? 0.5 : (value - min) / span;
            // Дробную высоту не округляем: округление и делало из линии лесенку.
            double py = y + h - 1 - norm * (h - 1);

            column(ctx, x + i, Double.isNaN(previous) ? py : previous, py, color);
            previous = py;
        }
    }

    /**
     * Столбец линии между двумя высотами, со сглаженными концами.
     * <p>
     * Целые строки заливаются полностью, а крайние — по доле, на которую линия
     * в них заходит. Это то же самое, чем держится скругление панели, и по той же
     * причине: без дробной кромки пологий график распадается на ступеньки, а
     * ступеньки на графике читаются как скачки курса, которых не было.
     */
    private static void column(DrawContext ctx, int x, double from, double to, int color) {
        double lo = Math.min(from, to);
        double hi = Math.max(from, to);

        int first = (int) Math.floor(lo);
        int last = (int) Math.floor(hi);

        if (first == last) {
            // Линия целиком внутри одной строки: чернила делятся между ней и
            // соседней по тому, насколько линия смещена от центра.
            double shift = lo - first;
            ctx.fill(x, first, x + 1, first + 1, Motion.fade(color, 1 - shift));
            ctx.fill(x, first + 1, x + 1, first + 2, Motion.fade(color, shift));
            return;
        }
        ctx.fill(x, first, x + 1, first + 1, Motion.fade(color, 1 - (lo - first)));
        if (last > first + 1) {
            ctx.fill(x, first + 1, x + 1, last, color);
        }
        ctx.fill(x, last, x + 1, last + 1, Motion.fade(color, hi - last));
    }

    /** Разделитель между секциями панели. */
    public static void separator(DrawContext ctx, int x, int y, int w, double alpha) {
        ctx.fill(x, y, x + w, y + 1, Motion.fade(Theme.BORDER, alpha));
    }

    /** Метка особого товара: четырёхлучевая искра. В шрифтах такого знака нет. */
    public static void spark(DrawContext ctx, int x, int y, int color) {
        ctx.fill(x + 2, y, x + 3, y + 5, color);
        ctx.fill(x, y + 2, x + 5, y + 3, color);
        ctx.fill(x + 1, y + 1, x + 2, y + 2, color);
        ctx.fill(x + 3, y + 1, x + 4, y + 2, color);
        ctx.fill(x + 1, y + 3, x + 2, y + 4, color);
        ctx.fill(x + 3, y + 3, x + 4, y + 4, color);
    }

    /** Монетка: кольцо, 5×5. */
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
