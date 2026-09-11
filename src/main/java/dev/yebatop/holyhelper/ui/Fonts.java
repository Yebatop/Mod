package dev.yebatop.holyhelper.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Гарнитуры мода.
 * <p>
 * Клиент умеет грузить настоящие TTF: шрифт лежит в {@code assets/holyhelper/font},
 * рядом с ним описание с кеглем, и дальше он доступен через стиль текста. Ресурспак
 * игроку не нужен — ассеты мода клиент подхватывает сам.
 * <p>
 * Гарнитур четыре, и это ровно те, что в макетах: Unbounded на заголовки, Onest на
 * текст и подписи, JetBrains Mono на числа. Иерархия кеглей — то, на чём держится
 * вид макета; встроенным шрифтом клиента её не собрать, он один и одного размера.
 * <p>
 * Всё создание стиля собрано здесь одним местом: тип, которым клиент задаёт шрифт,
 * меняется от версии к версии, и переписывать это должно быть правкой одной строки.
 */
public final class Fonts {

    /** Заголовки экранов. Unbounded Bold, крупно. */
    public static final Style DISPLAY = of("ui_display");

    /** Обычный текст. Onest Regular. */
    public static final Style BODY = of("ui_body");

    /** Мелкие подписи капсом. Onest SemiBold. */
    public static final Style LABEL = of("ui_label");

    /** Числа. JetBrains Mono — цифры одной ширины, колонки встают ровно. */
    public static final Style NUM = of("ui_num");

    /** Насколько раздвигать буквы в подписях капсом, в пикселях. */
    private static final int TRACKING = 1;

    /**
     * Где у гарнитуры базовая линия: на сколько ниже заданной точки садятся буквы.
     * <p>
     * Три из четырёх чисел сошлись с расчётом по файлу шрифта — подъём из таблицы
     * {@code hhea}, делённый на {@code unitsPerEm} и умноженный на кегль. Проверено
     * по снимку экрана: текст, числа и подписи встают ровно туда, куда считает
     * формула.
     * <p>
     * Крупный Unbounded выбился. По файлу выходило пятнадцать, на экране —
     * десять: клиент размещает его глифы по своей мере, а не по той, что лежит в
     * шрифте. Поэтому здесь стоит замеренное, а не вычисленное, и менять его
     * можно только новым замером.
     * <p>
     * Именно на этой разнице и разъезжалась карточка: крупное число и подпись
     * рядом с ним вставали на базовые линии, отстоящие на пять пикселей.
     */
    public static final int BASELINE_DISPLAY = 10;

    /** @see #BASELINE_DISPLAY */
    public static final int BASELINE_BODY = 9;

    /** @see #BASELINE_DISPLAY */
    public static final int BASELINE_LABEL = 7;

    /** @see #BASELINE_DISPLAY */
    public static final int BASELINE_NUM = 9;

    private Fonts() {
    }

    private static Style of(String name) {
        return Style.EMPTY.withFont(
                new StyleSpriteSource.Font(Identifier.of("holyhelper", name)));
    }

    /** Текст в заданной гарнитуре. */
    public static Text text(String value, Style font) {
        return Text.literal(value).setStyle(font);
    }

    public static int width(TextRenderer renderer, String value, Style font) {
        return renderer.getWidth(text(value, font));
    }

    /** Рисует строку и возвращает её ширину — чтобы не мерить дважды. */
    public static int draw(DrawContext ctx, TextRenderer renderer, String value, Style font,
                           int x, int y, int color) {
        Text prepared = text(value, font);
        // Тень выключена намеренно: под ней панель мода выглядела бы как ванильный
        // текст поверх мира, а он лежит на своей подложке и в тени не нуждается.
        ctx.drawText(renderer, prepared, x, y, color, false);
        return renderer.getWidth(prepared);
    }

    /**
     * Дробное число, у которого целая часть светлая, а дробная приглушена:
     * <code>8<span style="opacity:.6">.44</span></code>.
     * <p>
     * Колонка цен читается по целым — они и решают, дорого или дёшево. Копейки
     * нужны только чтобы сравнение не врало, и держать их той же яркостью
     * значит заставлять глаз каждый раз отделять важное от точного. Правая
     * граница при этом не сдвигается, так что колонка остаётся выровненной.
     *
     * @return ширина всей записи
     */
    public static int split(DrawContext ctx, TextRenderer renderer, String value,
                            int x, int y, int color, int fraction) {
        int dot = value.indexOf('.');
        if (dot < 0) {
            return draw(ctx, renderer, value, NUM, x, y, color);
        }
        int used = draw(ctx, renderer, value.substring(0, dot), NUM, x, y, color);
        return used + draw(ctx, renderer, value.substring(dot), NUM, x + used, y, fraction);
    }

    /** Та же запись, но прижатая правым краем — как и все числа в таблицах. */
    public static void splitRight(DrawContext ctx, TextRenderer renderer, String value,
                                  int right, int y, int color, int fraction) {
        split(ctx, renderer, value, right - width(renderer, value, NUM), y, color, fraction);
    }

    /** То же, но строка прижата к правому краю отрезка. */
    public static void drawRight(DrawContext ctx, TextRenderer renderer, String value, Style font,
                                 int right, int y, int color) {
        Text prepared = text(value, font);
        ctx.drawText(renderer, prepared, right - renderer.getWidth(prepared), y, color, false);
    }

    /**
     * Подпись капсом с разрядкой.
     * <p>
     * Трекинг — половина того, за счёт чего мелкая подпись в макете читается как
     * подпись, а не как обрезанный текст. Шрифт его не задаёт, поэтому буквы
     * расставляются вручную.
     */
    public static int label(DrawContext ctx, TextRenderer renderer, String value,
                            int x, int y, int color) {
        String caps = value.toUpperCase(java.util.Locale.ROOT);
        int cursor = x;
        for (int i = 0; i < caps.length(); i++) {
            Text glyph = text(String.valueOf(caps.charAt(i)), LABEL);
            ctx.drawText(renderer, glyph, cursor, y, color, false);
            cursor += renderer.getWidth(glyph) + TRACKING;
        }
        return cursor - x - TRACKING;
    }

    /** Ширина подписи капсом с той же разрядкой, что и при рисовании. */
    public static int labelWidth(TextRenderer renderer, String value) {
        String caps = value.toUpperCase(java.util.Locale.ROOT);
        int total = 0;
        for (int i = 0; i < caps.length(); i++) {
            total += renderer.getWidth(text(String.valueOf(caps.charAt(i)), LABEL)) + TRACKING;
        }
        return Math.max(0, total - TRACKING);
    }
}
