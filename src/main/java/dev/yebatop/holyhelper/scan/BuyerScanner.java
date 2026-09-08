package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Читает окна Скупца, открытые самим игроком.
 * <p>
 * Сканер именно читает: он не кликает по слотам, ничего не открывает и не листает.
 * Пока окно закрыто, он не делает вообще ничего — это условие, при котором мод
 * вправе существовать на сервере с такими правилами.
 * <p>
 * Последний снимок сохраняется, и это не удобство, а необходимость: с открытым
 * окном чат не открыть, и команду набрать негде. Значит читать нужно, пока окно
 * на экране, а показывать — после того как игрок его закрыл. Чтобы снимок не врал,
 * рядом лежит время съёмки, и команда всегда пишет, насколько он свежий.
 * <p>
 * Товары опознаются по подсказке, а не по номеру слота. Раскладка сеток у разных
 * разделов разная, стекло-разделители стоят где попало, а «Назад» и книга справки
 * сидят среди товаров, — но объём приёма есть только у настоящего предложения.
 */
public final class BuyerScanner {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/buyer");

    private final Patterns patterns;
    private final BuyerParser parser;

    private volatile Snapshot last = Snapshot.EMPTY;

    public enum Kind {
        /** Открытого окна нет либо оно чужое. */
        NONE,
        /** «Торговля и заработок» — сетка товаров. */
        TRADE,
        /** «Множители торговли». */
        MULTIPLIERS,
        /** «Этапы и награды». */
        STAGES,
        /** «Скупец» — меню с разделами. */
        HUB
    }

    public record Snapshot(
            Kind kind,
            String title,
            List<BuyerParser.Offer> offers,
            List<BuyerParser.Multiplier> multipliers,
            Instant seenAt) {

        public static final Snapshot EMPTY =
                new Snapshot(Kind.NONE, "", List.of(), List.of(), Instant.EPOCH);
    }

    public BuyerScanner(Patterns patterns) {
        this.patterns = patterns;
        this.parser = new BuyerParser(patterns);
    }

    public BuyerParser parser() {
        return parser;
    }

    /** Последний снимок окна Скупца. Пустой, если игрок ещё ни одного не открывал. */
    public Snapshot last() {
        return last;
    }

    /**
     * Читает окно, если оно сейчас открыто. Зовётся из тика клиента.
     * <p>
     * Чужие экраны и закрытый инвентарь снимок не затирают: иначе он пропадал бы
     * ровно в тот момент, когда игрок закрывает окно, чтобы посмотреть результат.
     */
    public void tickScan() {
        Snapshot fresh = scan();
        if (fresh.kind() != Kind.NONE) {
            last = fresh;
        }
    }

    /** Читает то окно, которое открыто прямо сейчас. */
    public Snapshot scan() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen screen = client.currentScreen;
        if (!(screen instanceof HandledScreen<?> handled)) {
            return Snapshot.EMPTY;
        }

        String title = screen.getTitle().getString();
        Kind kind = kindOf(title);
        if (kind == Kind.NONE) {
            return Snapshot.EMPTY;
        }

        List<BuyerParser.Offer> offers = new ArrayList<>();
        List<BuyerParser.Multiplier> multipliers = new ArrayList<>();

        try {
            for (Slot slot : handled.getScreenHandler().slots) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) {
                    continue;
                }
                String name = stack.getName().getString();
                List<String> lore = loreOf(stack);

                // Инвентарь игрока тоже попадает в этот список, но у его предметов
                // нет ни объёма приёма, ни заголовка множителя, так что фильтр по
                // подсказке отсекает их сам — отдельная проверка не нужна.
                parser.parseOffer(name, idOf(stack), lore).ifPresent(offers::add);
                parser.parseMultiplier(name, lore).ifPresent(multipliers::add);
            }
        } catch (RuntimeException e) {
            // Чтение окна — не критичная функция: пусть мод молчит, а не падает.
            LOG.warn("Не удалось прочитать окно «{}»: {}", title, e.toString());
            return Snapshot.EMPTY;
        }

        offers.sort(Comparator.comparingDouble(BuyerParser.Offer::unitPrice).reversed());
        return new Snapshot(kind, title, List.copyOf(offers), List.copyOf(multipliers), Instant.now());
    }

    /** Периоды ротации из книги справки, если она лежит в открытом окне. */
    public Optional<java.time.Duration> rotationPeriod(boolean special) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            return Optional.empty();
        }
        try {
            for (Slot slot : handled.getScreenHandler().slots) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty() || patterns.match("info.title", stack.getName().getString()).isEmpty()) {
                    continue;
                }
                return parser.parseRotationPeriod(loreOf(stack), special);
            }
        } catch (RuntimeException e) {
            LOG.warn("Не удалось прочитать справку: {}", e.toString());
        }
        return Optional.empty();
    }

    private Kind kindOf(String title) {
        if (patterns.match("buyer.tradeTitle", title).isPresent()) {
            return Kind.TRADE;
        }
        if (patterns.match("buyer.multipliersTitle", title).isPresent()) {
            return Kind.MULTIPLIERS;
        }
        if (patterns.match("buyer.stagesTitle", title).isPresent()) {
            return Kind.STAGES;
        }
        if (patterns.match("buyer.hubTitle", title).isPresent()) {
            return Kind.HUB;
        }
        return Kind.NONE;
    }

    /** Строки подсказки предмета. Форматирование отбрасываем — разбор идёт по тексту. */
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

    private static String idOf(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }
}
