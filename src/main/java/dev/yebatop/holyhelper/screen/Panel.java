package dev.yebatop.holyhelper.screen;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Одна вкладка терминала.
 * <p>
 * Панель не знает ни про раму, ни про вкладки, ни про то, открыта она сейчас или
 * нет. Она умеет одно: нарисовать себя в отведённый прямоугольник. Всё остальное —
 * скругление, тень, заголовок, переключение — делает {@link Terminal}, и делает
 * одинаково для всех.
 * <p>
 * Ради этого разделения терминал и затевался. Пока каждый экран сам рисовал себе
 * раму, третий по счёту неизбежно получил бы другое скругление или другой отступ,
 * и разъехались бы они не сразу, а через месяц, когда уже не вспомнить, где эталон.
 */
public interface Panel {

    /** Короткое имя на вкладке. */
    String tab();

    /** Заголовок в шапке, когда вкладка открыта. */
    String title();

    /** Строка под заголовком: обычно возраст снимка и объём того, что показано. */
    String subtitle();

    /** Цвет вкладки и акцентов внутри неё. */
    int accent();

    /** Само содержимое. */
    void body(DrawContext ctx, TextRenderer font, int x, int y, int width, int height, double alpha);

    /**
     * Что нарисовать в шапке справа от вкладок — часы, курс, что угодно своё.
     * <p>
     * Место общее с вкладками, поэтому панель получает уже остаток ширины.
     */
    default void header(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                        double alpha) {
    }

    /** Подвал: легенда, подсказки по клавишам. */
    default void footer(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                        double alpha) {
    }

    /** Колесо мыши. */
    default void scroll(double vertical) {
    }

    /** Вкладку открыли — можно начать сборку заново. */
    default void open() {
    }
}
