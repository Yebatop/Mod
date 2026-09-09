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
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));

        for (int row = 0; row < h; row++) {
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
        roundRect(ctx, x, y, w, h, radius, Motion.fade(Theme.BORDER_SOLID, alpha));
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), Motion.fade(body, alpha));

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
        roundRect(ctx, x + 1, y + 2, 2, h - 4, 1, Motion.fade(color, alpha));
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

    /** Горизонтальный градиент столбцами: у клиента градиент только сверху вниз. */
    public static void gradient(DrawContext ctx, int x, int y, int w, int h, int from, int to) {
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int i = 0; i < w; i++) {
            ctx.fill(x + i, y, x + i + 1, y + h,
                    Motion.mix(from, to, w == 1 ? 1 : i / (double) (w - 1)));
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

    /** Отрезок между двумя точками. Брезенхэм: без него косые линии рассыпаются. */
    public static void stroke(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        int guard = dx - dy + 4;

        while (guard-- > 0) {
            ctx.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                x0 += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += sy;
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
        int previous = Integer.MIN_VALUE;

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
            int py = y + h - 1 - (int) Math.round(norm * (h - 1));

            if (previous != Integer.MIN_VALUE && Math.abs(py - previous) > 1) {
                ctx.fill(x + i, Math.min(py, previous), x + i + 1, Math.max(py, previous) + 1, color);
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

    /**
     * Знак мода: изометрический блок с растущей линией внутри.
     * <p>
     * Задан долями квадрата и рисуется отрезками, поэтому одинаково собирается
     * и на двенадцати пикселях в панели, и на полусотне на экране запуска.
     */
    public static void mark(DrawContext ctx, int x, int y, int size, int edge, int accent, double alpha) {
        int gold = Motion.fade(edge, alpha);
        int teal = Motion.fade(accent, alpha);

        double[][] hex = {{0.50, 0.04}, {0.96, 0.29}, {0.96, 0.71},
                          {0.50, 0.96}, {0.04, 0.71}, {0.04, 0.29}};
        for (int i = 0; i < hex.length; i++) {
            double[] a = hex[i];
            double[] b = hex[(i + 1) % hex.length];
            stroke(ctx, px(x, size, a[0]), px(y, size, a[1]), px(x, size, b[0]), px(y, size, b[1]), gold);
        }
        // Три ребра внутрь — от них блок читается объёмным, а не шестиугольником.
        stroke(ctx, px(x, size, 0.04), px(y, size, 0.29), px(x, size, 0.50), px(y, size, 0.52), gold);
        stroke(ctx, px(x, size, 0.96), px(y, size, 0.29), px(x, size, 0.50), px(y, size, 0.52), gold);
        stroke(ctx, px(x, size, 0.50), px(y, size, 0.52), px(x, size, 0.50), px(y, size, 0.96), gold);

        double[][] chart = {{0.24, 0.64}, {0.42, 0.50}, {0.58, 0.58}, {0.80, 0.34}};
        for (int i = 0; i < chart.length - 1; i++) {
            stroke(ctx, px(x, size, chart[i][0]), px(y, size, chart[i][1]),
                    px(x, size, chart[i + 1][0]), px(y, size, chart[i + 1][1]), teal);
        }
    }

    private static int px(int origin, int size, double fraction) {
        return origin + (int) Math.round(fraction * (size - 1));
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
