package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.ui.Motion;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
 * Экран Скупца, открытый из мира, когда никакого окна не открыто.
 * <p>
 * Рисует ровно то же, что слой поверх окна торговли, — общий {@link BuyerView}.
 * Разница только в том, кто держит ввод.
 */
public final class BuyerScreen extends Screen {

    private static final int MARGIN = 8;

    private final BuyerView view = new BuyerView();

    public BuyerScreen() {
        super(Text.literal("Скупец"));
    }

    @Override
    protected void init() {
        view.open();
    }

    /**
     * Свой фон вместо ванильного.
     * <p>
     * Клиент по умолчанию замыливает мир за экраном. Здесь это лишнее: подложка
     * своя, полупрозрачная, и сквозь неё видно, что происходит вокруг, — на анархии
     * это важнее эффекта.
     */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
    }

    /**
     * Экран не ставит игру на паузу: пока игрок смотрит в таблицу цен, мир вокруг
     * живёт, и делать вид, что он замер, нельзя.
     */
    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, Motion.fade(0xC8060810, 1));
        view.renderIn(ctx, this.textRenderer, this.width, this.height, MARGIN);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        view.scroll(vertical);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        // Той же клавишей, что открыли. Иначе на телефоне, где Esc нарисован не
        // всегда, экран становится ловушкой.
        if (Keys.matchesBuyer(input.getKeycode())) {
            this.close();
            return true;
        }
        return super.keyPressed(input);
    }
}
