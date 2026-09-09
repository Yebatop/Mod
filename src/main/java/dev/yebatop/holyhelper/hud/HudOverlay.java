package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.RotationTimer;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.BuyerParser;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Панель в углу экрана.
 * <p>
 * Она существует потому, что команда в чате бесполезна там, где нужна: с открытым
 * окном Скупца чат не открыть. Панель показывает то, что мод уже знает, и не требует
 * ничего набирать.
 * <p>
 * Рисуется только то, что действительно известно. Пустая строка вместо таймера хуже
 * отсутствия строки: она выглядит как поломка, хотя окно просто ещё не открывали.
 * <p>
 * От макета панель отличается ровно одним — шрифтом. В макете три гарнитуры и
 * мелкие подписи в разрядку; клиент рисует одним встроенным шрифтом одного кегля,
 * и подделывать иерархию нечем. Поэтому её несёт цвет и расположение: золото —
 * Скупец, бирюза — Маркет, приглушённый серый — подписи. Гарнитуры из макета можно
 * будет положить в мод отдельным шагом, это заметная работа и отдельный разговор.
 */
public final class HudOverlay {

    /** Имя функции в реестре LiteAPI: попав в блок-лист, панель обязана исчезнуть целиком. */
    public static final String FEATURE = "hud-overlay";

    /** За какое окно считаем медиану курса. Сутки сглаживают ночные перекосы. */
    private static final Duration RATE_WINDOW = Duration.ofHours(24);

    /** Отклонение меньше этого — шум, а не сигнал. */
    private static final double NOTABLE_PERCENT = 3;

    private static final int WIDTH = 118;
    private static final int MARGIN = 4;
    private static final int PAD = 5;
    private static final int LINE = 9;
    private static final int GAP = 3;
    private static final int BAR = 2;

    /** Период пробега блика по кромке — как в макете. */
    private static final long SHEEN_PERIOD = 8_000L;

    /** Разбег появления панелей и длительность появления одной. */
    private static final long REVEAL_STEP = 90L;
    private static final long REVEAL_LENGTH = 420L;

    /** Сколько сделок берём в линию курса. */
    private static final int SPARK_POINTS = 40;

    /** Когда панель впервые появилась. Отсюда считается разбег появления. */
    private static long shownSince;

    /** Что рисуется внутри одной панели. */
    @FunctionalInterface
    private interface Body {
        void paint(DrawContext ctx, TextRenderer font, int x, int y, int width, double alpha);
    }

    private record Block(int height, Body body) {
    }

