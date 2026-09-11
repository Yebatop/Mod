package dev.yebatop.holyhelper.ui;

import dev.yebatop.holyhelper.HolyHelperClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Картинки мода — то немногое, что нарисовано заранее, а не собрано из
 * прямоугольников на месте.
 * <p>
 * Пока знак складывался в игре из отрезков, он выглядел ровно так, как и был
 * сделан: блочно. Дело не в старании — знак почти весь состоит из диагоналей,
 * а диагональ из прямоугольников гладкой не бывает. Текстура снимает это
 * ограничение целиком.
 * <p>
 * Здесь же живёт то, чего клиент не умеет в принципе: размытие. Свечение вокруг
 * знака и тень под панелью в макете дают размытием, а у клиента его нет —
 * поэтому и то и другое нарисовано заранее.
 * <p>
 * Рисуются картинки из {@code scripts/make-textures.py} — исходником считается
 * скрипт, а не png. Поправить знак значит поправить скрипт и перегенерировать,
 * иначе через месяц никто не вспомнит, откуда взялись эти пиксели.
 */
public final class Sprites {

    private static final Identifier MARK =
            Identifier.of(HolyHelperClient.MOD_ID, "textures/gui/mark.png");
    private static final Identifier MARK_LARGE =
            Identifier.of(HolyHelperClient.MOD_ID, "textures/gui/mark_large.png");

    private static final int MARK_SIZE = 96;
    private static final int MARK_LARGE_SIZE = 256;

    /**
     * С какого размера берётся крупная картинка.
     * <p>
     * Текстуры интерфейса Minecraft хранит без мип-уровней, поэтому сильное
     * уменьшение бьётся в кашу, а сильное увеличение мылит. Один размер на все
     * случаи здесь не работает: в шапке карточки знак занимает восемнадцать
     * пикселей, а на экране запуска — под сотню.
     */
    private static final int LARGE_FROM = 40;

    /**
     * Во сколько раз холст картинки больше самого знака.
     * <p>
     * Вокруг знака оставлено прозрачное поле под свечение — иначе оно упёрлось
     * бы в край текстуры заметной ступенькой. Значит рисовать картинку нужно
     * крупнее, чем занимает знак, и с поправкой на это поле, иначе разметка
     * поедет: место под знак вызывающий код считает по самому знаку.
     */
    private static final float CANVAS = 256f / 208f;

    private static final Identifier SHADOW =
            Identifier.of(HolyHelperClient.MOD_ID, "textures/gui/shadow.png");

    private static final int SHADOW_SIZE = 128;

    /**
     * Кусок картинки тени, который не растягивается: в нём лежит и размытие
     * наружу, и скругление угла самой панели.
     */
    private static final int SHADOW_CORNER = 56;

    /** Тот же кусок на экране. Вчетверо меньше — отсюда и все остальные размеры. */
    private static final int SHADOW_CORNER_ON_SCREEN = 14;

    /** Насколько тень выходит за панель. Ровно поле размытия из картинки. */
    public static final int SHADOW_SPREAD = 8;

    private Sprites() {
    }

