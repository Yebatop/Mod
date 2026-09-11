package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.Liquidity;
import dev.yebatop.holyhelper.store.PriceStore;
import dev.yebatop.holyhelper.ui.Card;
import dev.yebatop.holyhelper.ui.Fonts;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Всё, что мод запомнил про Маркет.
 * <p>
 * База копится неделями и до сих пор была видна только по одному предмету за раз —
 * в подсказке, если этот предмет случайно оказался в руке. Здесь она видна целиком,
 * и сразу понятно, чего мод насмотрелся, а чего почти не видел.
 * <p>
 * Порядок — от самых наблюдаемых предметов к редким, и это не про удобство. Дно
 * цены, собранное по двадцати лотам, и дно по одному — величины разного качества,
 * и ставить их вперемешку значит выдавать второе за первое. Сверху то, на что
 * можно опереться.
 */
public final class HistoryView implements Panel {

    /** За какое время наблюдения ещё что-то значат. */
    private static final Duration MEMORY = Duration.ofDays(7);

    private static final int ROW = 12;
    private static final int ICON = 12;

    /** Колонки справа: дно, число лотов, давность, вывод о спросе. */
    private static final int COL_FLOOR = 58;
    private static final int COL_LOTS = 42;
    private static final int COL_SEEN = 54;
    private static final int COL_DEMAND = 118;

    private int scroll;
    private int maxScroll;

    @Override
    public String tab() {
        return "история";
    }

    @Override
    public String title() {
        return "История";
    }

    @Override
    public String subtitle() {
        PriceStore prices = HolyHelperClient.instance().prices();
        return prices.itemCount() + " предметов · " + prices.observationCount() + " лотов";
    }

    @Override
    public int accent() {
        return Theme.TEAL;
    }

    @Override
    public void open() {
        scroll = 0;
    }

