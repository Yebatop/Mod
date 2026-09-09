package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private volatile BuyerParser.Bonuses bonuses;
    private volatile Map<String, BuyerParser.Offer> tradeOffers = Map.of();
    private volatile Instant tradeSeenAt = Instant.EPOCH;
    private volatile List<BuyerParser.Stage> stages = List.of();
    private volatile Instant stagesSeenAt = Instant.EPOCH;

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
     * Надбавки уровней из справки множителей.
     * <p>
     * Хранятся отдельно от снимка: справка лежит в окне множителей, а нужны они
     * в окне товаров, и снимок к тому моменту уже перезаписан. Числа при этом
     * не меняются, так что запомнить их один раз достаточно.
     */
    public BuyerParser.Bonuses bonuses() {
        return bonuses;
    }

    /**
     * Читает окно, если оно сейчас открыто. Зовётся из тика клиента.
     * <p>
     * Чужие экраны и закрытый инвентарь снимок не затирают: иначе он пропадал бы
     * ровно в тот момент, когда игрок закрывает окно, чтобы посмотреть результат.
     */
    public void tickScan() {
        Snapshot fresh = scan();
        if (fresh.kind() == Kind.NONE) {
            return;
        }
        last = fresh;
        if (fresh.bonuses() != null) {
            bonuses = fresh.bonuses();
        }

        // Этапы держим по той же причине, что и надбавки: показывать прогресс надо
        // в панели поверх игры, а лежат они в отдельном окне, которое к тому моменту
        // давно закрыто. Время снимка нужно, потому что прогресс, в отличие от
        // надбавок, меняется — и устаревшее число врало бы молча.
        if (!fresh.stages().isEmpty()) {
            stages = fresh.stages();
            stagesSeenAt = fresh.seenAt();
        }

        // Цены товаров нужны и вне окна Скупца — на подсказке предмета в инвентаре.
        // Снимок к тому моменту давно перезаписан другим окном, поэтому храним
        // их отдельно и вместе со временем: ассортимент меняется каждые несколько
        // часов, и старая цена без отметки давности врала бы молча.
        if (fresh.kind() == Kind.TRADE) {
            Map<String, BuyerParser.Offer> byItem = new HashMap<>();
            for (BuyerParser.Offer offer : fresh.offers()) {
                byItem.put(offer.itemId(), offer);
            }
            tradeOffers = Map.copyOf(byItem);
            tradeSeenAt = fresh.seenAt();
        }
    }

    /** Этапы из последнего просмотра окна «Этапы». */
    public List<BuyerParser.Stage> stages() {
        return stages;
    }

    /** Когда сняты этапы. Прогресс меняется, поэтому давность важна. */
    public Instant stagesSeenAt() {
        return stagesSeenAt;
    }

    /** Цена товара у Скупца, если он попадался в последнем просмотре «Торговли». */
    public Optional<BuyerParser.Offer> offerOf(String itemId) {
        return Optional.ofNullable(tradeOffers.get(itemId));
    }

    /** Когда снят последний список товаров. Нужно, чтобы честно показать давность. */
    public Instant tradeSeenAt() {
        return tradeSeenAt;
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
