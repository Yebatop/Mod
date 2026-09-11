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
public final class BuyerView implements Panel {

    /** За какое время наблюдения Маркета ещё что-то значат. */
    private static final Duration MARKET_MEMORY = Duration.ofHours(12);

    /** До этого возраста прогресс этапа считаем свежим и не подписываем. */
    private static final Duration STAGE_QUIET = Duration.ofMinutes(5);

    private static final int ROW = 12;

    /**
     * Высота карточек над таблицей и строки внутри них.
     * <p>
     * Раньше эти числа подбирались на глаз, и я промахивался трижды подряд.
     * Теперь они считаны от подъёмов гарнитур, снятых с самих файлов шрифтов:
     * подпись — семь пикселей до базовой линии, текст и числа — девять, крупное
     * число Unbounded — пятнадцать.
     * <p>
     * Отсюда и разметка: подпись занимает строку сверху, крупное число ставится
     * ниже, а мелкий текст рядом с ним сдвигается вниз на разницу подъёмов —
     * иначе они выравниваются по верхушкам букв, и подпись оказывается выше
     * базовой линии числа на треть его высоты. Именно это и выглядело съехавшим.
     */
    private static final int HERO = 52;

    /** Верх подписи. */
    private static final int HERO_LABEL = 7;

    /**
     * Верх крупного числа.
     * <p>
     * Стоит само по себе и ни от чего не считается. Раньше от него вычислялось
     * положение соседних строк, и это вышло боком: правя меру шрифта, я сдвигал
     * не число, а всё вокруг него. Теперь двигать число можно, не трогая соседей,
     * и наоборот.
     */
    private static final int HERO_MAIN = 27;

    /** Верх строки «из N» рядом с числом. */
    private static final int HERO_ASIDE = 26;

    /** Верх строки под ней. */
    private static final int HERO_ASIDE_UNDER = 38;

    /** Верх первой строки обычного текста в карточках без крупного числа. */
    private static final int HERO_SIDE = 20;

    /** Верх второй такой строки. */
    private static final int HERO_UNDER = 32;

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
    @Override
    public void open() {
        openedAt = System.currentTimeMillis();
        scroll = 0;
    }

