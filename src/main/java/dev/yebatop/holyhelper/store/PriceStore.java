package dev.yebatop.holyhelper.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Наблюдения цен Маркета, переживающие перезаход в игру.
 * <p>
 * Здесь именно наблюдения, а не «цены рынка». Согласованного среза витрины не бывает:
 * страниц три десятка, их число плавает, и пока листаешь — лоты раскупают. Поэтому
 * каждая запись честно значит «такой лот висел в такой момент», а не «столько это стоит».
 * <p>
 * Отсюда и главная оговорка: самое дешёвое из увиденного — это <b>оценка сверху</b>
 * для настоящего дна рынка. Игрок мог не дойти до страницы, где лежит дешевле, или
 * смотреть под сортировкой «сначала дорогие». Занизить эта оценка не может, завысить —
 * запросто, и подписывать её надо именно так.
 * <p>
 * Классов Minecraft здесь нет: на вход путь к файлу, поэтому хранилище проверяется тестами.
 */
public final class PriceStore {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/store");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Сколько наблюдений держим на предмет. Больше незачем: нужен минимум, а не история. */
    private static final int PER_ITEM_LIMIT = 30;

    /** Старше этого срока наблюдение — уже не про сегодняшний рынок. */
    private static final Duration KEEP = Duration.ofDays(7);

    /** Одно наблюдение: цена за штуку и когда её видели. */
    public record Observation(long unitPrice, long seenAtMillis) {

        public Instant seenAt() {
            return Instant.ofEpochMilli(seenAtMillis);
        }
    }

    /** Что мод знает про предмет. */
    public record Known(String itemId, String name, long cheapestUnitPrice, Instant seenAt, int samples) {
    }

    private final Path file;
    private final Map<String, List<Observation>> byItem = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private transient boolean dirty;

    public PriceStore(Path file) {
        this.file = file;
    }

    /** Запоминает лот. Одинаковые наблюдения в одну секунду не плодятся. */
    public void record(String itemId, String name, long unitPrice, Instant seenAt) {
        if (itemId == null || itemId.isBlank() || unitPrice <= 0) {
            return;
        }
        List<Observation> observations = byItem.computeIfAbsent(itemId, key -> new ArrayList<>());

        long millis = seenAt.toEpochMilli();
        for (Observation existing : observations) {
            if (existing.unitPrice() == unitPrice
                    && Math.abs(existing.seenAtMillis() - millis) < 1000) {
                return;
            }
        }

        observations.add(new Observation(unitPrice, millis));
        names.put(itemId, name);
        prune(observations);
        dirty = true;
    }

    /**
     * Самая низкая цена за штуку из виденного за последнее время.
     * <p>
     * Это оценка дна рынка сверху, а не само дно: дешевле могло лежать там, куда
     * игрок не дошёл.
     */
    public OptionalLong cheapest(String itemId, Duration maxAge) {
        List<Observation> observations = byItem.get(itemId);
        if (observations == null) {
            return OptionalLong.empty();
        }
        Instant since = Instant.now().minus(maxAge);
        return observations.stream()
                .filter(observation -> observation.seenAt().isAfter(since))
                .mapToLong(Observation::unitPrice)
                .min();
    }

    /** Всё, что известно про предмет, вместе с давностью и числом наблюдений. */
    public Optional<Known> known(String itemId, Duration maxAge) {
        List<Observation> observations = byItem.get(itemId);
        if (observations == null || observations.isEmpty()) {
            return Optional.empty();
        }
        Instant since = Instant.now().minus(maxAge);
        List<Observation> fresh = observations.stream()
                .filter(observation -> observation.seenAt().isAfter(since))
                .toList();
        if (fresh.isEmpty()) {
            return Optional.empty();
        }

        Observation cheapest = fresh.stream()
                .min(Comparator.comparingLong(Observation::unitPrice))
                .orElseThrow();
        Instant latest = fresh.stream()
                .map(Observation::seenAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        return Optional.of(new Known(
                itemId, names.getOrDefault(itemId, itemId),
                cheapest.unitPrice(), latest, fresh.size()));
    }

    public int itemCount() {
        return byItem.size();
    }

    public int observationCount() {
        return byItem.values().stream().mapToInt(List::size).sum();
    }

    /** Выбрасывает лишнее: слишком старое и слишком многочисленное. */
    private static void prune(List<Observation> observations) {
        Instant since = Instant.now().minus(KEEP);
        observations.removeIf(observation -> observation.seenAt().isBefore(since));

        if (observations.size() > PER_ITEM_LIMIT) {
            observations.sort(Comparator.comparingLong(Observation::seenAtMillis).reversed());
            observations.subList(PER_ITEM_LIMIT, observations.size()).clear();
        }
    }

    private record Snapshot(Map<String, List<Observation>> items, Map<String, String> names) {
    }

    private static final Type SNAPSHOT_TYPE = new TypeToken<Snapshot>() {
    }.getType();

    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Snapshot snapshot = GSON.fromJson(reader, SNAPSHOT_TYPE);
            if (snapshot == null || snapshot.items() == null) {
                return;
            }
            byItem.clear();
            names.clear();
            snapshot.items().forEach((itemId, observations) ->
                    byItem.put(itemId, new ArrayList<>(observations)));
            if (snapshot.names() != null) {
                names.putAll(snapshot.names());
            }
            byItem.values().forEach(PriceStore::prune);
            LOG.info("Загружено наблюдений: {} по {} предметам", observationCount(), itemCount());
        } catch (IOException | RuntimeException e) {
            // Битая база хуже пустой только если из-за неё падает мод. Не падаем.
            LOG.warn("База цен не прочиталась ({}), начинаю с пустой", e.getMessage());
            byItem.clear();
            names.clear();
        }
    }

    /** Сохраняет, только если было что менять. */
    public void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(new Snapshot(byItem, names), SNAPSHOT_TYPE, writer);
            }
            dirty = false;
        } catch (IOException e) {
            LOG.warn("База цен не сохранилась: {}", e.getMessage());
        }
    }
}
