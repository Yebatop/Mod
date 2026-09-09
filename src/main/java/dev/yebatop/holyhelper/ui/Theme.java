package dev.yebatop.holyhelper.ui;

/**
 * Палитра из макетов, переведённая в ARGB.
 * <p>
 * Один источник цвета на весь мод: экраны отличаются раскладкой, а не оттенками.
 * Золото — Скупец, бирюза — Маркет, сирень — множители, зелёный — то, что уже
 * подтверждено наблюдениями.
 * <p>
 * Чего здесь нет и не будет — размытия и мягких теней. Клиент рисует интерфейс
 * плоскими прямоугольниками, и подделывать под них тень значит городить десяток
 * полупрозрачных слоёв ради эффекта, который в игре всё равно не прочитается.
 */
public final class Theme {

    /** Тело панели. Полупрозрачное: под ним живая игра, и прятать её незачем. */
    public static final int PANEL = 0xB80A0C11;

    /** Тело панели, когда она лежит поверх окна, а не поверх мира. */
    public static final int PANEL_SOLID = 0xF00A0C11;

    public static final int BORDER = 0x1FFFFFFF;

    /**
     * Рамка панели. Плотнее прозрачной {@link #BORDER}: она рисуется под телом
     * панели и видна только на скруглении, а полупрозрачная там просто пропадает.
     */
    public static final int BORDER_SOLID = 0x4DFFFFFF;

    /** Внутренняя светлая кромка по верху — та самая «полированная» грань. */
    public static final int EDGE = 0x17FFFFFF;

    /** Бегущий блик. Альфа задаётся при рисовании, здесь только цвет. */
    public static final int SHEEN = 0xFFFFFFFF;

    /** Подложка полос прогресса. */
    public static final int TRACK = 0x1FFFFFFF;

    public static final int GOLD = 0xFFF2B45C;
    public static final int GOLD_DEEP = 0xFFC9862A;
    public static final int TEAL = 0xFF46D3D9;
    public static final int TEAL_DEEP = 0xFF1F9198;
    public static final int GREEN = 0xFF5FD18B;
    public static final int PURPLE = 0xFFC3ADF6;
    public static final int WARN = 0xFFE2C05A;

    public static final int TEXT = 0xFFE8EAED;
    public static final int TEXT_DIM = 0xFF8B93A1;
    public static final int TEXT_FAINT = 0xFF59616F;

    private Theme() {
    }
}
