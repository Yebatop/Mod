package dev.yebatop.holyhelper.screen;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.ServerDetector;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Клавиши, которыми открываются экраны мода.
 * <p>
 * По умолчанию G, H и J — рядом и не заняты клиентом. Переназначаются в обычных
 * настройках управления, поэтому на телефоне их можно повесить на экранные кнопки.
 * <p>
 * Экран открывается только на HolyWorld: на чужом сервере мод спит целиком, и
 * показывать там пустую таблицу незачем.
 */
public final class Keys {

    private static KeyBinding buyer;

    private Keys() {
    }

    public static void register() {
        KeyBinding.Category category =
                KeyBinding.Category.create(Identifier.of(HolyHelperClient.MOD_ID, "main"));

        buyer = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.holyhelper.buyer", GLFW.GLFW_KEY_G, category));
    }

    /**
     * Та ли это клавиша, которой открывается Скупец.
     * <p>
     * Нужна слою поверх окна: пока окно открыто, клиент обычные привязки не
     * опрашивает, и событие клавиши приходит сырым кодом.
     */
    public static boolean matchesBuyer(int keyCode) {
        if (buyer == null) {
            return false;
        }
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(buyer);
        return bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() == keyCode;
    }

    /**
     * Как называется клавиша Скупца сейчас.
     * <p>
     * В подсказке на экране она была вписана буквой «g». Это враньё сразу после
     * того, как игрок переназначит её в настройках, — а переназначает он её
     * обязательно, если играет не с клавиатуры.
     */
    public static String buyerKeyName() {
        return buyer == null
                ? "?"
                : KeyBindingHelper.getBoundKeyOf(buyer).getLocalizedText().getString();
    }

    /** Зовётся из тика клиента. */
    public static void tick(MinecraftClient client) {
        if (buyer == null) {
            return;
        }
        boolean pressed = false;
        while (buyer.wasPressed()) {
            pressed = true;
        }
        if (!pressed || client.currentScreen != null || !ServerDetector.onHolyWorld()) {
            return;
        }
        client.setScreen(new BuyerScreen());
    }
}
