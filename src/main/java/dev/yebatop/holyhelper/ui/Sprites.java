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
 * ограничение целиком, и заодно приносит то, чего у клиента нет вовсе:
 * свечение вокруг знака запечено в саму картинку.
 * <p>
 * Рисуются картинки из {@code scripts/make-mark.py} — исходником считается
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

    private Sprites() {
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
