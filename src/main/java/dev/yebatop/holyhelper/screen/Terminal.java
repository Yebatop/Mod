package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.ui.Card;
import dev.yebatop.holyhelper.ui.Fonts;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Surface;
import dev.yebatop.holyhelper.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Рама со вкладками, в которой живут все экраны мода.
 * <p>
 * Раньше экран Скупца сам рисовал себе карточку. Пока экран один, это незаметно;
 * на третьем расходится неизбежно — где-то другое скругление, где-то отступ, — и
 * замечаешь это не сразу, а через месяц, когда уже не помнишь, что было эталоном.
 * Рама здесь одна на всех, а вкладки только наполняют её содержимым.
 * <p>
 * Пустых вкладок нет намеренно. Вкладка появляется тогда, когда за ней есть что
 * показать: «скоро» на видном месте — это обещание, которое интерфейс даёт вместо
 * автора, и выполнять его потом приходится не ему.
 * <p>
 * Рама ничего не знает про то, что внутри вкладок, а вкладки — про то, что снаружи.
 * Поэтому новая вкладка это один новый файл и одна строчка в списке.
 */
public final class Terminal {

    /** Высота полосы вкладок вместе с чертой под активной. */
    private static final int TABS = 13;

    /** Поле по бокам имени на вкладке. */
    private static final int TAB_PAD = 7;

    /** Просвет между вкладками. */
    private static final int TAB_GAP = 2;

    private final List<Panel> panels;
    private int active;
    private long openedAt = System.currentTimeMillis();

    /** Где нарисована каждая вкладка — заполняется при отрисовке, читается при щелчке. */
    private int tabsTop;
    private int[] tabEdges;

    public Terminal(List<Panel> panels) {
        if (panels == null || panels.isEmpty()) {
            throw new IllegalArgumentException("терминал без вкладок бессмыслен");
        }
        this.panels = List.copyOf(panels);
        this.tabEdges = new int[this.panels.size() + 1];
    }

    /** Открыт заново — сборка идёт с начала, вкладка остаётся та же. */
    public void open() {
        openedAt = System.currentTimeMillis();
        current().open();
    }

    public Panel current() {
        return panels.get(active);
    }

    public void scroll(double vertical) {
        current().scroll(vertical);
    }

    /**
     * Следующая или предыдущая вкладка.
     * <p>
     * По кругу: на последней «дальше» возвращает к первой. Упереться в край и не
     * понять, кончились вкладки или заело, — худшее из двух.
     */
    public void step(int direction) {
        int next = Math.floorMod(active + direction, panels.size());
        select(next);
    }

    public void select(int index) {
        if (index < 0 || index >= panels.size() || index == active) {
            return;
        }
        active = index;
        openedAt = System.currentTimeMillis();
        current().open();
    }

    /**
     * Щелчок по полосе вкладок.
     *
     * @param x положение курсора в единицах мода
     * @return переключились ли — чтобы вызывающий знал, съеден ли щелчок
     */
    public boolean click(int x, int y) {
        if (y < tabsTop || y > tabsTop + TABS) {
            return false;
        }
        for (int i = 0; i < panels.size(); i++) {
            if (x >= tabEdges[i] && x < tabEdges[i + 1]) {
                select(i);
                return true;
            }
        }
        return false;
    }

    /** Рисует раму во всё отведённое место. Размеры — в пикселях экрана. */
    public void render(DrawContext ctx, TextRenderer font, int screenWidth, int screenHeight,
                       int margin) {
        Surface.open(ctx);
        try {
            int width = Surface.units(screenWidth);
            int height = Surface.units(screenHeight);
            Panel panel = current();
            Card.draw(ctx, font, margin, margin, width - margin * 2, height - margin * 2,
                    panel.title(), panel.subtitle(), panel.accent(),
                    System.currentTimeMillis() - openedAt,
                    panel::body, this::header, panel::footer);
        } finally {
            Surface.close(ctx);
        }
    }

    /**
     * Шапка: вкладки слева, своё панели — справа от них.
     * <p>
     * Ширину панель получает уже за вычетом вкладок, поэтому её содержимое не
     * может на них наехать, сколько бы вкладок ни появилось.
     */
    private void header(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                        double alpha) {
        // Вкладки идут под заголовком: заголовок называет открытую вкладку, и
        // ставить их выше значило бы дважды говорить одно и то же.
        tabsTop = y + height - TABS;
        int cursor = x;

        for (int i = 0; i < panels.size(); i++) {
            Panel panel = panels.get(i);
            String name = panel.tab();
            int tabWidth = Fonts.labelWidth(font, name) + TAB_PAD * 2;
            tabEdges[i] = cursor;

            boolean open = i == active;
            if (open) {
                Paint.roundRect(ctx, cursor, tabsTop, tabWidth, TABS - 2, 3,
                        Motion.fade(Theme.SORTED, alpha));
            }
            Fonts.label(ctx, font, name, cursor + TAB_PAD, tabsTop + 2,
                    Motion.fade(open ? panel.accent() : Theme.TEXT_FAINT, alpha));
            if (open) {
                // Черта под открытой вкладкой цветом самой вкладки: по ней видно
                // не только где мы, но и к чему относится экран.
                Paint.roundRect(ctx, cursor + 2, tabsTop + TABS - 2, tabWidth - 4, 1, 0,
                        Motion.fade(panel.accent(), alpha));
            }
            cursor += tabWidth + TAB_GAP;
        }
        tabEdges[panels.size()] = cursor - TAB_GAP;

        int taken = cursor - x;
        if (width - taken > 0) {
            current().header(ctx, font, cursor, y, width - taken, height, alpha);
        }
    }
}
