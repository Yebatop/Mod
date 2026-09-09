package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.MultiplierMath;
import dev.yebatop.holyhelper.analytics.RotationTimer;
import dev.yebatop.holyhelper.scan.BuyerParser;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Экран Скупца: всё, что мод прочитал в окне торговли, разом и по-человечески.
 * <p>
 * Он нужен потому, что в самом окне сравнить нечего. Пятнадцать товаров, у каждого
 * цена за партию и цена с множителями, а решать надо по цене за штуку — в игре её
 * нет нигде. Здесь она посчитана, список отсортирован, и рядом стоит то, что мод
 * видел на Маркете.
 * <p>
 * Это не экран клиента, а рисование в прямоугольник, потому что показывается оно
 * двумя способами: слоем поверх открытого окна Скупца и отдельным экраном, когда
 * никакого окна нет. Первый способ важнее: подменять окно значит его закрыть, и
 * тогда таблицу смотришь ценой того, что идти сдавать уже некуда.
 * <p>
 * Экран ничего не нажимает и ничего не отправляет. Он показывает снимок, снятый
 * пока игрок сам смотрел в окно, и всегда подписывает, насколько тот свежий.
 */
public final class BuyerView {

    /** За какое время наблюдения Маркета ещё что-то значат. */
    private static final Duration MARKET_MEMORY = Duration.ofHours(12);

    /** До этого возраста прогресс этапа считаем свежим и не подписываем. */
    private static final Duration STAGE_QUIET = Duration.ofMinutes(5);

    private static final int ROW = 12;
    private static final int HERO = 36;

    private static final int ICON = 12;

    /** Заголовок колонки и то, сколько места ей нужно под числа. */
    private record Column(String label, int minimum) {
    }

    private static final Column COL_MULT = new Column("множ.", 30);
    private static final Column COL_BATCH = new Column("за 16", 38);
    private static final Column COL_UNIT = new Column("за штуку", 44);
    private static final Column COL_LEFT = new Column("осталось", 42);
    private static final Column COL_MARKET = new Column("маркет", 80);

    private long openedAt = System.currentTimeMillis();
    private int scroll;
    private int maxScroll;

    /** Начать сборку заново — при каждом открытии. */
    public void open() {
        openedAt = System.currentTimeMillis();
        scroll = 0;
    }

