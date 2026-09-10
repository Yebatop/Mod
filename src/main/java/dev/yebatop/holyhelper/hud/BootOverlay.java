package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.ui.Fonts;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Paint;
import dev.yebatop.holyhelper.ui.Sprites;
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

/**
 * Экран запуска: что мод проверил, прежде чем начать работать.
 * <p>
 * Он не только красивый. Мод молча читает чужие окна, и игроку неоткуда узнать,
 * жив ли он вообще, пока не откроется первое окно Скупца. Здесь сразу видно: тот
 * ли сервер, отвечает ли LiteAPI, сколько цен накоплено, знает ли мод про ротацию.
 * <p>
 * Рисуется поверх игры и ничего не перехватывает: это не экран в смысле клиента,
 * а слой поверх мира. Управление всё время остаётся у игрока — заслонять ему обзор
 * на входе в анархию было бы дурной шуткой, поэтому подложка полупрозрачная
 * и уходит сама.
 */
public final class BootOverlay {

    public static final String FEATURE = "boot-screen";

    /** Сколько всего живёт экран и когда начинает гаснуть. */
    private static final long HOLD_MILLIS = 3_600L;
    private static final long FADE_MILLIS = 900L;

    private static final long REVEAL_STEP = 110L;
    private static final long REVEAL_LENGTH = 460L;

    private static final int PANEL_WIDTH = 210;
    private static final int MARK = 34;
    private static final int LINE = 12;

    private static long startedAt;

    private record Row(String label, String value, int color) {
    }

    private BootOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.of(HolyHelperClient.MOD_ID, "boot"),
                BootOverlay::render);
    }

    /** Запускает показ. Зовётся, когда мод понял, на каком он сервере. */
    public static void show() {
        startedAt = System.currentTimeMillis();
    }

    public static void hide() {
        startedAt = 0;
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        HolyHelperClient mod = HolyHelperClient.instance();

        if (startedAt == 0 || mod == null || client.player == null || client.options.hudHidden) {
            return;
        }
        if (!ServerDetector.onHolyWorld() || !mod.config().bootScreenEnabled) {
            startedAt = 0;
            return;
        }
        if (mod.featureGate().status() == FeatureGate.Status.ANSWERED
                && !mod.featureGate().isAllowed(FEATURE)) {
            startedAt = 0;
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > HOLD_MILLIS + FADE_MILLIS) {
            startedAt = 0;
            return;
        }

        // Общая видимость: держим, потом гасим. Гаснет всё разом, включая подложку.
        double alpha = elapsed <= HOLD_MILLIS
                ? 1
                : 1 - Motion.ease((elapsed - HOLD_MILLIS) / (double) FADE_MILLIS);

        int width = ctx.getScaledWindowWidth();
        int height = ctx.getScaledWindowHeight();
        TextRenderer font = client.textRenderer;

        ctx.fill(0, 0, width, height, Motion.fade(0xD8060810, alpha));

        List<Row> rows = status(mod);
        int panelHeight = 20 + rows.size() * (LINE + 4) + 16;
        int left = (width - PANEL_WIDTH) / 2;
        int top = height / 2 - (MARK + 46 + panelHeight) / 2;

        double markReveal = Motion.reveal(elapsed, 0, REVEAL_LENGTH);
        if (markReveal > 0) {
            Sprites.mark(ctx, left + (PANEL_WIDTH - MARK) / 2, top + Motion.rise(markReveal, 8),
                    MARK, markReveal * alpha);
        }

        double titleReveal = Motion.reveal(elapsed, REVEAL_STEP, REVEAL_LENGTH);
        if (titleReveal > 0) {
            int y = top + MARK + 12 + Motion.rise(titleReveal, 8);
            int titleWidth = Fonts.width(font, "HolyHelper", Fonts.DISPLAY);
            Fonts.draw(ctx, font, "HolyHelper", Fonts.DISPLAY,
                    left + (PANEL_WIDTH - titleWidth) / 2, y,
                    Motion.fade(Theme.GOLD, titleReveal * alpha));

            int subWidth = Fonts.labelWidth(font, "экономический помощник Прайм Анархии");
            Fonts.label(ctx, font, "экономический помощник Прайм Анархии",
                    left + (PANEL_WIDTH - subWidth) / 2, y + 20,
                    Motion.fade(Theme.TEXT_FAINT, titleReveal * alpha));
        }

        double panelReveal = Motion.reveal(elapsed, REVEAL_STEP * 2, REVEAL_LENGTH);
        if (panelReveal <= 0) {
            return;
        }
        int panelTop = top + MARK + 46 + Motion.rise(panelReveal, 10);
        Paint.panel(ctx, left, panelTop, PANEL_WIDTH, panelHeight, 5,
                Theme.PANEL_SOLID, panelReveal * alpha);
        Paint.sheen(ctx, left, panelTop, PANEL_WIDTH, 5,
                System.currentTimeMillis(), 8_000L, panelReveal * alpha);

        int rowY = panelTop + 12;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double reveal = Motion.reveal(elapsed, REVEAL_STEP * (3 + i), REVEAL_LENGTH);
            if (reveal <= 0) {
                continue;
            }
            double visible = reveal * alpha;
            ctx.fill(left + 12, rowY + 4, left + 15, rowY + 7, Motion.fade(row.color(), visible));
            Fonts.draw(ctx, font, row.label(), Fonts.BODY, left + 21, rowY,
                    Motion.fade(Theme.TEXT_DIM, visible));
            Fonts.drawRight(ctx, font, row.value(), Fonts.NUM, left + PANEL_WIDTH - 12, rowY,
                    Motion.fade(row.color(), visible));
            rowY += LINE + 4;
        }

        double barReveal = Motion.reveal(elapsed, REVEAL_STEP * (3 + rows.size()), 900);
        if (barReveal > 0) {
            Paint.bar(ctx, left + 12, panelTop + panelHeight - 11, PANEL_WIDTH - 24, 3,
                    barReveal, Theme.GOLD_DEEP, Theme.TEAL, alpha);
        }
    }

    /** Строки проверки. Только то, что мод действительно знает на этот момент. */
    private static List<Row> status(HolyHelperClient mod) {
        List<Row> rows = new ArrayList<>();

        String server = mod.board().snapshot().server();
        rows.add(new Row("Сервер", server.isEmpty()
                ? ServerDetector.currentAddress().orElse("?") : server, Theme.GREEN));

        rows.add(switch (mod.featureGate().status()) {
            case ANSWERED -> new Row("LiteAPI", mod.featureGate().blocked().isEmpty()
                    ? "ограничений нет" : mod.featureGate().blocked().size() + " в блоке", Theme.GREEN);
            case NO_CHANNEL -> new Row("LiteAPI", "канала нет", Theme.TEXT_FAINT);
            case NO_ANSWER -> new Row("LiteAPI", "молчит", Theme.WARN);
            case NOT_ASKED -> new Row("LiteAPI", "спрашиваем", Theme.TEXT_FAINT);
        });

        rows.add(mod.prices().itemCount() == 0
                ? new Row("База цен", "пуста", Theme.TEXT_FAINT)
                : new Row("База цен", mod.prices().observationCount() + " набл.", Theme.GREEN));

        Duration left = mod.rotation().remaining(false).orElse(null);
        rows.add(left == null
                ? new Row("Скупец", "окно не открывали", Theme.TEXT_FAINT)
                : new Row("Скупец", left.toHours() + " ч " + left.toMinutesPart() + " мин", Theme.GOLD));

        return rows;
    }
}
