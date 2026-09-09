package dev.yebatop.holyhelper.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumbersPluralTest {

    private static String позиций(long value) {
        return Numbers.counted(value, "позиция", "позиции", "позиций");
    }

    @Test
    @DisplayName("Обычные окончания")
    void basic() {
        assertEquals("1 позиция", позиций(1));
        assertEquals("2 позиции", позиций(2));
        assertEquals("4 позиции", позиций(4));
        assertEquals("5 позиций", позиций(5));
        assertEquals("8 позиций", позиций(8));
        assertEquals("0 позиций", позиций(0));
    }

    @Test
    @DisplayName("Второй десяток — исключение целиком")
    void teens() {
        // Ровно то место, где наивное правило «смотрим на последнюю цифру» ломается.
        assertEquals("11 позиций", позиций(11));
        assertEquals("12 позиций", позиций(12));
        assertEquals("13 позиций", позиций(13));
        assertEquals("14 позиций", позиций(14));
        assertEquals("15 позиций", позиций(15));
    }

    @Test
    @DisplayName("Сотни и тысячи считаются по хвосту")
    void hundreds() {
        assertEquals("21 позиция", позиций(21));
        assertEquals("22 позиции", позиций(22));
        assertEquals("25 позиций", позиций(25));
        assertEquals("101 позиция", позиций(101));
        assertEquals("111 позиций", позиций(111));
        assertEquals("1002 позиции", позиций(1002));
    }
}
