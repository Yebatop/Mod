package dev.yebatop.holyhelper.store;

import dev.yebatop.holyhelper.analytics.Liquidity;

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

    /**
     * Одно наблюдение: чей лот, по какой цене за штуку и когда его видели.
     * <p>
     * Продавец здесь не для красоты. Без него одно и то же объявление, попавшееся
     * при каждом перелистывании витрины, ложилось в базу заново, и счётчик
     * наблюдений считал просмотры вместо разных лотов. А от этого счётчика
     * зависит, показывает мод цену уверенно или с оговоркой.
     * <p>
     * Старые записи продавца не содержат: у них он пустой, и они по-прежнему
     * читаются — база копилась неделями, выбрасывать её из-за нового поля нельзя.
     */
    public record Observation(long unitPrice, long seenAtMillis, String seller, long remainingMillis) {

        /** Старые записи остатка не содержали — у них он нулевой. */
        public Observation(long unitPrice, long seenAtMillis, String seller) {
            this(unitPrice, seenAtMillis, seller, 0);
        }

        public Instant seenAt() {
            return Instant.ofEpochMilli(seenAtMillis);
        }

        /**
         * Сколько лоту оставалось висеть, когда его увидели.
         * <p>
         * По этому числу и виден возраст лота: сервер печатает остаток сам, а
         * значит доживающий лот отличим от свежего по одному снимку, без всякой
         * слежки за тем, купили его или нет.
         *
         * @return пусто, если запись сделана до того, как мод начал это запоминать
         */
        public Optional<Duration> remaining() {
            return remainingMillis > 0 ? Optional.of(Duration.ofMillis(remainingMillis)) : Optional.empty();
        }

        /** Тот же ли это лот: тот же продавец и та же цена за штуку. */
        boolean sameLot(long otherPrice, String otherSeller) {
            return unitPrice == otherPrice
                    && (seller == null ? "" : seller).equals(otherSeller == null ? "" : otherSeller);
        }
    }

    /**
     * Что мод знает про предмет.
     *
     * @param samples сколько разных лотов он видел, а не сколько раз смотрел
     */
    public record Known(String itemId, String name, long cheapestUnitPrice, Instant seenAt, int samples) {
    }

    private final Path file;
    private final Map<String, List<Observation>> byItem = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private transient boolean dirty;

    public PriceStore(Path file) {
        this.file = file;
    }

    /**
     * Запоминает лот.
     * <p>
     * Тот же лот, увиденный снова, не удваивает счётчик: у него обновляется отметка
     * времени, и всё. Иначе десять заходов на одну страницу превращали одно
     * объявление в десять «наблюдений», и мод показывал цену одного человека так,
     * будто её подтвердил рынок.
     */
    /**
     * Лот, у которого сервер не написал, сколько ему осталось висеть.
     * <p>
     * Такое бывает: строка времени есть не у каждого объявления. Цена от этого
     * не становится хуже, а вот в разбор ликвидности такая запись не пойдёт —
     * возраст по ней неизвестен.
     */
    public void record(String itemId, String name, long unitPrice, String seller, Instant seenAt) {
        record(itemId, name, unitPrice, seller, seenAt, null);
    }

    public void record(String itemId, String name, long unitPrice, String seller, Instant seenAt,
                       Duration remaining) {
        if (itemId == null || itemId.isBlank() || unitPrice <= 0) {
            return;
        }
        long remainingMillis = remaining == null || remaining.isNegative() ? 0 : remaining.toMillis();
        List<Observation> observations = byItem.computeIfAbsent(itemId, key -> new ArrayList<>());
        long millis = seenAt.toEpochMilli();

        for (int i = 0; i < observations.size(); i++) {
            Observation existing = observations.get(i);
            if (existing.sameLot(unitPrice, seller)) {
                // Лот ещё висит — освежаем срок, чтобы он не выпал из окна памяти
                // раньше времени, но новым наблюдением не считаем.
                if (millis > existing.seenAtMillis()) {
                    // Остаток берём свежий: он у того же лота с каждым разом
                    // меньше, и именно этим показывает, что лот не купили.
                    observations.set(i, new Observation(unitPrice, millis, existing.seller(),
                            remainingMillis > 0 ? remainingMillis : existing.remainingMillis()));
                    dirty = true;
                }
                return;
            }
        }

        observations.add(new Observation(unitPrice, millis, seller == null ? "" : seller,
                remainingMillis));
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

    /**
     * Наблюдения по предмету в виде, годном для разбора ликвидности.
     * <p>
     * Записи без остатка отбрасываются: они сделаны до того, как мод начал его
     * запоминать, и возраст лота по ним неизвестен. Считать их свежими или
     * старыми одинаково неверно, а домысливать тут нечего.
     */
    public List<Liquidity.Sample> samples(String itemId, Duration maxAge) {
        List<Observation> observations = byItem.get(itemId);
        if (observations == null) {
            return List.of();
        }
        Instant since = Instant.now().minus(maxAge);
        List<Liquidity.Sample> samples = new ArrayList<>();
        for (Observation observation : observations) {
            if (observation.seenAt().isBefore(since)) {
                continue;
            }
            observation.remaining().ifPresent(remaining ->
                    samples.add(new Liquidity.Sample(observation.unitPrice(), remaining)));
        }
        return samples;
    }

    public int itemCount() {
        return byItem.size();
    }

    public int observationCount() {
        return byItem.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Схлопывает записи без продавца, совпадающие по цене.
     * <p>
     * У них нет ничего, чем один лот отличается от другого, а появились они
     * из-за того, что мод записывал каждую встречу с витриной заново. Оставляем
     * самую свежую из каждой цены: это ровно то, что мод про них знает.
     */
    private static void collapseLegacy(List<Observation> observations) {
        Map<Long, Observation> newest = new HashMap<>();
        List<Observation> withSeller = new ArrayList<>();

        for (Observation observation : observations) {
            String seller = observation.seller();
            if (seller != null && !seller.isEmpty()) {
                withSeller.add(observation);
                continue;
            }
            newest.merge(observation.unitPrice(), observation,
                    (a, b) -> a.seenAtMillis() >= b.seenAtMillis() ? a : b);
            // Остаток при схлопывании не теряется: берётся запись целиком, а не
            // только её цена.
        }
        if (newest.size() + withSeller.size() == observations.size()) {
            return;
        }
        observations.clear();
        observations.addAll(withSeller);
        observations.addAll(newest.values());
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

            // Записи, накопленные до того, как наблюдение стало помнить продавца,
            // считали просмотры вместо лотов: один и тот же лот при каждом
            // перелистывании ложился заново. Отличить их друг от друга уже нельзя,
            // поэтому одинаковые по цене схлопываем в одну — это честный минимум,
            // а не ждать неделю, пока они истекут сами.
            int before = observationCount();
            byItem.values().forEach(PriceStore::collapseLegacy);
            int collapsed = before - observationCount();

            LOG.info("Загружено наблюдений: {} по {} предметам", observationCount(), itemCount());
            if (collapsed > 0) {
                LOG.info("Схлопнуто старых записей без продавца: {} — они считали просмотры,"
                        + " а не разные лоты", collapsed);
                dirty = true;
            }
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
