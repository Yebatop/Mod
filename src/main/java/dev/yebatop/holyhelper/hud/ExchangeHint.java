package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.ExchangeParser;
import dev.yebatop.holyhelper.scan.ScreenReader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Подсказка поверх окна Биржи: как курс создаваемой заявки соотносится с рынком.
 * <p>
 * Рисуется прямо на экране, а не пишется в чат, по простой причине: с открытым окном
 * чат не открыть, а решение о курсе принимается именно здесь. Сообщение, которое
 * игрок прочитает после закрытия окна, приходит слишком поздно.
 * <p>
 * Сравнение идёт с медианой <b>совершённых</b> сделок, а не с заявками в окне: заявка
 * может висеть с любым курсом и ничего не значить, пока её никто не принял.
 */
public final class ExchangeHint {

    /** Имя функции в реестре LiteAPI. */
    public static final String FEATURE = "exchange-rate";

    /** Отклонение меньше этого — обычный разброс, а не повод что-то менять. */
    private static final double NOTABLE_PERCENT = 5;

    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_BACKDROP = 0xC0000000;

    private ExchangeHint() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register(
                        (rendered, context, mouseX, mouseY, delta) -> render(context)));
    }

    private static void render(DrawContext context) {
        HolyHelperClient mod = HolyHelperClient.instance();
        MinecraftClient client = MinecraftClient.getInstance();

        if (mod == null || client.player == null || !ServerDetector.onHolyWorld()) {
            return;
        }
        if (mod.featureGate().status() == FeatureGate.Status.ANSWERED
                && !mod.featureGate().isAllowed(FEATURE)) {
            return;
        }

        List<Text> lines = compose(mod);
        if (lines.isEmpty()) {
            return;
        }

        int width = 0;
        for (Text line : lines) {
            width = Math.max(width, client.textRenderer.getWidth(line));
        }

        int x = 4;
        int y = 4;
        context.fill(x - 2, y - 2, x + width + 2, y + lines.size() * 10, COLOR_BACKDROP);
        for (int i = 0; i < lines.size(); i++) {
            context.drawText(client.textRenderer, lines.get(i), x, y + i * 10, COLOR_TEXT, true);
        }
    }

    private static List<Text> compose(HolyHelperClient mod) {
        ExchangeParser parser = mod.exchange();
        Optional<Double> median = mod.rates().median(java.time.Duration.ofHours(24));
        if (median.isEmpty()) {
            return List.of();
        }

        // Заявка лежит подсказкой на предмете в открытом окне; ищем её там же,
        // где и всё остальное, — без кликов и без запросов к серверу.
        for (ScreenReader.Item item : ScreenReader.items()) {
            if (!parser.isOwnOffer(item.name())) {
                continue;
            }
            ExchangeParser.Offer offer = parser.parseOwnOffer(item.lore()).orElse(null);
            if (offer == null) {
                continue;
            }
            return describe(offer, median.get());
        }
        return List.of();
    }

    private static List<Text> describe(ExchangeParser.Offer offer, double median) {
        double deviation = (offer.rate() - median) / median * 100;

        String verdict;
        if (deviation > NOTABLE_PERCENT) {
            // Игрок отдаёт монетки и хочет жетоны: курс выше рынка значит, что он
            // платит дороже обычного, и заявку могут разобрать быстро.
            verdict = "выше рынка — заберут быстрее, но переплата";
        } else if (deviation < -NOTABLE_PERCENT) {
            verdict = "ниже рынка — дешевле, но заявка может висеть долго";
        } else {
            verdict = "по рынку";
        }

        return List.of(
                Text.literal("HolyHelper"),
                Text.literal(String.format(Locale.ROOT,
                        "Ваш курс %d · медиана суток %.0f", offer.rate(), median)),
                Text.literal(String.format(Locale.ROOT, "%+.1f%% — %s", deviation, verdict)));
    }
}
