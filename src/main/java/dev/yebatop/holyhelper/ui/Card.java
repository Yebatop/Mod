package dev.yebatop.holyhelper.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.time.Duration;
import java.time.Instant;

/**
 * Карточка — общая рама всех экранов мода: подложка, панель, шапка со знаком
 * и заголовком, подвал.
 * <p>
 * Это не экран клиента, а рисование в заданный прямоугольник. Ровно поэтому одно
 * и то же содержимое показывается и отдельным экраном, и слоем поверх открытого
 * окна Скупца: во втором случае подменять окно нельзя — клиент закрыл бы контейнер
 * и отправил серверу пакет, а мод не отправляет игровых пакетов.
 * <p>
 * Экранов трое, различаются они содержимым, а не оформлением. Держать раму в одном
 * месте — единственный способ не разъехаться: иначе на третьем экране скругление
 * окажется другим, и это заметно сразу.
 */
public final class Card {

    public static final int PAD = 9;
    public static final int RADIUS = 6;
    public static final int HEADER = 26;
    public static final int FOOTER = 12;
    public static final int LINE = 9;
    public static final int CAP = 8;
    public static final int GAP = 6;

    /** Поле по бокам буквы на клавише. */
    private static final int KEY_PAD = 4;

    public static final long REVEAL_STEP = 55L;
    public static final long REVEAL_LENGTH = 380L;

    private static final long SHEEN_PERIOD = 8_000L;

    /** Что рисуется внутри отведённой области. */
    @FunctionalInterface
    public interface Area {
        void paint(DrawContext ctx, TextRenderer font, int x, int y, int width, int height, double alpha);
    }

    private Card() {
    }

    /**
     * Рисует карточку целиком.
     *
     * @param elapsed сколько прошло с открытия, мс — от этого идёт сборка
     */
    public static void draw(DrawContext ctx, TextRenderer font, int x, int y, int width, int height,
                            String title, String subtitle, int accent, long elapsed,
                            Area body, Area header, Area footer) {
        double reveal = Motion.reveal(elapsed, 0, REVEAL_LENGTH);
        if (reveal <= 0) {
            return;
        }
        int top = y + Motion.rise(reveal, 12);

        Paint.panel(ctx, x, top, width, height, RADIUS, Theme.PANEL_SOLID, reveal);
        Paint.sheen(ctx, x, top, width, RADIUS, System.currentTimeMillis(), SHEEN_PERIOD, reveal);

        int inner = width - PAD * 2;
        int left = x + PAD;

        Sprites.mark(ctx, left, top + PAD + 2, 18, reveal);
        Fonts.draw(ctx, font, title, Fonts.DISPLAY, left + 24, top + PAD, Motion.fade(Theme.TEXT, reveal));
        Fonts.label(ctx, font, subtitle, left + 24, top + PAD + 17, Motion.fade(Theme.TEXT_FAINT, reveal));
        if (header != null) {
            header.paint(ctx, font, left, top + PAD, inner, HEADER, reveal);
        }

        int bodyTop = top + PAD + HEADER + GAP;
        int bodyHeight = height - PAD * 2 - HEADER - GAP - FOOTER;
        if (body != null && bodyHeight > 0) {
            body.paint(ctx, font, left, bodyTop, inner, bodyHeight, reveal);
        }

        Paint.separator(ctx, left, top + height - PAD - FOOTER + 2, inner, reveal * 0.6);
        if (footer != null) {
            footer.paint(ctx, font, left, top + height - PAD - FOOTER + 7, inner, FOOTER, reveal);
        }
    }

    /** Сколько места займёт клавиша. Нужно тем, кто прижимает её к правому краю. */
    public static int keyWidth(TextRenderer font, String name) {
        return Fonts.width(font, name, Fonts.NUM) + KEY_PAD * 2;
    }

    /**
     * Название клавиши в виде самой клавиши: скруглённая площадка со светлой
     * кромкой по верху.
     * <p>
     * Написанное просто буквой, оно теряется в строке подсказок и читается как
     * часть фразы. Площадка отделяет «что нажать» от «что при этом будет» без
     * единого лишнего слова.
     *
     * @return сколько места заняла клавиша
     */
    public static int key(DrawContext ctx, TextRenderer font, String name, int x, int y,
                          double alpha) {
        int width = keyWidth(font, name);
        int height = LINE + 3;

        Paint.roundRect(ctx, x, y, width, height, 2, Motion.fade(Theme.BORDER_SOLID, alpha));
        Paint.roundRect(ctx, x + 1, y + 1, width - 2, height - 2, 2,
                Motion.fade(Motion.lighten(Theme.PANEL_SOLID, 0.09), alpha),
                Motion.fade(Theme.PANEL_SOLID, alpha));
        ctx.fill(x + 2, y + 1, x + width - 2, y + 2, Motion.fade(Theme.EDGE, alpha));

        Fonts.draw(ctx, font, name, Fonts.NUM, x + KEY_PAD, y + 2,
                Motion.fade(Theme.TEXT, alpha));
        return width;
    }

    /** Возраст снимка словами. Секунды важнее всего: остатки меняются быстро. */
    public static String age(Instant seenAt) {
        if (seenAt == null || seenAt.equals(Instant.EPOCH)) {
            return "окно ещё не открывали";
        }
        long seconds = Math.max(0, Duration.between(seenAt, Instant.now()).toSeconds());
        if (seconds < 60) {
            return "снято " + seconds + " с назад";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "снято " + minutes + " мин назад";
        }
        return "снято " + (minutes / 60) + " ч назад — числа устарели";
    }

    /**
     * Возраст коротко — и только когда он уже что-то значит.
     * <p>
     * Нужен там, где число не идёт само. Таймер ротации мод превращает в момент
     * обновления, и часы дальше тикают сами; а прогресс этапа наращивает сервер,
     * и мод его не видит — ни продаж, ни того, сколько за них засчитали. Значит
     * число замерзает на последнем снимке, и без подписи оно однажды соврёт молча.
     *
     * @return пустая строка, пока свежо
     */
    public static String staleness(Instant seenAt, Duration quiet) {
        if (seenAt == null || seenAt.equals(Instant.EPOCH)) {
            return "";
        }
        Duration age = Duration.between(seenAt, Instant.now());
        if (age.compareTo(quiet) < 0) {
            return "";
        }
        long hours = age.toHours();
        return hours > 0 ? hours + " ч назад" : age.toMinutes() + " мин назад";
    }

    /** Разряды через пробел: без них шестизначные числа не читаются с одного взгляда. */
    public static String money(long value) {
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

    /** Остаток времени коротко. */
    public static String human(Duration left) {
        long hours = left.toHours();
        long minutes = left.toMinutesPart();
        return hours > 0 ? hours + " ч " + minutes + " мин" : minutes + " мин";
    }
}