    public void scroll(double vertical) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(vertical)));
    }

    public void render(DrawContext ctx, TextRenderer font, int x, int y, int width, int height) {
        HolyHelperClient mod = HolyHelperClient.instance();
        int count = mod.buyer().tradeOffers().size();
        String subtitle = Card.age(mod.buyer().tradeSeenAt()) + " · " + count + " позиций";

        Card.draw(ctx, font, x, y, width, height, "Скупец", subtitle, Theme.GOLD,
                System.currentTimeMillis() - openedAt,
                this::body, this::header, this::footer);
    }

    private void header(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                        double alpha) {
        RotationTimer timer = HolyHelperClient.instance().rotation();
        int cursor = x + width;
        cursor = chip(ctx, font, cursor, y, "особые торги", timer.remaining(true),
                timer.remainingFraction(true), Theme.TEAL, alpha);
        chip(ctx, font, cursor - Card.GAP * 2, y, "обычные торги", timer.remaining(false),
                timer.remainingFraction(false), Theme.GOLD, alpha);
    }

    /** Часы одной группы: подпись, остаток и полоса под ними. Возвращает левый край. */
    private int chip(DrawContext ctx, TextRenderer font, int right, int y, String label,
                     Optional<Duration> left, Optional<Double> fraction, int color, double alpha) {
        if (left.isEmpty()) {
            return right;
        }
        String value = Card.human(left.get());
        int width = Math.max(Fonts.labelWidth(font, label), Fonts.width(font, value, Fonts.NUM));
        int x = right - width;

        Fonts.label(ctx, font, label, x, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        Fonts.draw(ctx, font, value, Fonts.NUM, x, y + Card.CAP + 1, Motion.fade(color, alpha));
        fraction.ifPresent(share -> Paint.bar(ctx, x, y + Card.CAP + 1 + Card.LINE + 1, width, 2,
                share, Motion.mix(color, Theme.PANEL, 0.5), color, alpha));
        return x;
    }

    private void body(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                      double alpha) {
        HolyHelperClient mod = HolyHelperClient.instance();
        List<BuyerParser.Offer> offers = mod.buyer().tradeOffers();

        if (offers.isEmpty()) {
            Fonts.draw(ctx, font, "Окно торговли ещё не открывали.", Fonts.BODY,
                    x, y + height / 2 - Card.LINE, Motion.fade(Theme.TEXT_DIM, alpha));
            Fonts.draw(ctx, font, "Зайдите к Скупцу — мод прочитает товары сам, пока вы смотрите.",
                    Fonts.BODY, x, y + height / 2 + 2, Motion.fade(Theme.TEXT_FAINT, alpha));
            return;
        }

        hero(ctx, font, x, y, width, offers, alpha);
        table(ctx, font, x, y + HERO + Card.GAP, width, height - HERO - Card.GAP, offers, alpha);
    }

    /** Три карточки над таблицей: сравнение с Маркетом, множители, этап. */
    private void hero(DrawContext ctx, TextRenderer font, int x, int y, int width,
                      List<BuyerParser.Offer> offers, double alpha) {
        HolyHelperClient mod = HolyHelperClient.instance();
        long elapsed = System.currentTimeMillis() - openedAt;
        int cell = (width - Card.GAP * 2) / 3;

        double first = Motion.reveal(elapsed, Card.REVEAL_STEP, Card.REVEAL_LENGTH) * alpha;
        Paint.panel(ctx, x, y, cell, HERO, 4, Theme.PANEL, first);
        Paint.accent(ctx, x, y, HERO, Theme.GOLD, first);
        Fonts.label(ctx, font, "маркет видел дороже", x + 8, y + 5,
                Motion.fade(Theme.TEXT_FAINT, first));
        int used = Fonts.draw(ctx, font, Integer.toString(countDearer(offers)), Fonts.DISPLAY,
                x + 8, y + 14, Motion.fade(Theme.GOLD, first));
        Fonts.draw(ctx, font, "из " + offers.size(), Fonts.BODY, x + 12 + used, y + 18,
                Motion.fade(Theme.TEXT_DIM, first));

        double second = Motion.reveal(elapsed, Card.REVEAL_STEP * 2, Card.REVEAL_LENGTH) * alpha;
        int mx = x + cell + Card.GAP;
        Paint.panel(ctx, mx, y, cell, HERO, 4, Theme.PANEL, second);
        Paint.accent(ctx, mx, y, HERO, Theme.PURPLE, second);
        Fonts.label(ctx, font, "множители", mx + 8, y + 5, Motion.fade(Theme.TEXT_FAINT, second));
        List<BuyerParser.Multiplier> multipliers = mod.buyer().multipliers();
        if (multipliers.isEmpty()) {
            Fonts.draw(ctx, font, "окно не открывали", Fonts.BODY, mx + 8, y + 15,
                    Motion.fade(Theme.TEXT_FAINT, second));
        } else {
            int row = y + 14;
            for (int i = 0; i < Math.min(2, multipliers.size()); i++) {
                BuyerParser.Multiplier multiplier = multipliers.get(i);
                Fonts.draw(ctx, font, multiplier.category(), Fonts.BODY, mx + 8, row,
                        Motion.fade(Theme.TEXT_DIM, second));
                Fonts.drawRight(ctx, font, multiplier.stacks() + " ст.", Fonts.NUM,
                        mx + cell - 8, row, Motion.fade(Theme.PURPLE, second));
                row += Card.LINE + 1;
            }
        }

        double third = Motion.reveal(elapsed, Card.REVEAL_STEP * 3, Card.REVEAL_LENGTH) * alpha;
        int sx = x + (cell + Card.GAP) * 2;
        int sw = width - (cell + Card.GAP) * 2;
        Paint.panel(ctx, sx, y, sw, HERO, 4, Theme.PANEL, third);
        Paint.accent(ctx, sx, y, HERO, Theme.GREEN, third);
        BuyerParser.Stage stage = currentStage(mod);
        if (stage == null) {
            Fonts.label(ctx, font, "этапы", sx + 8, y + 5, Motion.fade(Theme.TEXT_FAINT, third));
            Fonts.draw(ctx, font, "окно не открывали", Fonts.BODY, sx + 8, y + 15,
                    Motion.fade(Theme.TEXT_FAINT, third));
        } else {
            // Возраст рядом с номером: прогресс этапа наращивает сервер, и без
            // нового захода в окно «Этапы» число стоит на месте, сколько бы вы
            // ни продали.
            String stale = Card.staleness(mod.buyer().stagesSeenAt(), STAGE_QUIET);
            Fonts.label(ctx, font, stale.isEmpty()
                            ? "этап #" + stage.number()
                            : "этап #" + stage.number() + " · " + stale,
                    sx + 8, y + 5, Motion.fade(Theme.TEXT_FAINT, third));
            Fonts.draw(ctx, font, Card.money(stage.progress()), Fonts.NUM, sx + 8, y + 14,
                    Motion.fade(Theme.GREEN, third));
            Fonts.drawRight(ctx, font, "из " + Card.money(stage.goal()), Fonts.NUM,
                    sx + sw - 8, y + 14, Motion.fade(Theme.TEXT_FAINT, third));
            Paint.bar(ctx, sx + 8, y + 29, sw - 16, 3, stage.completion(),
                    Theme.GREEN, Theme.TEAL, third);
        }
    }

    private void table(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                       List<BuyerParser.Offer> offers, double alpha) {
        int mult = widthOf(font, COL_MULT);
        int batch = widthOf(font, COL_BATCH);
        int unit = widthOf(font, COL_UNIT);
        int left = widthOf(font, COL_LEFT);
        int market = widthOf(font, COL_MARKET);
        int nameWidth = width - mult - batch - unit - left - market - ICON - 10;

        int cursor = x + ICON + 4;
        Fonts.label(ctx, font, "товар", cursor, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        cursor += nameWidth;
        headerCell(ctx, font, COL_MULT.label(), cursor + mult, y, alpha, Theme.TEXT_FAINT);
        cursor += mult;
        headerCell(ctx, font, COL_BATCH.label(), cursor + batch, y, alpha, Theme.TEXT_FAINT);
        cursor += batch;
        headerCell(ctx, font, COL_UNIT.label(), cursor + unit, y, alpha, Theme.GOLD);
        cursor += unit;
        headerCell(ctx, font, COL_LEFT.label(), cursor + left, y, alpha, Theme.TEXT_FAINT);
        cursor += left;
        headerCell(ctx, font, COL_MARKET.label(), cursor + market, y, alpha, Theme.TEXT_FAINT);

        int listTop = y + Card.CAP + 3;
        int listHeight = height - Card.CAP - 3;
        int visible = Math.max(1, listHeight / ROW);
        maxScroll = Math.max(0, offers.size() - visible);
        scroll = Math.min(scroll, maxScroll);

        long elapsed = System.currentTimeMillis() - openedAt;
        ctx.enableScissor(x, listTop, x + width, listTop + listHeight);
        for (int i = 0; i < visible && i + scroll < offers.size(); i++) {
            BuyerParser.Offer offer = offers.get(i + scroll);
            double reveal = Motion.reveal(elapsed, Card.REVEAL_STEP * 4 + i * 24L,
                    Card.REVEAL_LENGTH) * alpha;
            if (reveal > 0) {
                row(ctx, font, x, listTop + i * ROW, width, nameWidth, offer,
                        i + scroll == 0, reveal);
            }
        }
        ctx.disableScissor();

        if (maxScroll > 0) {
            int thumb = Math.max(8, listHeight * visible / offers.size());
            int offset = (listHeight - thumb) * scroll / maxScroll;
            ctx.fill(x + width - 1, listTop, x + width, listTop + listHeight,
                    Motion.fade(Theme.TRACK, alpha));
            Paint.roundRect(ctx, x + width - 2, listTop + offset, 2, thumb, 1,
                    Motion.fade(Theme.GOLD, alpha * 0.7));
        }
    }

    /**
     * Ширина колонки: не уже своей подписи и не уже, чем нужно числам.
     * <p>
     * Раньше это были константы, и они врали: подпись капсом с разрядкой заметно
     * шире, чем те же буквы строчными, по которым я их прикидывал. Заголовки
     * наезжали друг на друга. Теперь ширина считается от того, что нарисуется.
     */
    private static int widthOf(TextRenderer font, Column column) {
        return Math.max(column.minimum(), Fonts.labelWidth(font, column.label()) + 8);
    }

    private void headerCell(DrawContext ctx, TextRenderer font, String label, int right, int y,
                            double alpha, int color) {
        Fonts.label(ctx, font, label, right - Fonts.labelWidth(font, label), y,
                Motion.fade(color, alpha));
    }

    private void row(DrawContext ctx, TextRenderer font, int x, int y, int width, int nameWidth,
                     BuyerParser.Offer offer, boolean first, double alpha) {
        if (first) {
            Paint.roundRect(ctx, x, y, width, ROW - 1, 2, Motion.fade(0x1AF2B45C, alpha));
        }
        int slide = Motion.rise(alpha, 8);

        ItemStack stack = new ItemStack(Registries.ITEM.get(Identifier.of(offer.itemId())));
        if (!stack.isEmpty()) {
            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            matrices.translate((float) (x - slide), (float) (y - 1));
            matrices.scale(ICON / 16f, ICON / 16f);
            ctx.drawItem(stack, 0, 0);
            matrices.popMatrix();
        }

        int cursor = x + ICON + 4 - slide;
        int nameEnd = Fonts.draw(ctx, font, offer.name(), Fonts.BODY, cursor, y + 1,
                Motion.fade(Theme.TEXT, alpha));
        if (offer.special()) {
            Paint.spark(ctx, cursor + Math.min(nameWidth - 8, nameEnd + 3), y + 3,
                    Motion.fade(Theme.GREEN, alpha));
        }

        cursor = x + ICON + 4 + nameWidth;
        int step = widthOf(font, COL_MULT);
        cell(ctx, font, multiplierText(offer), cursor + step, y, alpha, Theme.PURPLE);
        cursor += step;
        step = widthOf(font, COL_BATCH);
        cell(ctx, font, Card.money(offer.batchPrice()), cursor + step, y, alpha, Theme.TEXT_DIM);
        cursor += step;
        step = widthOf(font, COL_UNIT);
        cell(ctx, font, String.format(Locale.ROOT, "%.2f", offer.unitPrice()),
                cursor + step, y, alpha, Theme.GOLD);
        cursor += step;
        step = widthOf(font, COL_LEFT);
        cell(ctx, font, Card.money(offer.available()), cursor + step, y, alpha, Theme.TEXT_DIM);
        cursor += step;
        market(ctx, font, offer, cursor + widthOf(font, COL_MARKET), y, alpha);
    }

    private void cell(DrawContext ctx, TextRenderer font, String text, int right, int y,
                      double alpha, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Fonts.drawRight(ctx, font, text, Fonts.NUM, right, y + 1, Motion.fade(color, alpha));
    }

    /**
     * Что мод видел на Маркете по этому предмету.
     * <p>
     * Осторожно: это не «цена рынка», а самое дешёвое из увиденного, то есть оценка
     * дна сверху. Рядом стоит число разных лотов — именно разных, а не просмотров:
     * одно объявление по дикой цене мод показывает жёлтым, потому что оно ничего
     * не говорит о рынке.
     */
    private void market(DrawContext ctx, TextRenderer font, BuyerParser.Offer offer,
                        int right, int y, double alpha) {
        PriceStore.Known known = HolyHelperClient.instance().prices()
                .known(offer.itemId(), MARKET_MEMORY).orElse(null);
        if (known == null) {
            Fonts.drawRight(ctx, font, "—", Fonts.NUM, right, y + 1,
                    Motion.fade(Theme.TEXT_FAINT, alpha * 0.6));
            return;
        }
        boolean dearer = known.cheapestUnitPrice() > offer.unitPrice() * 1.01;
        boolean trusted = known.samples() > 1;
        int color = dearer && trusted ? Theme.GREEN : dearer ? Theme.WARN : Theme.TEXT_FAINT;

        Fonts.drawRight(ctx, font, "от " + Card.money(known.cheapestUnitPrice())
                + " ×" + known.samples(), Fonts.NUM, right, y + 1, Motion.fade(color, alpha));
    }

    /**
     * Множитель, приведённый к достижимому.
     * <p>
     * Отношение итоговой цены к начальной у дешёвых товаров врёт из-за округления:
     * ламинария показывала ×1.06 там, где у соседей ×1.05. Поэтому наблюдаемое
     * значение приводится к ближайшему, которое складывается из надбавок сервера,
     * а необъяснённое печатается с вопросом.
     */
    private String multiplierText(BuyerParser.Offer offer) {
        double observed = offer.multiplierFactor();
        BuyerParser.Bonuses bonuses = HolyHelperClient.instance().buyer().bonuses();
        MultiplierMath.Applied applied =
                MultiplierMath.explain(observed, offer.batchPrice(), bonuses).orElse(null);

        if (applied != null) {
            return applied.any() ? String.format(Locale.ROOT, "×%.2f", applied.factor()) : "";
        }
        return observed > 1.001 ? String.format(Locale.ROOT, "×%.2f?", observed) : "";
    }

    private static int countDearer(List<BuyerParser.Offer> offers) {
        PriceStore prices = HolyHelperClient.instance().prices();
        int count = 0;
        for (BuyerParser.Offer offer : offers) {
            PriceStore.Known known = prices.known(offer.itemId(), MARKET_MEMORY).orElse(null);
            if (known != null && known.cheapestUnitPrice() > offer.unitPrice() * 1.01) {
                count++;
            }
        }
        return count;
    }

    private static BuyerParser.Stage currentStage(HolyHelperClient mod) {
        for (BuyerParser.Stage stage : mod.buyer().stages()) {
            if (!stage.completed() && !stage.locked() && stage.hasProgress()) {
                return stage;
            }
        }
        return null;
    }

    private void footer(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                        double alpha) {
        int cursor = x;
        cursor += legend(ctx, font, cursor, y, Theme.GREEN, "на маркете дороже", alpha);
        cursor += legend(ctx, font, cursor, y, Theme.WARN, "один лот — не рынок", alpha);
        legend(ctx, font, cursor, y, Theme.PURPLE, "действует множитель", alpha);

        String hint = "колесо — прокрутка · " + Keys.buyerKeyName() + " — закрыть";
        Fonts.label(ctx, font, hint, x + width - Fonts.labelWidth(font, hint), y,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }

    private int legend(DrawContext ctx, TextRenderer font, int x, int y, int color, String text,
                       double alpha) {
        Paint.roundRect(ctx, x, y + 1, 4, 4, 1, Motion.fade(color, alpha));
        Fonts.label(ctx, font, text, x + 7, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        return Fonts.labelWidth(font, text) + 18;
    }
}
