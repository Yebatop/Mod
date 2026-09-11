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

    /**
     * Размер мелкого значка. Пять пикселей — столько же, сколько занимали старые,
     * сложенные из прямоугольников: место в разметке не меняется, меняется только
     * то, из чего значок сделан.
     */
    private static final int GLYPH = 5;

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
        int density = Surface.density();
        Surface.enter(ctx, density);
        rows(ctx, x * density, y * density, w * density, h * density, radius * density,
                top, bottom);
        Surface.leave(ctx);
    }

    /**
     * Тело скруглённого прямоугольника, строка за строкой, уже в пикселях экрана.
     * <p>
     * У каждой строки свой отступ от края, посчитанный по окружности. Дробная
     * часть отступа рисуется отдельным пикселем с уменьшенной прозрачностью —
     * это и даёт мягкий угол вместо ступенек. Чем мельче сетка, тем больше
     * ступеней помещается, и тем ближе угол к настоящей дуге.
     * <p>
     * Прямая середина заливается одним прямоугольником, а не строками: на мелкой
     * сетке панель высотой в сотню логических пикселей дала бы четыреста заливок
     * там, где хватает одной.
     */
    private static void rows(DrawContext ctx, int x, int y, int w, int h, int r,
                             int top, int bottom) {
        int limit = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        boolean flat = top == bottom;

        int straightFrom = limit;
        int straightTo = h - limit;
        if (flat && straightTo > straightFrom) {
            ctx.fill(x, y + straightFrom, x + w, y + straightTo, top);
        }

        for (int row = 0; row < h; row++) {
            if (flat && row >= straightFrom && row < straightTo) {
                continue;
            }
            int color = flat
                    ? top
                    : Motion.mix(top, bottom, h == 1 ? 0 : row / (double) (h - 1));
            double inset = insetAt(row, h, limit);
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

        int density = Surface.density();
        Surface.enter(ctx, density);
        int fx = x * density;
        int fy = y * density;
        int fw = w * density;
        int fh = h * density;
        int fr = radius * density;

        int border = Motion.fade(Theme.BORDER_SOLID, alpha);
        rows(ctx, fx, fy, fw, fh, fr, border, border);

        // Рамка толщиной в один пиксель монитора, а не в один логический. Именно
        // её жирность и делала панель похожей на ванильную менюшку: при масштабе
        // интерфейса 2 «пиксель» рамки занимал два пикселя экрана.
        rows(ctx, fx + 1, fy + 1, fw - 2, fh - 2, Math.max(0, fr - 1),
                Motion.fade(Motion.lighten(body, Theme.LIT_SHARE), alpha),
                Motion.fade(body, alpha));

        // Кромка идёт внутри тела и не доходит до углов: свет по грани, а не вторая
        // линия обводки.
        int edgeInset = Math.max(2 * density, fr);
        ctx.fill(fx + edgeInset, fy + 1, fx + fw - edgeInset, fy + 2,
                Motion.fade(Theme.EDGE, alpha));
        Surface.leave(ctx);
    }

    /**
     * Цветная грань у левого края панели — то, чем в макетах различаются карточки:
     * золото у Скупца, бирюза у Маркета.
     */
    public static void accent(DrawContext ctx, int x, int y, int h, int color, double alpha) {
        int density = Surface.density();
        Surface.enter(ctx, density);
        int painted = Motion.fade(color, alpha);
        rows(ctx, x * density + 1, (y + 2) * density, 3 * density, (h - 4) * density,
                density, painted, painted);
        Surface.leave(ctx);
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
        int density = Surface.density();
        Surface.enter(ctx, density);
        int fx = x * density;
        int fy = y * density;
        int fw = w * density;

        int span = Math.max(2, (int) Math.round(fw * SHEEN_WIDTH));
        int start = (int) Math.round(head * fw);
        int guard = Math.max(density, radius * density);

        for (int i = 0; i < span; i++) {
            int px = start + i;
            if (px < guard || px >= fw - guard) {
                continue;
            }
            double edge = 1 - Math.abs((i / (double) span) * 2 - 1);
            ctx.fill(fx + px, fy + 1, fx + px + 1, fy + 2,
                    Motion.fade(Theme.SHEEN, 0.55 * edge * alpha));
        }
        Surface.leave(ctx);
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
        int density = Surface.density();
        Surface.enter(ctx, density);
        int fx = x * density;
        int fy = y * density;
        int fw = w * density;
        int fh = h * density;
        int radius = fh / 2;

        int track = Motion.fade(Theme.TRACK, alpha);
        rows(ctx, fx, fy, fw, fh, radius, track, track);

        int filled = (int) Math.round(fw * Math.max(0, Math.min(1, value)));
        if (filled > 0) {
            // Цвет шагает отрезками: на глаз это тот же градиент, а клиенту
            // вчетверо меньше четырёхугольников. Шаг растёт вместе с сеткой,
            // иначе на мелкой сетке отрезков стало бы вчетверо больше.
            int step = 4 * density;
            for (int row = 0; row < fh; row++) {
                int inset = (int) Math.round(insetAt(row, fh, radius));
                int left = fx + inset;
                int right = Math.min(fx + filled, fx + fw - inset);
                for (int px = left; px < right; px += step) {
                    int chunk = Math.min(step, right - px);
                    double at = fw == 1 ? 1 : (px - fx) / (double) (fw - 1);
                    ctx.fill(px, fy + row, px + chunk, fy + row + 1,
                            Motion.fade(Motion.mix(from, to, at), alpha));
                }
            }
        }
        Surface.leave(ctx);
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

        int density = Surface.density();
        Surface.enter(ctx, density);
        int fx = x * density;
        int fy = y * density;
        int fw = w * density;
        int fh = h * density;

        int columns = (int) Math.round(fw * Math.max(0, Math.min(1, reveal)));
        double previous = Double.NaN;

        for (int i = 0; i < columns; i++) {
            double at = i / (double) (fw - 1) * (values.size() - 1);
            int index = (int) Math.floor(at);
            double frac = at - index;
            double a = values.get(Math.min(index, values.size() - 1));
            double b = values.get(Math.min(index + 1, values.size() - 1));
            double value = a + (b - a) * frac;

            // Плоский участок истории рисуем по середине, а не по нижней кромке:
            // прижатая ко дну прямая читается как «курс упал в ноль».
            double norm = span <= 0 ? 0.5 : (value - min) / span;
            // Дробную высоту не округляем: округление и делало из линии лесенку.
            double py = fy + fh - 1 - norm * (fh - 1);

            column(ctx, fx + i, Double.isNaN(previous) ? py : previous, py, color);
            previous = py;
        }
        Surface.leave(ctx);
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
        int density = Surface.density();
        Surface.enter(ctx, density);
        // Волосяная линия: один пиксель монитора. В логических пикселях она была
        // бы вдвое жирнее и читалась разделителем таблицы, а не тенью между
        // секциями.
        ctx.fill(x * density, y * density, (x + w) * density, y * density + 1,
                Motion.fade(Theme.BORDER, alpha));
        Surface.leave(ctx);
    }

    /** Метка особого товара: четырёхлучевая искра. В шрифтах такого знака нет. */
    public static void spark(DrawContext ctx, int x, int y, int color) {
        Sprites.glyph(ctx, Sprites.Glyph.SPARK, x, y, GLYPH, color);
    }

    /** Монетка: кольцо, 5×5. */
    public static void coin(DrawContext ctx, int x, int y, int color) {
        Sprites.glyph(ctx, Sprites.Glyph.COIN, x, y, GLYPH, color);
    }

    /** Гем: ромб, 5×5. */
    public static void gem(DrawContext ctx, int x, int y, int color) {
        Sprites.glyph(ctx, Sprites.Glyph.GEM, x, y, GLYPH, color);
    }

    /** Жетон: шестигранник, 5×5. */
    public static void token(DrawContext ctx, int x, int y, int color) {
        Sprites.glyph(ctx, Sprites.Glyph.TOKEN, x, y, GLYPH, color);
    }
}
