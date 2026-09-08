package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Patterns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Читает витрину Маркета, открытую игроком.
 * <p>
 * У Маркета есть свойство, которого нет у Скупца: <b>согласованного среза рынка
 * не существует</b>. Страниц три десятка, их число зависит от того, сколько выставили
 * игроки, и пока листаешь — лоты раскупают и добавляют новые. Поэтому здесь нет
 * «снимка рынка»: есть наблюдения отдельных лотов, снятые в известный момент,
 * при известной сортировке и в известной категории.
 * <p>
 * Сортировка важна не меньше самих цен. Под «сначала дешёвые за ед.» первая страница
 * категории — это дно рынка, под «сначала дорогие» — потолок. Считать по такой выборке
 * медиану значит соврать, поэтому режим запоминается вместе с лотами.
 */
public final class MarketScanner {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/market");

    private final Patterns patterns;
    private final MarketParser parser;

    private volatile Snapshot last = Snapshot.EMPTY;

    public record Snapshot(
            boolean present,
            MarketParser.Page page,
            String category,
            String sort,
            List<MarketParser.Lot> lots,
            Instant seenAt) {

        public static final Snapshot EMPTY =
                new Snapshot(false, null, "", "", List.of(), Instant.EPOCH);
    }

    public MarketScanner(Patterns patterns) {
        this.patterns = patterns;
        this.parser = new MarketParser(patterns);
    }

    public Snapshot last() {
        return last;
    }

    /** Читает витрину, если она открыта. Зовётся из тика клиента. */
    public void tickScan() {
        Snapshot fresh = scan();
        if (fresh.present()) {
            last = fresh;
        }
    }

    public Snapshot scan() {
        String title = ScreenReader.title().orElse("");
        MarketParser.Page page = parser.parsePage(title).orElse(null);
        if (page == null) {
            return Snapshot.EMPTY;
        }

        List<MarketParser.Lot> lots = new ArrayList<>();
        String category = "";
        String sort = "";

        try {
            for (ScreenReader.Item item : ScreenReader.items()) {
                List<String> lore = item.lore();
                parser.parseLot(item.name(), item.id(), lore).ifPresent(lots::add);

                // Меню сортировки и категорий отличаются заголовком предмета,
                // а выбранный пункт в обоих помечен галочкой.
                if (patterns.match("market.sortTitle", item.name()).isPresent()) {
                    sort = parser.parseActiveChoice(lore).orElse("");
                } else if (patterns.match("market.categoriesTitle", item.name()).isPresent()) {
                    category = parser.parseActiveChoice(lore).orElse("");
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Не удалось прочитать витрину «{}»: {}", title, e.toString());
            return Snapshot.EMPTY;
        }

        // Дешёвые сверху: именно нижняя граница отвечает на вопрос «дороже ли Скупца».
        lots.sort(Comparator.comparingLong(MarketParser.Lot::unitPrice));
        return new Snapshot(true, page, category, sort, List.copyOf(lots), Instant.now());
    }
}
