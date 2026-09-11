package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.ui.Motion;
import dev.yebatop.holyhelper.ui.Surface;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Тот же экран Скупца, но слоем поверх открытого окна торговли.
 * <p>
 * Так он и задуман. Подменить окно своим экраном мешает не запрет — в правилах
 * сервера про это ничего нет, — а то, что окно при этом теряется: посмотрел цены
 * и иди открывай торговлю заново. Слоем удобнее: закрыл панель и ты сразу
 * в товарах, а серверу за всё это время не уходит ни одного пакета.
 * <p>
 * Пока слой виден, клики по слотам под ним не проходят. Иначе панель, закрывающая
 * пол-экрана, превратилась бы в способ случайно продать не то.
 */
public final class BuyerOverlay {

    private static final Terminal TERMINAL =
            new Terminal(java.util.List.of(new BuyerView(), new RateView(), new HistoryView()));

    /** Где был курсор в последнем кадре — по нему и попадаем в вкладку. */
    private static int pointerX;
    private static int pointerY;
    private static boolean visible;

    private BuyerOverlay() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof HandledScreen<?>)) {
                return;
            }
            // Новое окно — панель закрыта: она относится к тому окну, над которым
            // её открыли, и переносить её на следующее было бы враньём.
            visible = false;

            ScreenEvents.afterRender(screen).register((rendered, ctx, mouseX, mouseY, delta) -> {
                pointerX = Surface.toUnits(mouseX);
                pointerY = Surface.toUnits(mouseY);
                render(ctx, rendered.width, rendered.height);
            });

            ScreenKeyboardEvents.afterKeyPress(screen).register((target, input) -> {
                if (Keys.matchesBuyer(input.getKeycode()) && ServerDetector.onHolyWorld()) {
                    toggle();
                    return;
                }
                if (!visible) {
                    return;
                }
                if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                    TERMINAL.step(1);
                } else if (input.getKeycode() == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
                    TERMINAL.step(-1);
                }
            });

            // Пока слой виден, щелчки до окна не доходят: панель закрывает
            // пол-экрана, и клик сквозь неё продал бы не то. Но вкладки свои —
            // их мод обрабатывает сам, прежде чем щелчок пропадёт.
            ScreenMouseEvents.allowMouseClick(screen).register((target, input) -> {
                if (!visible) {
                    return true;
                }
                TERMINAL.click(pointerX, pointerY);
                return false;
            });

            // Прокрутку перехватываем до окна, а не после: иначе колесо успело бы
            // пролистать что-нибудь под панелью.
            ScreenMouseEvents.allowMouseScroll(screen).register(
                    (target, mouseX, mouseY, horizontal, vertical) -> {
                        if (!visible) {
                            return true;
                        }
                        TERMINAL.scroll(vertical);
                        return false;
                    });

            ScreenEvents.remove(screen).register(removed -> visible = false);
        });
    }

    private static void toggle() {
        visible = !visible;
        if (visible) {
            TERMINAL.open();
        }
    }

    private static void render(DrawContext ctx, int width, int height) {
        if (!visible) {
            return;
        }
        int margin = 8;
        ctx.fill(0, 0, width, height, Motion.fade(0xD2060810, 1));
        TERMINAL.render(ctx, net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                width, height, margin);
    }
}