    @Override
    public void scroll(double vertical) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(vertical)));
    }

    @Override
    public String tab() {
        return "скупец";
    }

    @Override
    public String title() {
        return "Скупец";
    }

    @Override
    public String subtitle() {
        HolyHelperClient mod = HolyHelperClient.instance();
        int count = mod.buyer().tradeOffers().size();
        return Card.age(mod.buyer().tradeSeenAt()) + " · " + count + " позиций";
    }

    @Override
    public int accent() {
        return Theme.GOLD;
    }

    @Override
    public void header(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                       double alpha) {
        RotationTimer timer = HolyHelperClient.instance().rotation();
        if (timer.singleClock()) {
            // Сроки у групп совпали — часы одни, но подпись называет обе.
            // Подробнее в RotationTimer#singleClock.
            chip(ctx, font, x + width, y, "обычные и особые", timer.remaining(false),
                    timer.remainingFraction(false), Theme.GOLD, alpha);
            return;
        }
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

    @Override
    public void body(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
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
        Fonts.label(ctx, font, "маркет видел дороже", x + 8, y + HERO_LABEL,
                Motion.fade(Theme.TEXT_FAINT, first));
        // Число крупно слева, пояснения столбиком справа. Положения задаются
        // порознь: связав их, я дважды двигал не то, что собирался.
        int used = Fonts.draw(ctx, font, Integer.toString(countDearer(offers)), Fonts.DISPLAY,
                x + 8, y + HERO_MAIN, Motion.fade(Theme.GOLD, first));
        int side = x + 8 + used + 7;

        Fonts.draw(ctx, font, "из " + offers.size(), Fonts.BODY,
                side, y + HERO_ASIDE, Motion.fade(Theme.TEXT_DIM, first));

        // Сколько товаров мод про Маркет вообще не знает. Без этой строки «5 из 8»
        // читается увереннее, чем есть: часть восьми просто не проверена.
        int unknown = countUnknown(offers);
        Fonts.label(ctx, font, unknown == 0 ? "все проверены" : unknown + " без данных",
                side, y + HERO_ASIDE_UNDER,
                Motion.fade(unknown == 0 ? Theme.GREEN : Theme.TEXT_FAINT, first));

        double second = Motion.reveal(elapsed, Card.REVEAL_STEP * 2, Card.REVEAL_LENGTH) * alpha;
        int mx = x + cell + Card.GAP;
        Paint.panel(ctx, mx, y, cell, HERO, 4, Theme.PANEL, second);
        Paint.accent(ctx, mx, y, HERO, Theme.PURPLE, second);
        Fonts.label(ctx, font, "множители", mx + 8, y + HERO_LABEL,
                Motion.fade(Theme.TEXT_FAINT, second));
        List<BuyerParser.Multiplier> multipliers = mod.buyer().multipliers();
        if (multipliers.isEmpty()) {
            Fonts.draw(ctx, font, "окно не открывали", Fonts.BODY, mx + 8, y + HERO_SIDE,
                    Motion.fade(Theme.TEXT_FAINT, second));
        } else {
            int row = y + HERO_SIDE;
            for (int i = 0; i < Math.min(2, multipliers.size()); i++) {
                BuyerParser.Multiplier multiplier = multipliers.get(i);
                Fonts.draw(ctx, font, multiplier.category(), Fonts.BODY, mx + 8, row,
                        Motion.fade(Theme.TEXT_DIM, second));
                Fonts.drawRight(ctx, font, multiplier.stacks() + " ст.", Fonts.NUM,
                        mx + cell - 8, row, Motion.fade(Theme.PURPLE, second));
                row += HERO_UNDER - HERO_SIDE;
            }
        }

        double third = Motion.reveal(elapsed, Card.REVEAL_STEP * 3, Card.REVEAL_LENGTH) * alpha;
        int sx = x + (cell + Card.GAP) * 2;
        int sw = width - (cell + Card.GAP) * 2;
        Paint.panel(ctx, sx, y, sw, HERO, 4, Theme.PANEL, third);
        Paint.accent(ctx, sx, y, HERO, Theme.GREEN, third);
        BuyerParser.Stage stage = currentStage(mod);
        if (stage == null) {
            Fonts.label(ctx, font, "этапы", sx + 8, y + HERO_LABEL,
                    Motion.fade(Theme.TEXT_FAINT, third));
            Fonts.draw(ctx, font, "окно не открывали", Fonts.BODY, sx + 8, y + HERO_SIDE,
                    Motion.fade(Theme.TEXT_FAINT, third));
        } else {
            // Возраст рядом с номером: прогресс этапа наращивает сервер, и без
            // нового захода в окно «Этапы» число стоит на месте, сколько бы вы
            // ни продали.
            String stale = Card.staleness(mod.buyer().stagesSeenAt(), STAGE_QUIET);
            Fonts.label(ctx, font, stale.isEmpty()
                            ? "этап #" + stage.number()
                            : "этап #" + stage.number() + " · " + stale,
                    sx + 8, y + HERO_LABEL, Motion.fade(Theme.TEXT_FAINT, third));
            Fonts.draw(ctx, font, Card.money(stage.progress()), Fonts.NUM, sx + 8, y + HERO_SIDE,
                    Motion.fade(Theme.GREEN, third));
            Fonts.drawRight(ctx, font, "из " + Card.money(stage.goal()), Fonts.NUM,
                    sx + sw - 8, y + HERO_SIDE, Motion.fade(Theme.TEXT_FAINT, third));
            Paint.bar(ctx, sx + 8, y + HERO - 9, sw - 16, 3, stage.completion(),
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

        int listTop = y + Card.CAP + 3;
        int listHeight = height - Card.CAP - 3;
        int visible = Math.max(1, listHeight / ROW);

        // Подложка под колонкой, по которой отсортирован список. Заголовок про это
        // тоже говорит — золотом, — но заголовок надо прочитать, а полосу глаз
        // ловит сразу. Кончается она на последней строке, а не на дне отведённого
        // места: полоса, уходящая в пустоту, показывает границу таблицы там, где
        // таблицы уже нет.
        int sortedRight = x + ICON + 4 + nameWidth + mult + batch + unit;
        int sortedBottom = listTop + Math.min(visible, offers.size()) * ROW;
        // Сверху вниз с затуханием: ровная заливка читается серым ящиком поверх
        // таблицы, а гаснущая — подсветкой колонки.
        Paint.roundRect(ctx, sortedRight - unit - 3, y - 3, unit + 6, sortedBottom - (y - 3), 0,
                Motion.fade(Theme.SORTED, alpha), Motion.fade(Theme.SORTED_FADE, alpha));

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

        maxScroll = Math.max(0, offers.size() - visible);
        scroll = Math.min(scroll, maxScroll);

        long elapsed = System.currentTimeMillis() - openedAt;
        // Обрезки не нужно: строк рисуется ровно столько, сколько помещается,
        // и за край не выходит ни одна. А под матрицей мода обрезка ещё и
        // считалась бы в чужих координатах.
        for (int i = 0; i < visible && i + scroll < offers.size(); i++) {
            BuyerParser.Offer offer = offers.get(i + scroll);
            double reveal = Motion.reveal(elapsed, Card.REVEAL_STEP * 4 + i * 24L,
                    Card.REVEAL_LENGTH) * alpha;
            if (reveal > 0) {
                row(ctx, font, x, listTop + i * ROW, width, nameWidth, offer,
                        i + scroll == 0, reveal);
            }
        }

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
        // Целая часть решает, дорого или дёшево; копейки нужны только чтобы
        // сравнение не врало. Разной яркостью это видно без чтения.
        Fonts.splitRight(ctx, font, String.format(Locale.ROOT, "%.2f", offer.unitPrice()),
                cursor + step, y + 1, Motion.fade(Theme.GOLD, alpha),
                Motion.fade(Theme.FRACTION, alpha));
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

    /** По скольким товарам мод на Маркете ничего не видел. */
    private static int countUnknown(List<BuyerParser.Offer> offers) {
        PriceStore prices = HolyHelperClient.instance().prices();
        int count = 0;
        for (BuyerParser.Offer offer : offers) {
            if (prices.known(offer.itemId(), MARKET_MEMORY).isEmpty()) {
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

    @Override
    public void footer(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                       double alpha) {
        // Правая часть рисуется первой: она обязательна, а легенда — нет. Зная,
        // где кончается место, можно не рисовать те подписи, которые в него уже
        // не влезут. Раньше легенда просто наезжала на подсказку.
        String key = Keys.buyerKeyName();
        int keyWidth = Card.keyWidth(font, key);
        Card.key(ctx, font, key, x + width - keyWidth, y - 2, alpha);

        String hint = "колесо — прокрутка · закрыть";
        int hintLeft = x + width - keyWidth - 6 - Fonts.labelWidth(font, hint);
        Fonts.label(ctx, font, hint, hintLeft, y, Motion.fade(Theme.TEXT_FAINT, alpha));

        int limit = hintLeft - Card.GAP * 2;
        int cursor = x;
        cursor = legendFits(ctx, font, cursor, y, limit, Theme.GREEN, "на маркете дороже", alpha);
        cursor = legendFits(ctx, font, cursor, y, limit, Theme.WARN, "один лот — не рынок", alpha);
        cursor = legendFits(ctx, font, cursor, y, limit, Theme.PURPLE, "действует множитель", alpha);

        // Искру в списке рисует row(), а объяснить её было негде: подвал
        // перечислял три цвета и молчал про единственный значок на экране.
        int sparkWidth = 9 + Fonts.labelWidth(font, "особое предложение");
        if (cursor + sparkWidth <= limit) {
            Paint.spark(ctx, cursor, y + 1, Motion.fade(Theme.GREEN, alpha));
            Fonts.label(ctx, font, "особое предложение", cursor + 9, y,
                    Motion.fade(Theme.TEXT_FAINT, alpha));
        }
    }

    /**
     * Пункт легенды, если он ещё помещается до заданной границы.
     * <p>
     * Подвал должен читаться на любой ширине, а легенда — то, чем можно
     * пожертвовать: цвета в таблице говорят сами за себя, а подсказка про
     * клавишу нет. Поэтому не влезающие пункты просто не рисуются, вместо того
     * чтобы наезжать на соседа.
     *
     * @return новое положение курсора
     */
    private int legendFits(DrawContext ctx, TextRenderer font, int cursor, int y, int limit,
                           int color, String text, double alpha) {
        // Ширину меряем тем же способом, каким её считает сам legend, иначе
        // проверка и рисование разойдутся при первой же правке отступов.
        if (cursor + Fonts.labelWidth(font, text) + 18 > limit) {
            return cursor;
        }
        return cursor + legend(ctx, font, cursor, y, color, text, alpha);
    }

    private int legend(DrawContext ctx, TextRenderer font, int x, int y, int color, String text,
                       double alpha) {
        Paint.roundRect(ctx, x, y + 1, 4, 4, 1, Motion.fade(color, alpha));
        Fonts.label(ctx, font, text, x + 7, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        return Fonts.labelWidth(font, text) + 18;
    }
}
