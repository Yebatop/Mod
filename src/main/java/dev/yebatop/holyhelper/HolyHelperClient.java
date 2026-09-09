package dev.yebatop.holyhelper;

import dev.yebatop.holyhelper.analytics.RotationTimer;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.command.HolyHelperCommand;
import dev.yebatop.holyhelper.core.HolyHelperConfig;
import dev.yebatop.holyhelper.core.Patterns;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.hud.ExchangeHint;
import dev.yebatop.holyhelper.hud.HudOverlay;
import dev.yebatop.holyhelper.hud.ItemPriceTooltip;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.liteapi.LiteApiChannel;
import dev.yebatop.holyhelper.liteapi.LiteApiPayload;
import dev.yebatop.holyhelper.rest.CoinRateTracker;
import dev.yebatop.holyhelper.rest.HolyApiClient;
import dev.yebatop.holyhelper.scan.BuyerScanner;
import dev.yebatop.holyhelper.scan.ExchangeParser;
import dev.yebatop.holyhelper.scan.MarketScanner;
import dev.yebatop.holyhelper.store.PriceStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа. Мод целиком клиентский и работает только на серверах HolyWorld.
 * <p>
 * Что он умеет по правилам проекта: читать окна, которые игрок открыл сам, читать
 * сайдбар, считать арифметику и напоминать. Чего не делает никогда: не кликает по
 * слотам, не автоматизирует инвентарь, не показывает ничего про невидимость и не шлёт
 * серверу ни одного пакета, кроме запроса к LiteAPI.
 */
@Environment(EnvType.CLIENT)
public final class HolyHelperClient implements ClientModInitializer {

    public static final String MOD_ID = "holyhelper";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static HolyHelperClient instance;

    private HolyHelperConfig config;
    private Patterns patterns;
    private LiteApiChannel channel;
    private FeatureGate featureGate;
    private ScoreboardWatcher board;
    private BuyerScanner buyer;
    private MarketScanner market;
    private ExchangeParser exchange;
    private RotationTimer rotation;
    private PriceStore prices;
    private HolyApiClient api;
    private CoinRateTracker rates;

    private int tickCounter;
    private int announceAtTick;
    private long nextRatePollAt;

    public static HolyHelperClient instance() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        config = HolyHelperConfig.load();
        patterns = Patterns.load(HolyHelperConfig.directory());
        channel = new LiteApiChannel();
        featureGate = new FeatureGate(channel);
        board = new ScoreboardWatcher(patterns);
        buyer = new BuyerScanner(patterns);
        prices = new PriceStore(HolyHelperConfig.directory().resolve("prices.json"));
        prices.load();
        market = new MarketScanner(patterns, prices);
        exchange = new ExchangeParser(patterns);
        rotation = new RotationTimer();
        api = new HolyApiClient();
        rates = new CoinRateTracker();

