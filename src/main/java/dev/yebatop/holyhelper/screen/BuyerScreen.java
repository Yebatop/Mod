package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Surface;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.Click;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Терминал, открытый из мира, когда никакого окна не открыто.
 * <p>
 * Рисует ровно то же, что слой поверх окна торговли, — общий {@link Terminal}.
 * Разница только в том, кто держит ввод.
 */
public final class BuyerScreen extends Screen {

    private static final int MARGIN = 8;

    private final Terminal terminal = new Terminal(List.of(new BuyerView(), new RateView(), new HistoryView()));

    /**
     * Где был курсор в последнем кадре.
     * <p>
     * Клик приходит без координат — они лежат в объекте, имена полей которого
     * маппинги не называют. Гадать не нужно: положение курсора и так приходит
     * в отрисовку каждый кадр, а разница в один кадр для попадания по вкладке
     * значения не имеет.
     */
    private int pointerX;
    private int pointerY;

    public BuyerScreen() {
        super(Text.literal("HolyHelper"));
    }

    @Override
    protected void init() {
        terminal.open();
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
        pointerX = Surface.toUnits(mouseX);
        pointerY = Surface.toUnits(mouseY);
        ctx.fill(0, 0, this.width, this.height, Motion.fade(0xC8060810, 1));
        terminal.render(ctx, this.textRenderer, this.width, this.height, MARGIN);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        terminal.scroll(vertical);
        return true;
    }

    @Override
    public boolean mouseClicked(Click input, boolean doubled) {
        if (terminal.click(pointerX, pointerY)) {
            return true;
        }
        return super.mouseClicked(input, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        // Той же клавишей, что открыли. Иначе на телефоне, где Esc нарисован не
        // всегда, экран становится ловушкой.
        if (Keys.matchesBuyer(input.getKeycode())) {
            this.close();
            return true;
        }
        // Вкладки стрелками: мышь на телефоне есть не всегда, а до вкладки
        // дотянуться надо.
        if (input.getKeycode() == GLFW.GLFW_KEY_RIGHT || input.getKeycode() == GLFW.GLFW_KEY_TAB) {
            terminal.step(1);
            return true;
        }
        if (input.getKeycode() == GLFW.GLFW_KEY_LEFT) {
            terminal.step(-1);
            return true;
        }
        return super.keyPressed(input);
    }
}
