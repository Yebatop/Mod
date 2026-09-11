package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.Candles;
import dev.yebatop.holyhelper.rest.CoinRateTracker;
import dev.yebatop.holyhelper.ui.Card;
import dev.yebatop.holyhelper.ui.Fonts;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Курс монетки свечами.
 * <p>
 * Единственный экран мода, где данные — о совершённых сделках, а не о запросах.
 * Маркет показывает, сколько просят, и всё там обвешано оговорками; здесь API
 * отдаёт, сколько дали, и о цене можно говорить прямо.
 * <p>
 * Свечами, а не линией, потому что линия по сделкам врёт про плотность: десять
 * сделок подряд по одной цене и одна сделка за час выглядят на ней одинаково.
 * Свеча показывает и размах внутри часа, и то, сколько раз за этот час вообще
 * торговали.
 * <p>
 * Работает с первого запуска: историю отдаёт API, копить её неделями не нужно.
 */
public final class RateView implements Panel {

    /** Длина одной свечи. Час — при плотности рынка Прайма это десятки сделок. */
    private static final Duration PERIOD = Duration.ofHours(1);

    /** За какое окно считаем медиану, от которой меряется отклонение. */
    private static final Duration WINDOW = Duration.ofHours(24);

    /** Ширина свечи вместе с просветом. */
    private static final int STEP = 7;

    /** Ширина тела свечи. Нечётная, чтобы фитиль встал ровно по середине. */
    private static final int BODY = 5;

    /** Место справа под подписи шкалы. */
    private static final int SCALE = 52;

    /** Высота строки с крупным курсом над графиком. */
    private static final int HEAD = 30;

    @Override
    public String tab() {
        return "курс";
    }

    @Override
    public String title() {
        return "Курс монетки";
    }

    @Override
    public String subtitle() {
        CoinRateTracker rates = HolyHelperClient.instance().rates();
        if (rates.size() == 0) {
            return "сделки ещё не загружены";
        }
        return rates.size() + " сделок из API · свеча = час";
    }

    @Override
    public int accent() {
        return Theme.TEAL;
    }

    @Override
    public void body(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                     double alpha) {
        HolyHelperClient mod = HolyHelperClient.instance();
        CoinRateTracker rates = mod.rates();

        int columns = Math.max(1, (width - SCALE) / STEP);
        List<Candles.Candle> candles = Candles.of(rates.trades(), PERIOD, columns);

        if (candles.isEmpty()) {
            Fonts.draw(ctx, font, "Сделок пока нет.", Fonts.BODY,
                    x, y + height / 2 - Card.LINE, Motion.fade(Theme.TEXT_DIM, alpha));
            Fonts.draw(ctx, font, "Курс приходит из API сам — окно Биржи открывать не нужно.",
                    Fonts.BODY, x, y + height / 2 + 2, Motion.fade(Theme.TEXT_FAINT, alpha));
            return;
        }

        head(ctx, font, rates, candles, x, y, width, alpha);
        chart(ctx, font, candles, x, y + HEAD, width, height - HEAD, alpha);
    }

    /** Крупный последний курс, отклонение от медианы и размах за показанное время. */
    private void head(DrawContext ctx, TextRenderer font, CoinRateTracker rates,
                      List<Candles.Candle> candles, int x, int y, int width, double alpha) {
        Candles.Candle last = candles.get(candles.size() - 1);

        Fonts.label(ctx, font, "последняя сделка", x, y, Motion.fade(Theme.TEXT_FAINT, alpha));
        int used = Fonts.draw(ctx, font, Card.money(Math.round(last.close())), Fonts.NUM,
                x, y + 11, Motion.fade(Theme.TEAL, alpha));
        Fonts.draw(ctx, font, "за жетон", Fonts.BODY, x + used + 6, y + 13,
                Motion.fade(Theme.TEXT_FAINT, alpha));

        Optional<Double> deviation = rates.deviationPercent(WINDOW);
        deviation.ifPresent(percent -> {
            String text = String.format(Locale.ROOT, "%+.1f %% к медиане за сутки", percent);
            Fonts.draw(ctx, font, text, Fonts.NUM, x + width - SCALE
                            - Fonts.width(font, text, Fonts.NUM), y + 11,
                    Motion.fade(percent >= 0 ? Theme.GREEN : Theme.WARN, alpha));
        });
    }