        // Канал LiteAPI объявляется в обе стороны: без C2S нечем отправить,
        // без S2C Fabric не отдаст нам входящий пакет.
        PayloadTypeRegistry.playC2S().register(LiteApiPayload.FEATURE_CONTROL, LiteApiPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LiteApiPayload.FEATURE_CONTROL, LiteApiPayload.CODEC);
        channel.registerReceiver();
        HudOverlay.register();
        ExchangeHint.register();
        ItemPriceTooltip.register();

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, access) -> HolyHelperCommand.register(dispatcher));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        LOG.info("HolyHelper загружен, паттерны версии {}", patterns.version());
    }

    private void onJoin() {
        if (!ServerDetector.onHolyWorld()) {
            LOG.info("Сервер {} не относится к HolyWorld — мод не активен",
                    ServerDetector.currentAddress().orElse("?"));
            return;
        }

        // reset() здесь не зовём: вход срабатывает дважды (лобби, затем Прайм),
        // и сбрасывать уже полученный ответ на второй половине перехода незачем.
        // Полная очистка — при отключении.
        featureGate.armOnJoin();

        if (config.announceOnJoin) {
            // Исход выясняется асинхронно, поэтому отчёт откладываем.
            scheduleAnnounce();
        }
    }

    private void onDisconnect() {
        prices.save();
        HudOverlay.resetAnimation();
        nextRatePollAt = 0;
        channel.reset();
        featureGate.reset();
        rotation.reset();
        tickCounter = 0;
    }

    private void onTick(MinecraftClient client) {
        if (client.world == null || !ServerDetector.onHolyWorld()) {
            return;
        }
        tickCounter++;

        // Ждём, пока сервер объявит канал LiteAPI. Пока не объявил — не шлём ничего.
        featureGate.tick();

        // Сайдбар перечитываем раз в секунду: чаще незачем, сервер обновляет его редко.
        if (tickCounter % 20 == 0) {
            board.refresh();
        }

        // Наблюдения сбрасываем на диск раз в минуту, а не при каждой записи:
        // вылет клиента не должен стоить всего, что мод успел увидеть.
        if (tickCounter % 1200 == 0) {
            prices.save();
        }

        pollRate();

        // Окно Скупца читаем дважды в секунду, пока оно открыто. Ждать команды нельзя:
        // с открытым окном чат не открыть, и набрать её игроку негде.
        if (tickCounter % 10 == 0) {
            buyer.tickScan();
            market.tickScan();
            // Из остатка в подсказке считаем момент обновления: дальше часы идут сами,
            // и переоткрывать окно ради таймера не нужно.
            if (buyer.last().kind() == BuyerScanner.Kind.TRADE) {
                rotation.update(buyer.last().offers(), buyer.last().seenAt());
            }
            // Справка Скупца называет длину цикла прямым текстом. Пока игрок в неё
            // не заглянул, длина берётся как наибольший увиденный остаток — этого
            // хватает полосе, но точное значение всё равно лучше, поэтому спрашиваем
            // при каждом заходе в окно. Без открытого окна вызов ничего не стоит.
            buyer.rotationPeriod(false).ifPresent(period -> rotation.learnPeriod(false, period));
            buyer.rotationPeriod(true).ifPresent(period -> rotation.learnPeriod(true, period));
        }

        // Отчитываемся, как только исход ясен, но не позже жёсткого срока: иначе на
        // сервере без LiteAPI сообщение висело бы в ожидании неизвестно сколько.
        if (announceAtTick > 0
                && (featureGate.status() != FeatureGate.Status.NOT_ASKED || tickCounter >= announceAtTick)) {
            announceAtTick = 0;
            announce(client);
        }
    }

    /**
     * Опрашивает историю курса. Пауза берётся у клиента: при неудачах он её растит,
     * и мод не долбится в упавший сервис.
     * <p>
     * Ответ приходит в фоновом потоке, поэтому здесь только запуск — трекер
     * потокобезопасен, а игровой поток ничего не ждёт.
     */
    private void pollRate() {
        if (!featureGate.isAllowed("exchange-rate")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRatePollAt) {
            return;
        }
        nextRatePollAt = now + api.interval().toMillis();

        api.get("/v2/prime/coins/trades?limit=100").thenAccept(body -> {
            int added = rates.merge(body);
            if (added > 0) {
                LOG.info("Курс: {} новых сделок, всего {}", added, rates.size());
            }
        });
    }

    private void scheduleAnnounce() {
        // Жёсткий потолок: десять секунд ожидания канала плюс пять на таймаут запроса.
        // Обычно отчёт уходит раньше — как только состояние перестало быть «не спрашивали».
        announceAtTick = tickCounter + 20 * 16;
    }

    private void announce(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        String liteapi = switch (featureGate.status()) {
            case ANSWERED -> featureGate.blocked().isEmpty()
                    ? "LiteAPI отвечает, ограничений нет"
                    : "LiteAPI отвечает, заблокировано: " + String.join(", ", featureGate.blocked());
            case NO_CHANNEL -> "LiteAPI на этом режиме не отвечает — работаю без него";
            case NO_ANSWER -> "LiteAPI молчит — работаю без него";
            case NOT_ASKED -> "LiteAPI ещё не проверен";
        };

        client.player.sendMessage(
                Text.literal("[HolyHelper] ").formatted(Formatting.GOLD)
                        .append(Text.literal(liteapi).formatted(Formatting.GRAY)),
                false);
    }

    public HolyHelperConfig config() {
        return config;
    }

    public Patterns patterns() {
        return patterns;
    }

    public LiteApiChannel channel() {
        return channel;
    }

    public FeatureGate featureGate() {
        return featureGate;
    }

    public ScoreboardWatcher board() {
        return board;
    }

    public BuyerScanner buyer() {
        return buyer;
    }

    public MarketScanner market() {
        return market;
    }

    public ExchangeParser exchange() {
        return exchange;
    }

    public RotationTimer rotation() {
        return rotation;
    }

    public PriceStore prices() {
        return prices;
    }

    public HolyApiClient api() {
        return api;
    }

    public CoinRateTracker rates() {
        return rates;
    }
}