    /**
     * Мягкая тень под панель.
     * <p>
     * В макете тень есть у каждой панели, и именно она отделяет её от того, что
     * лежит под ней. В игре её не было вовсе: клиент умеет заливку и текст, а
     * размытия у него нет. Нарисованная заранее — есть.
     * <p>
     * Одной картинкой на панель любой формы не обойтись: растянув её, растянули бы
     * и размытие в углах, и тень поехала бы вслед за пропорциями. Поэтому картинка
     * режется на девять кусков — четыре угла ложатся как есть, четыре края и
     * середина тянутся. Середина однородна, тянуть её безопасно.
     */
    public static void shadow(DrawContext ctx, int x, int y, int w, int h, double alpha) {
        int outer = SHADOW_CORNER_ON_SCREEN;
        int left = x - SHADOW_SPREAD;
        int top = y - SHADOW_SPREAD;
        int width = w + SHADOW_SPREAD * 2;
        int height = h + SHADOW_SPREAD * 2;

        int midWidth = width - outer * 2;
        int midHeight = height - outer * 2;
        if (midWidth <= 0 || midHeight <= 0) {
            // Панель уже своей же тени — рисовать нечего, и растягивать в минус
            // клиент не станет.
            return;
        }
        int far = SHADOW_SIZE - SHADOW_CORNER;
        int midSource = SHADOW_SIZE - SHADOW_CORNER * 2;
        int color = Motion.fade(0xFFFFFFFF, alpha);

        piece(ctx, 0, 0, SHADOW_CORNER, SHADOW_CORNER, left, top, outer, outer, color);
        piece(ctx, far, 0, SHADOW_CORNER, SHADOW_CORNER, left + width - outer, top, outer, outer, color);
        piece(ctx, 0, far, SHADOW_CORNER, SHADOW_CORNER, left, top + height - outer, outer, outer, color);
        piece(ctx, far, far, SHADOW_CORNER, SHADOW_CORNER,
                left + width - outer, top + height - outer, outer, outer, color);

        piece(ctx, SHADOW_CORNER, 0, midSource, SHADOW_CORNER, left + outer, top, midWidth, outer, color);
        piece(ctx, SHADOW_CORNER, far, midSource, SHADOW_CORNER,
                left + outer, top + height - outer, midWidth, outer, color);
        piece(ctx, 0, SHADOW_CORNER, SHADOW_CORNER, midSource, left, top + outer, outer, midHeight, color);
        piece(ctx, far, SHADOW_CORNER, SHADOW_CORNER, midSource,
                left + width - outer, top + outer, outer, midHeight, color);

        piece(ctx, SHADOW_CORNER, SHADOW_CORNER, midSource, midSource,
                left + outer, top + outer, midWidth, midHeight, color);
    }

    private static void piece(DrawContext ctx, int u, int v, int sourceWidth, int sourceHeight,
                              int x, int y, int width, int height, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SHADOW, x, y, u, v, width, height,
                sourceWidth, sourceHeight, SHADOW_SIZE, SHADOW_SIZE, color);
    }

    private static final Identifier GLYPHS =
            Identifier.of(HolyHelperClient.MOD_ID, "textures/gui/glyphs.png");

    private static final int GLYPH = 64;
    private static final int GLYPH_SHEET = GLYPH * 4;

    /**
     * Мелкие значки мода. Порядок совпадает с порядком клеток в картинке —
     * менять его можно только вместе с {@code scripts/make-textures.py}.
     */
    public enum Glyph {
        /** Особое предложение Скупца. */
        SPARK,
        /** Монетка. */
        COIN,
        /** Гем. */
        GEM,
        /** Жетон. */
        TOKEN
    }

    /**
     * Рисует мелкий значок.
     * <p>
     * Значки лежат белыми, а цвет накладывается здесь: одна картинка служит и
     * золотой монетке, и сиреневому гему. Прозрачность появления входит в тот же
     * цвет — отдельного довода под неё не нужно.
     * <p>
     * Мелкой сетки здесь не требуется: картинку клиент растягивает уже по
     * пикселям монитора, и полутона на краях он считает сам.
     */
    public static void glyph(DrawContext ctx, Glyph glyph, int x, int y, int size, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, GLYPHS, x, y,
                glyph.ordinal() * (float) GLYPH, 0f, size, size,
                GLYPH, GLYPH, GLYPH_SHEET, GLYPH, color);
    }

    /**
     * Рисует фирменный знак так, чтобы он занял квадрат {@code size} — свечение
     * выходит за этот квадрат, как и положено свечению.
     *
     * @param alpha прозрачность появления, от 0 до 1
     */
    public static void mark(DrawContext ctx, int x, int y, int size, double alpha) {
        int box = Math.round(size * CANVAS);
        int inset = (box - size) / 2;
        int source = size >= LARGE_FROM ? MARK_LARGE_SIZE : MARK_SIZE;
        Identifier texture = size >= LARGE_FROM ? MARK_LARGE : MARK;

        // Цвет здесь — не окраска, а только прозрачность: знак приходит из
        // картинки уже в своих цветах, и перекрашивать его незачем.
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                x - inset, y - inset, 0f, 0f, box, box,
                source, source, source, source, Motion.fade(0xFFFFFFFF, alpha));
    }
}