    private HudOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.of(HolyHelperClient.MOD_ID, "overlay"),
                HudOverlay::render);
    }

    /** Сбрасывает появление, чтобы при следующем входе панель собралась заново. */
    public static void resetAnimation() {
        shownSince = 0;
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        HolyHelperClient mod = HolyHelperClient.instance();

        if (mod == null || client.player == null || client.options.hudHidden) {
            return;
        }
        if (!mod.config().hudEnabled || !ServerDetector.onHolyWorld()) {
            return;
        }
        // Блок-лист сервера сильнее настройки игрока: запрещённая функция исчезает,
        // а не прячется за галочкой.
        if (mod.featureGate().status() == FeatureGate.Status.ANSWERED
                && !mod.featureGate().isAllowed(FEATURE)) {
            return;
        }

        List<Block> blocks = compose(mod, client.textRenderer);
        if (blocks.isEmpty()) {
            shownSince = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (shownSince == 0) {
            shownSince = now;
        }
        long elapsed = now - shownSince;

        int x = ctx.getScaledWindowWidth() - WIDTH - MARGIN;
        int y = MARGIN;

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            double reveal = Motion.reveal(elapsed, i * REVEAL_STEP, REVEAL_LENGTH);
            if (reveal > 0) {
                int top = y + Motion.rise(reveal, 10);
                Paint.panel(ctx, x, top, WIDTH, block.height(), Theme.PANEL, reveal);
                Paint.sheen(ctx, x, top, WIDTH, now, SHEEN_PERIOD, reveal);
                block.body().paint(ctx, client.textRenderer, x + PAD, top + PAD, WIDTH - PAD * 2, reveal);
            }
            y += block.height() + GAP;
        }
    }

    /**
     * Собирает панели из того, что известно. Порядок один и тот же всегда:
     * панель, которая переставляется от того, что мод чего-то не знает, читается
     * хуже отсутствующей.
     */
    private static List<Block> compose(HolyHelperClient mod, TextRenderer font) {
        List<Block> blocks = new ArrayList<>();

        ScoreboardWatcher.Snapshot board = mod.board().snapshot();
        boolean hasBalance = board.present() && board.coins() >= 0;

        RotationTimer timer = mod.rotation();
        Optional<Duration> regular = timer.remaining(false);
        Optional<Duration> special = timer.remaining(true);

        Optional<Double> rate = mod.rates().latest().map(trade -> trade.rate());
        BuyerParser.Stage stage = currentStage(mod);

        if (!hasBalance && regular.isEmpty() && special.isEmpty() && rate.isEmpty() && stage == null) {
            return blocks;
        }

        blocks.add(new Block(PAD * 2 + LINE, (ctx, f, x, y, w, alpha) -> {
            ctx.drawText(f, "HolyHelper", x, y, Motion.fade(Theme.GOLD, alpha), false);
            int dot = board.present() ? Theme.GREEN : Theme.TEXT_FAINT;
            ctx.fill(x + w - 5, y + 2, x + w, y + 7, Motion.fade(dot, alpha));
        }));

        if (hasBalance) {
            blocks.add(new Block(PAD * 2 + LINE, (ctx, f, x, y, w, alpha) ->
                    balance(ctx, f, x, y, board, alpha)));
        }

        if (regular.isPresent() || special.isPresent()) {
            int entries = (regular.isPresent() ? 1 : 0) + (special.isPresent() ? 1 : 0);
            int height = PAD * 2 + entries * (LINE + 1 + BAR) + (entries - 1) * 4;
            blocks.add(new Block(height, (ctx, f, x, y, w, alpha) -> {
                int row = y;
                if (regular.isPresent()) {
                    cycle(ctx, f, x, row, w, "Обычные торги", regular.get(),
                            timer.remainingFraction(false), Theme.GOLD, Theme.GOLD_DEEP, alpha);
                    row += LINE + 1 + BAR + 4;
                }
                if (special.isPresent()) {
                    cycle(ctx, f, x, row, w, "Особые торги", special.get(),
                            timer.remainingFraction(true), Theme.TEAL, Theme.TEAL_DEEP, alpha);
                }
            }));
        }

        if (rate.isPresent()) {
            List<Double> points = mod.rates().recentRates(SPARK_POINTS);
            boolean spark = points.size() >= 2;
            int height = PAD * 2 + LINE + (spark ? 3 + 14 : 0);
            Optional<Double> deviation = mod.rates().deviationPercent(RATE_WINDOW);
            blocks.add(new Block(height, (ctx, f, x, y, w, alpha) -> {
                ctx.drawText(f, "Курс", x, y, Motion.fade(Theme.TEXT_DIM, alpha), false);
                String value = money(Math.round(rate.get()));
                int valueWidth = f.getWidth(value);
                ctx.drawText(f, value, x + w - valueWidth, y, Motion.fade(Theme.TEXT, alpha), false);

                if (deviation.isPresent()) {
                    String text = String.format(Locale.ROOT, "%+.1f%%", deviation.get());
                    // Цвет говорит только «обратите внимание». Что тут выгодно, зависит
                    // от того, что игрок собирается делать, и мод этого не знает.
                    int color = Math.abs(deviation.get()) >= NOTABLE_PERCENT
                            ? Theme.WARN : Theme.TEXT_FAINT;
                    ctx.drawText(f, text, x + f.getWidth("Курс") + 4, y, Motion.fade(color, alpha), false);
                }
                if (spark) {
                    Paint.line(ctx, x, y + LINE + 3, w, 14, points,
                            Motion.fade(Theme.GOLD, alpha * 0.9), alpha);
                }
            }));
        }

        if (stage != null) {
            blocks.add(new Block(PAD * 2 + LINE + 1 + BAR, (ctx, f, x, y, w, alpha) -> {
                ctx.drawText(f, "Этап #" + stage.number(), x, y,
                        Motion.fade(Theme.TEXT_DIM, alpha), false);
                String value = money(stage.progress()) + " / " + money(stage.goal());
                ctx.drawText(f, value, x + w - f.getWidth(value), y,
                        Motion.fade(Theme.GREEN, alpha), false);
                Paint.bar(ctx, x, y + LINE + 1, w, BAR, stage.completion(),
                        Theme.GREEN, Theme.TEAL, alpha);
            }));
        }

        return blocks;
    }

    /** Строка цикла: подпись, остаток и полоса, если известна длина цикла. */
    private static void cycle(DrawContext ctx, TextRenderer font, int x, int y, int w,
                              String label, Duration left, Optional<Double> fraction,
                              int from, int to, double alpha) {
        ctx.drawText(font, label, x, y, Motion.fade(Theme.TEXT_DIM, alpha), false);
        String value = human(left);
        ctx.drawText(font, value, x + w - font.getWidth(value), y, Motion.fade(from, alpha), false);

        // Без знаменателя полосу не рисуем: доля от неизвестного — это не «пусто»,
        // это выдумка.
        fraction.ifPresent(value2 -> Paint.bar(ctx, x, y + LINE + 1, w, BAR, value2, to, from, alpha));
    }

    private static void balance(DrawContext ctx, TextRenderer font, int x, int y,
                                ScoreboardWatcher.Snapshot board, double alpha) {
        int cursor = x;
        cursor = currency(ctx, font, cursor, y, board.coins(), Theme.GOLD, 0, alpha);
        cursor = currency(ctx, font, cursor, y, board.gems(), Theme.PURPLE, 1, alpha);
        currency(ctx, font, cursor, y, board.tokens(), Theme.TEAL, 2, alpha);
    }

    /** Одна валюта со значком. Отрицательное значение — «не нашли в панели», его не рисуем. */
    private static int currency(DrawContext ctx, TextRenderer font, int x, int y,
                                long value, int color, int shape, double alpha) {
        if (value < 0) {
            return x;
        }
        int tint = Motion.fade(color, alpha);
        switch (shape) {
            case 0 -> Paint.coin(ctx, x, y + 2, tint);
            case 1 -> Paint.gem(ctx, x, y + 2, tint);
            default -> Paint.token(ctx, x, y + 2, tint);
        }
        String text = money(value);
        ctx.drawText(font, text, x + 7, y, Motion.fade(Theme.TEXT, alpha), false);
        return x + 7 + font.getWidth(text) + 7;
    }

    /**
     * Текущий этап: первый незакрытый и невыполненный, у которого есть прогресс.
     * Выполненные и закрытые в панели не нужны — они не подсказывают, что делать.
     */
    private static BuyerParser.Stage currentStage(HolyHelperClient mod) {
        for (BuyerParser.Stage stage : mod.buyer().stages()) {
            if (!stage.completed() && !stage.locked() && stage.hasProgress()) {
                return stage;
            }
        }
        return null;
    }

    /** Часы показываем только когда они есть, секунды — только на последних минутах. */
    private static String human(Duration left) {
        long hours = left.toHours();
        long minutes = left.toMinutesPart();
        long seconds = left.toSecondsPart();

        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        if (minutes > 0) {
            return minutes + " мин " + seconds + " с";
        }
        return seconds + " с";
    }

    /** Разряды через пробел: без них шестизначные числа не читаются с одного взгляда. */
    private static String money(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }
}
