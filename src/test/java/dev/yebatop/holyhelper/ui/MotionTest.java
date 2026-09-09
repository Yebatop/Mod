package dev.yebatop.holyhelper.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionTest {

    @Test
    void сглаживаниеЗакреплёноПоКонцам() {
        assertEquals(0, Motion.ease(0));
        assertEquals(1, Motion.ease(1));
        assertEquals(0, Motion.ease(-3));
        assertEquals(1, Motion.ease(42));
    }

    @Test
    void сглаживаниеМонотонно() {
        double previous = -1;
        for (int i = 0; i <= 100; i++) {
            double value = Motion.ease(i / 100.0);
            assertTrue(value >= previous, "провал на " + i + ": " + value + " < " + previous);
            previous = value;
        }
    }

    @Test
    void кривая() {
        // Кривая из макета быстро стартует и долго затухает. Если однажды это
        // перестанет выполняться, значит контрольные точки съехали и движение мода
        // разошлось с макетами.
        assertTrue(Motion.ease(0.25) > 0.5, "начало слишком вялое: " + Motion.ease(0.25));
        assertTrue(Motion.ease(0.75) > 0.95, "хвост не дотягивает: " + Motion.ease(0.75));
    }

    @Test
    void появлениеЖдётСвоейОчереди() {
        assertEquals(0, Motion.reveal(0, 200, 400));
        assertEquals(0, Motion.reveal(200, 200, 400));
        assertEquals(1, Motion.reveal(600, 200, 400));
        assertEquals(1, Motion.reveal(5000, 200, 400));
        assertTrue(Motion.reveal(400, 200, 400) > 0);
    }

    @Test
    void нулеваяДлительностьНеДелитНаНоль() {
        assertEquals(0, Motion.reveal(100, 200, 0));
        assertEquals(1, Motion.reveal(300, 200, 0));
    }

    @Test
    void сдвигУходитВНольККонцу() {
        assertEquals(10, Motion.rise(0, 10));
        assertEquals(0, Motion.rise(1, 10));
    }

    @Test
    void прозрачностьМеняетТолькоАльфу() {
        assertEquals(0x80F2B45C, Motion.fade(0xFFF2B45C, 0.5019607843));
        assertEquals(0x00F2B45C, Motion.fade(0xFFF2B45C, 0));
        assertEquals(0xFFF2B45C, Motion.fade(0xFFF2B45C, 1));
        // Значение вне отрезка не должно переполнять байт и красить панель в мусор.
        assertEquals(0xFFF2B45C, Motion.fade(0xFFF2B45C, 4));
    }

    @Test
    void смешиваниеЦветов() {
        assertEquals(0xFF000000, Motion.mix(0xFF000000, 0xFFFFFFFF, 0));
        assertEquals(0xFFFFFFFF, Motion.mix(0xFF000000, 0xFFFFFFFF, 1));
        assertEquals(0xFF808080, Motion.mix(0xFF000000, 0xFFFFFFFF, 0.5019607843));
        // Альфа смешивается наравне с цветом, иначе полупрозрачные концы градиента
        // обрывались бы посередине.
        assertEquals(0x80FFFFFF, Motion.mix(0x00FFFFFF, 0xFFFFFFFF, 0.5019607843));
    }

    @Test
    void бликПробегаетИЖдёт() {
        assertTrue(Motion.sheen(0, 8000) < 0, "блик обязан начинаться левее панели");
        assertTrue(Motion.sheen(3500, 8000) > 1, "к концу пробега блик уходит за правый край");
        assertTrue(Double.isNaN(Motion.sheen(5000, 8000)), "между пробегами рисовать нечего");
        assertTrue(Double.isNaN(Motion.sheen(1000, 0)), "нулевой период — не повод делить на ноль");
    }

    @Test
    void бликПовторяетсяКаждыйПериод() {
        assertEquals(Motion.sheen(1000, 8000), Motion.sheen(9000, 8000));
        // Отрицательное время встречается: панель могла появиться раньше отсчёта.
        assertFalse(Double.isNaN(Motion.sheen(-7000, 8000)));
    }
}