    @Override
    public void scroll(double vertical) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(vertical)));
    }

    @Override
    public void body(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                     double alpha) {
        HolyHelperClient mod = HolyHelperClient.instance();
        List<PriceStore.Known> items = mod.prices().all(MEMORY);

        if (items.isEmpty()) {
            Fonts.draw(ctx, font, "Мод ещё не видел ни одного лота.", Fonts.BODY,
                    x, y + height / 2 - Card.LINE, Motion.fade(Theme.TEXT_DIM, alpha));
            Fonts.draw(ctx, font, "Откройте Маркет — он запомнит всё, мимо чего вы пролистаете.",
                    Fonts.BODY, x, y + height / 2 + 2, Motion.fade(Theme.TEXT_FAINT, alpha));
            return;
        }

        int nameWidth = width - COL_FLOOR - COL_LOTS - COL_SEEN - COL_DEMAND - ICON - 10;
        int cursor = x + ICON + 4;
        Fonts.label(ctx, font, "предмет", cursor, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        cursor += nameWidth;
        right(ctx, font, "дно", cursor + COL_FLOOR, y, alpha, Theme.TEAL);
        cursor += COL_FLOOR;
        right(ctx, font, "лотов", cursor + COL_LOTS, y, alpha, Theme.TEXT_FAINT);
        cursor += COL_LOTS;
        right(ctx, font, "видел", cursor + COL_SEEN, y, alpha, Theme.TEXT_FAINT);
        cursor += COL_SEEN;
        right(ctx, font, "спрос", cursor + COL_DEMAND, y, alpha, Theme.TEXT_FAINT);

        int listTop = y + Card.CAP + 3;
        int visible = Math.max(1, (height - Card.CAP - 3) / ROW);
        maxScroll = Math.max(0, items.size() - visible);
        scroll = Math.min(scroll, maxScroll);

        for (int i = 0; i < visible && i + scroll < items.size(); i++) {
            row(ctx, font, mod, items.get(i + scroll), x, listTop + i * ROW, width, nameWidth, alpha);
        }

        if (maxScroll > 0) {
            int listHeight = visible * ROW;
            int thumb = Math.max(8, listHeight * visible / items.size());
            int offset = (listHeight - thumb) * scroll / maxScroll;
            ctx.fill(x + width - 1, listTop, x + width, listTop + listHeight,
                    Motion.fade(Theme.TRACK, alpha));
            Paint.roundRect(ctx, x + width - 2, listTop + offset, 2, thumb, 1,
                    Motion.fade(Theme.TEAL, alpha * 0.7));
        }
    }

    private void row(DrawContext ctx, TextRenderer font, HolyHelperClient mod,
                     PriceStore.Known known, int x, int y, int width, int nameWidth, double alpha) {
        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of(known.itemId())));
        if (!stack.isEmpty()) {
            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float) x, (float) (y - 1));
            matrices.scale(ICON / 16f, ICON / 16f);
            ctx.drawItem(stack, 0, 0);
            matrices.popMatrix();
        }

        int cursor = x + ICON + 4;
        Fonts.draw(ctx, font, known.name(), Fonts.BODY, cursor, y + 1,
                Motion.fade(Theme.TEXT, alpha));

        cursor += nameWidth;
        Fonts.drawRight(ctx, font, Card.money(known.cheapestUnitPrice()), Fonts.NUM,
                cursor + COL_FLOOR, y + 1, Motion.fade(Theme.TEAL, alpha));

        cursor += COL_FLOOR;
        // Число лотов решает, насколько дну можно верить, поэтому оно ярче там,
        // где наблюдений много, и приглушено там, где их одно-два.
        int weight = known.samples() > 2 ? Theme.TEXT_DIM : Theme.TEXT_FAINT;
        Fonts.drawRight(ctx, font, Integer.toString(known.samples()), Fonts.NUM,
                cursor + COL_LOTS, y + 1, Motion.fade(weight, alpha));

        cursor += COL_LOTS;
        Fonts.drawRight(ctx, font, ago(known.seenAt()), Fonts.NUM,
                cursor + COL_SEEN, y + 1, Motion.fade(Theme.TEXT_FAINT, alpha));

        cursor += COL_SEEN;
        demand(ctx, font, mod, known.itemId(), cursor + COL_DEMAND, y, alpha);
    }

    /**
     * Вывод о спросе, если он есть.
     * <p>
     * Прочерк здесь означает не «спроса нет», а «мод пока не может сказать»: для
     * вывода нужно с полдюжины лотов разной цены, у каждого с отметкой оставшегося
     * срока. Молчание честнее догадки.
     */
    private void demand(DrawContext ctx, TextRenderer font, HolyHelperClient mod, String itemId,
                        int right, int y, double alpha) {
        Liquidity.Split split = Liquidity.split(mod.prices().samples(itemId, MEMORY)).orElse(null);
        if (split == null || !split.notable()) {
            Fonts.drawRight(ctx, font, "—", Fonts.NUM, right, y + 1,
                    Motion.fade(Theme.TEXT_FAINT, alpha));
            return;
        }
        if (split.cheapMovesFaster()) {
            Fonts.drawRight(ctx, font, "по " + split.cheaper().medianPrice() + " берут",
                    Fonts.NUM, right, y + 1, Motion.fade(Theme.GREEN, alpha));
            return;
        }
        // Дорогие лоты моложе дешёвых: кто-то выставил их недавно, и про спрос это
        // не говорит ничего. Так и пишем, вместо того чтобы выдавать за сигнал.
        Fonts.drawRight(ctx, font, "свежие дороже", Fonts.NUM, right, y + 1,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }

    private static void right(DrawContext ctx, TextRenderer font, String label, int right, int y,
                              double alpha, int color) {
        Fonts.label(ctx, font, label, right - Fonts.labelWidth(font, label), y,
                Motion.fade(color, alpha));
    }

    /** Давность коротко: часы, а после суток — дни. */
    private static String ago(Instant seenAt) {
        Duration age = Duration.between(seenAt, Instant.now());
        long hours = age.toHours();
        if (hours < 1) {
            return age.toMinutes() + " мин";
        }
        return hours < 24 ? hours + " ч" : age.toDays() + " дн";
    }

    @Override
    public void footer(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                       double alpha) {
        Fonts.label(ctx, font, "дно — самое дешёвое из увиденного, а не дно рынка", x, y,
                Motion.fade(Theme.TEXT_FAINT, alpha));

        String key = Keys.buyerKeyName();
        int keyWidth = Card.keyWidth(font, key);
        Card.key(ctx, font, key, x + width - keyWidth, y - 2, alpha);
        String hint = "вкладки — стрелки · закрыть";
        Fonts.label(ctx, font, hint, x + width - keyWidth - 6 - Fonts.labelWidth(font, hint), y,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }
}
