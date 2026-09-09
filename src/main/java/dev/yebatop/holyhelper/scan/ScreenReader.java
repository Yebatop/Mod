package dev.yebatop.holyhelper.scan;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Чтение окна, открытого игроком.
 * <p>
 * Единственное место в моде, которое знает, как достать содержимое экрана. Сканеры
 * Скупца и Маркета работают уже с готовыми строками — так их можно проверять тестами,
 * а версия игры меняет код ровно в одном файле.
 * <p>
 * Чтение и только чтение: ни кликов по слотам, ни листания, ни открытия окон.
 */
public final class ScreenReader {

    /** Предмет в слоте: название, идентификатор и строки подсказки. */
    /**
     * Предмет в слоте.
     *
     * @param count размер стопки; для лота Маркета это количество в лоте, и по нему
     *              проверяется, что цена за единицу разобрана верно
     */
    public record Item(String name, String id, int count, List<String> lore) {
    }

    private ScreenReader() {
    }

    /** Заголовок открытого окна. Пусто, если открыто не окно с содержимым. */
    public static Optional<String> title() {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        return screen instanceof HandledScreen<?>
                ? Optional.of(screen.getTitle().getString())
                : Optional.empty();
    }

    /**
     * Все непустые слоты открытого окна.
     * <p>
     * Инвентарь игрока сюда тоже попадает: отделять его по индексам ненадёжно,
     * а сканерам он не мешает — они отбирают нужное по подсказке, которой
     * у обычных предметов нет.
     */
    public static List<Item> items() {
        Screen screen = MinecraftClient.getInstance().currentScreen;
        if (!(screen instanceof HandledScreen<?> handled)) {
            return List.of();
        }

        List<Item> items = new ArrayList<>();
        for (Slot slot : handled.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            items.add(new Item(
                    stack.getName().getString(),
                    Registries.ITEM.getId(stack.getItem()).toString(),
                    stack.getCount(),
                    loreOf(stack)));
        }
        return items;
    }

    /** Строки подсказки. Форматирование отбрасываем — разбор идёт по тексту. */
    private static List<String> loreOf(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Text line : lore.lines()) {
            lines.add(line.getString());
        }
        return lines;
    }
}
