package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.RotationTimer;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.BuyerParser;
import dev.yebatop.holyhelper.ui.Fonts;
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
 * Слева, а не справа, как было в макете. В макете нарисована сцена без сайдбара, а на
 * Прайме сайдбар справа висит всегда — панель наезжала прямо на него, и не читались
 * обе. Левый верх свободен.
 * <p>
 * Рисуется только то, что действительно известно. Пустая строка вместо таймера хуже
 * отсутствия строки: она выглядит как поломка, хотя окно просто ещё не открывали.
 */
public final class HudOverlay {

    /** Имя функции в реестре LiteAPI: попав в блок-лист, панель обязана исчезнуть целиком. */
    public static final String FEATURE = "hud-overlay";

    /** За какое окно считаем медиану курса. Сутки сглаживают ночные перекосы. */
    private static final Duration RATE_WINDOW = Duration.ofHours(24);

    /** Отклонение меньше этого — шум, а не сигнал. */
    private static final double NOTABLE_PERCENT = 3;

    private static final int WIDTH = 132;
    private static final int MARGIN = 4;
    private static final int PAD = 7;
    private static final int RADIUS = 5;

    /** Высота строки текста и высота мелкой подписи. */
    private static final int LINE = 9;
    private static final int CAP = 8;
    private static final int BAR = 3;

    /** Отступ между секциями внутри карточки. */
    private static final int GAP = 7;

    private static final long SHEEN_PERIOD = 8_000L;
    private static final long REVEAL_STEP = 80L;
    private static final long REVEAL_LENGTH = 420L;

    private static final int SPARK_POINTS = 40;
    private static final int SPARK_HEIGHT = 11;

    /** Когда панель впервые появилась. Отсюда считается разбег появления. */
    private static long shownSince;

    @FunctionalInterface
    private interface Body {
        void paint(DrawContext ctx, TextRenderer font, int x, int y, int width, double alpha);
    }

    private record Section(int height, Body body) {
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

        List<Section> sections = compose(mod, client.textRenderer);
        if (sections.isEmpty()) {
            shownSince = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (shownSince == 0) {
            shownSince = now;
        }
        long elapsed = now - shownSince;

        int height = PAD * 2 + (sections.size() - 1) * GAP;
        for (Section section : sections) {
            height += section.height();
        }

        double card = Motion.reveal(elapsed, 0, REVEAL_LENGTH);
        if (card <= 0) {
            return;
        }
        int x = MARGIN;
        int y = MARGIN + Motion.rise(card, 10);

        Paint.panel(ctx, x, y, WIDTH, height, RADIUS, Theme.PANEL, card);
        Paint.sheen(ctx, x, y, WIDTH, RADIUS, now, SHEEN_PERIOD, card);

        int inner = WIDTH - PAD * 2;
        int cursor = y + PAD;

        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            double reveal = Motion.reveal(elapsed, REVEAL_STEP * (i + 1), REVEAL_LENGTH) * card;
            if (reveal > 0) {
                section.body().paint(ctx, client.textRenderer, x + PAD, cursor, inner, reveal);
            }
            cursor += section.height();
            if (i < sections.size() - 1) {
                Paint.separator(ctx, x + PAD, cursor + GAP / 2, inner, card * 0.7);
                cursor += GAP;
            }
        }
    }