    /**
     * Сам график.
     * <p>
     * Шкала строится по крайностям свечей, а не по закрытиям: иначе фитиль,
     * выходящий за верх, обрезался бы, и размах дня оказывался бы меньше, чем был.
     */
    private void chart(DrawContext ctx, TextRenderer font, List<Candles.Candle> candles,
                       int x, int y, int width, int height, double alpha) {
        double high = Candles.high(candles);
        double low = Candles.low(candles);
        double span = high - low;
        if (span <= 0) {
            // Курс не менялся вовсе: рисовать разброс нечем, и растягивать плоскую
            // линию на всю высоту значит выдумать движение.
            span = Math.max(1, high * 0.02);
            low = high - span / 2;
        }

        int plot = width - SCALE;
        int bottom = y + height;

        // Три деления шкалы: верх, середина, низ. Больше на такой высоте только
        // мешает — цифры начинают наезжать на свечи.
        for (int i = 0; i <= 2; i++) {
            double value = high - span * i / 2;
            int line = y + (height - 1) * i / 2;
            ctx.fill(x, line, x + plot, line + 1, Motion.fade(Theme.BORDER, alpha * 0.5));
            Fonts.draw(ctx, font, Card.money(Math.round(value)), Fonts.NUM,
                    x + plot + 6, line - 4, Motion.fade(Theme.TEXT_FAINT, alpha));
        }

        for (int i = 0; i < candles.size(); i++) {
            Candles.Candle candle = candles.get(i);
            int left = x + i * STEP;
            int middle = left + BODY / 2;

            int top = level(candle.high(), high, span, y, height);
            int floor = level(candle.low(), high, span, y, height);
            int openY = level(candle.open(), high, span, y, height);
            int closeY = level(candle.close(), high, span, y, height);

            int color = candle.rising() ? Theme.GREEN : Theme.WARN;
            // Фитиль в один пиксель, тело — в пять: так свеча читается даже там,
            // где размах внутри часа был нулевым.
            Paint.roundRect(ctx, middle, top, 1, Math.max(1, floor - top), 0,
                    Motion.fade(color, alpha * 0.7));
            int bodyTop = Math.min(openY, closeY);
            Paint.roundRect(ctx, left, bodyTop, BODY, Math.max(1, Math.abs(closeY - openY)), 1,
                    Motion.fade(color, alpha));
        }

        // Подпись времени под левым и правым краем: без неё непонятно, за какой
        // срок график — за день или за неделю.
        String from = span(candles.get(0).openMillis());
        Fonts.label(ctx, font, from, x, bottom + 2, Motion.fade(Theme.TEXT_FAINT, alpha));
        Fonts.label(ctx, font, "сейчас",
                x + plot - Fonts.labelWidth(font, "сейчас"), bottom + 2,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }

    /** Высота точки на графике для заданного курса. */
    private static int level(double value, double high, double span, int y, int height) {
        double share = (high - value) / span;
        return y + (int) Math.round(Math.max(0, Math.min(1, share)) * (height - 1));
    }

    /** Как давно начинается показанный отрезок. */
    private static String span(long openMillis) {
        Duration age = Duration.between(java.time.Instant.ofEpochMilli(openMillis),
                java.time.Instant.now());
        long hours = age.toHours();
        return hours >= 24 ? age.toDays() + " дн назад" : hours + " ч назад";
    }

    @Override
    public void footer(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                       double alpha) {
        Fonts.label(ctx, font, "это совершённые сделки, а не заявки в стакане", x, y,
                Motion.fade(Theme.TEXT_FAINT, alpha));

        String key = Keys.buyerKeyName();
        int keyWidth = Card.keyWidth(font, key);
        Card.key(ctx, font, key, x + width - keyWidth, y - 2, alpha);
        String hint = "вкладки — стрелки · закрыть";
        Fonts.label(ctx, font, hint, x + width - keyWidth - 6 - Fonts.labelWidth(font, hint), y,
                Motion.fade(Theme.TEXT_FAINT, alpha));
    }
}
