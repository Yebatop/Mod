package dev.yebatop.holyhelper;

import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.command.HolyHelperCommand;
import dev.yebatop.holyhelper.core.HolyHelperConfig;
import dev.yebatop.holyhelper.core.Patterns;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.liteapi.LiteApiChannel;
import dev.yebatop.holyhelper.liteapi.LiteApiPayload;
import dev.yebatop.holyhelper.scan.BuyerScanner;
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

    private int tickCounter;
    private int announceAtTick;

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

        // Канал LiteAPI объявляется в обе стороны: без C2S нечем отправить,
        // без S2C Fabric не отдаст нам входящий пакет.
        PayloadTypeRegistry.playC2S().register(LiteApiPayload.FEATURE_CONTROL, LiteApiPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LiteApiPayload.FEATURE_CONTROL, LiteApiPayload.CODEC);
        channel.registerReceiver();

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
        channel.reset();
        featureGate.reset();
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

        // Отчитываемся, как только исход ясен, но не позже жёсткого срока: иначе на
        // сервере без LiteAPI сообщение висело бы в ожидании неизвестно сколько.
        if (announceAtTick > 0
                && (featureGate.status() != FeatureGate.Status.NOT_ASKED || tickCounter >= announceAtTick)) {
            announceAtTick = 0;
            announce(client);
        }
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
}