    /**
     * Собирает секции из того, что известно. Порядок один и тот же всегда: панель,
     * которая переставляется от того, что мод чего-то не знает, читается хуже
     * отсутствующей.
     */
    private static List<Section> compose(HolyHelperClient mod, TextRenderer font) {
        List<Section> sections = new ArrayList<>();

        ScoreboardWatcher.Snapshot board = mod.board().snapshot();
        boolean hasBalance = board.present() && board.coins() >= 0;

        RotationTimer timer = mod.rotation();
        Optional<Duration> regular = timer.remaining(false);
        Optional<Duration> special = timer.remaining(true);

        Optional<Double> rate = mod.rates().latest().map(trade -> trade.rate());
        BuyerParser.Stage stage = currentStage(mod);

        if (!hasBalance && regular.isEmpty() && special.isEmpty() && rate.isEmpty() && stage == null) {
            return sections;
        }

        sections.add(new Section(12, (ctx, f, x, y, w, alpha) -> {
            Paint.mark(ctx, x, y, 11, Theme.GOLD, Theme.TEAL, alpha);
            Fonts.draw(ctx, f, "HolyHelper", Fonts.BODY, x + 15, y + 1,
                    Motion.fade(Theme.GOLD, alpha));
            int dot = board.present() ? Theme.GREEN : Theme.TEXT_FAINT;
            Paint.roundRect(ctx, x + w - 5, y + 3, 5, 5, 2, Motion.fade(dot, alpha));
        }));

        if (hasBalance) {
            sections.add(new Section(CAP + 2 + LINE, (ctx, f, x, y, w, alpha) -> {
                Fonts.label(ctx, f, "баланс", x, y, Motion.fade(Theme.TEXT_FAINT, alpha));
                balance(ctx, f, x, y + CAP + 2, board, alpha);
            }));
        }

        if (regular.isPresent()) {
            sections.add(cycle("обычные торги", regular.get(), timer.remainingFraction(false),
                    Theme.GOLD, Theme.GOLD_DEEP));
        }
        if (special.isPresent()) {
            sections.add(cycle("особые торги", special.get(), timer.remainingFraction(true),
                    Theme.TEAL, Theme.TEAL_DEEP));
        }

        if (rate.isPresent()) {
            List<Double> points = mod.rates().recentRates(SPARK_POINTS);
            boolean spark = points.size() >= 2;
            Optional<Double> deviation = mod.rates().deviationPercent(RATE_WINDOW);
            int height = CAP + 2 + LINE + (spark ? 2 + SPARK_HEIGHT : 0);

            sections.add(new Section(height, (ctx, f, x, y, w, alpha) -> {
                Fonts.label(ctx, f, "курс монеток", x, y, Motion.fade(Theme.TEXT_FAINT, alpha));
                if (deviation.isPresent()) {
                    // Цвет говорит только «обратите внимание». Что тут выгодно, зависит
                    // от того, что игрок собирается делать, и мод этого не знает.
                    int color = Math.abs(deviation.get()) >= NOTABLE_PERCENT
                            ? Theme.WARN : Theme.TEXT_FAINT;
                    Fonts.drawRight(ctx, f, String.format(Locale.ROOT, "%+.1f%%", deviation.get()),
                            Fonts.NUM, x + w, y, Motion.fade(color, alpha));
                }
                Fonts.draw(ctx, f, money(Math.round(rate.get())), Fonts.NUM, x, y + CAP + 2,
                        Motion.fade(Theme.TEXT, alpha));
                if (spark) {
                    Paint.line(ctx, x, y + CAP + 2 + LINE + 2, w, SPARK_HEIGHT, points,
                            Motion.fade(Theme.GOLD, alpha * 0.85), alpha);
                }
            }));
        }

        if (stage != null) {
            sections.add(new Section(CAP + 2 + LINE + 2 + BAR, (ctx, f, x, y, w, alpha) -> {
                Fonts.label(ctx, f, "этап #" + stage.number(), x, y,
                        Motion.fade(Theme.TEXT_FAINT, alpha));
                Fonts.draw(ctx, f, money(stage.progress()), Fonts.NUM, x, y + CAP + 2,
                        Motion.fade(Theme.GREEN, alpha));
                Fonts.drawRight(ctx, f, "из " + money(stage.goal()), Fonts.NUM, x + w, y + CAP + 2,
                        Motion.fade(Theme.TEXT_FAINT, alpha));
                Paint.bar(ctx, x, y + CAP + 2 + LINE + 2, w, BAR, stage.completion(),
                        Theme.GREEN, Theme.TEAL, alpha);
            }));
        }

        return sections;
    }

    /**
     * Секция цикла: подпись, остаток и полоса.
     * <p>
     * Подпись и остаток стоят в одной строке — они помещаются рядом только потому,
     * что подпись набрана мелким капсом. Прежняя раскладка мерилась на глаз обычным
     * шрифтом, не влезала, и слова наезжали друг на друга.
     */
    private static Section cycle(String label, Duration left, Optional<Double> fraction,
                                 int from, int to) {
        boolean bar = fraction.isPresent();
        return new Section(CAP + 2 + (bar ? BAR : 0), (ctx, f, x, y, w, alpha) -> {
            Fonts.label(ctx, f, label, x, y, Motion.fade(Theme.TEXT_FAINT, alpha));
            Fonts.drawRight(ctx, f, human(left), Fonts.NUM, x + w, y - 1, Motion.fade(from, alpha));
            // Без знаменателя полосу не рисуем: доля от неизвестного — это не «пусто»,
            // это выдумка.
            fraction.ifPresent(value ->
                    Paint.bar(ctx, x, y + CAP + 2, w, BAR, value, to, from, alpha));
        });
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
        int width = Fonts.draw(ctx, font, money(value), Fonts.NUM, x + 7, y,
                Motion.fade(Theme.TEXT, alpha));
        return x + 7 + width + 6;
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
