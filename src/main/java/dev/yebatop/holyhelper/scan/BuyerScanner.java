package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
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
            List<BuyerParser.Stage> stages,
            List<BuyerParser.LockedSlot> lockedSlots,
            BuyerParser.Bonuses bonuses,
            Instant seenAt) {

        public static final Snapshot EMPTY = new Snapshot(
                Kind.NONE, "", List.of(), List.of(), List.of(), List.of(), null, Instant.EPOCH);
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
        String title = ScreenReader.title().orElse("");
        Kind kind = kindOf(title);
        if (kind == Kind.NONE) {
            return Snapshot.EMPTY;
        }

        List<BuyerParser.Offer> offers = new ArrayList<>();
        List<BuyerParser.Multiplier> multipliers = new ArrayList<>();
        List<BuyerParser.Stage> stages = new ArrayList<>();
        List<BuyerParser.LockedSlot> lockedSlots = new ArrayList<>();
        BuyerParser.Bonuses bonuses = null;

        try {
            for (ScreenReader.Item item : ScreenReader.items()) {
                List<String> lore = item.lore();

                // Инвентарь игрока тоже попадает в этот список, но у его предметов
                // нет ни объёма приёма, ни заголовка множителя, так что фильтр по
                // подсказке отсекает их сам — отдельная проверка не нужна.
                parser.parseOffer(item.name(), item.id(), lore).ifPresent(offers::add);
                parser.parseMultiplier(item.name(), lore).ifPresent(multipliers::add);
                parser.parseStage(item.name(), lore).ifPresent(stages::add);
                parser.parseLockedSlot(lore).ifPresent(lockedSlots::add);

                // Справка окна множителей называется так же, как само окно,
                // и печатает надбавки уровней — это арифметика выгоды, а не текст.
                if (bonuses == null) {
                    bonuses = parser.parseBonuses(lore).orElse(null);
                }
            }
        } catch (RuntimeException e) {
            // Чтение окна — не критичная функция: пусть мод молчит, а не падает.
            LOG.warn("Не удалось прочитать окно «{}»: {}", title, e.toString());
            return Snapshot.EMPTY;
        }

        offers.sort(Comparator.comparingDouble(BuyerParser.Offer::unitPrice).reversed());
        stages.sort(Comparator.comparingInt(BuyerParser.Stage::number));
        return new Snapshot(kind, title,
                List.copyOf(offers), List.copyOf(multipliers),
                List.copyOf(stages), List.copyOf(lockedSlots), bonuses, Instant.now());
    }

    /** Периоды ротации из книги справки, если она лежит в открытом окне. */
    public Optional<java.time.Duration> rotationPeriod(boolean special) {
        try {
            for (ScreenReader.Item item : ScreenReader.items()) {
                if (patterns.match("info.title", item.name()).isPresent()) {
                    return parser.parseRotationPeriod(item.lore(), special);
                }
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


}
