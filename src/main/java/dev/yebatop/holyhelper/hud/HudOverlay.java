package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.analytics.RotationTimer;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Небольшая панель в углу экрана.
 * <p>
 * Она существует потому, что команда в чате бесполезна там, где нужна: с открытым
 * окном Скупца чат не открыть. Панель показывает то, что мод уже знает, и не требует
 * ничего набирать.
 * <p>
 * Рисуется только то, что действительно известно. Пустая строка «—» вместо таймера
 * хуже отсутствия строки: она выглядит как поломка, хотя окно просто ещё не открывали.
 */
public final class HudOverlay {

    /** Имя функции в реестре LiteAPI: попав в блок-лист, панель обязана исчезнуть целиком. */
    public static final String FEATURE = "hud-overlay";

    private static final int PADDING = 4;
    private static final int LINE = 10;

    private static final int COLOR_TITLE = 0xFFFFAA00;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_BACKDROP = 0x80000000;

    private HudOverlay() {
    }

    public static void register() {
        HudElementRegistry.addLast(
                Identifier.of(HolyHelperClient.MOD_ID, "overlay"),
                HudOverlay::render);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        HolyHelperClient mod = HolyHelperClient.instance();

        if (mod == null || client.player == null || client.options.hudHidden) {
            return;
        }
        if (!mod.config().hudEnabled || !ServerDetector.onHolyWorld()) {
            return;
        }
        // Блок-лист сервера сильнее настройки игрока: запрещённая функция исчезает,
        // а не прячется за галочкой.
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

        int x = PADDING;
        int y = PADDING;
        context.fill(x - 2, y - 2, x + width + 2, y + lines.size() * LINE, COLOR_BACKDROP);

        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? COLOR_TITLE : COLOR_TEXT;
            context.drawText(client.textRenderer, lines.get(i), x, y + i * LINE, color, true);
        }
    }

    private static List<Text> compose(HolyHelperClient mod) {
        List<Text> lines = new ArrayList<>();

        ScoreboardWatcher.Snapshot board = mod.board().snapshot();
        if (board.present() && board.coins() >= 0) {
            lines.add(Text.literal("Монеток " + board.coins()
                    + (board.tokens() > 0 ? "  Жетонов " + board.tokens() : "")));
        }

        RotationTimer timer = mod.rotation();
        timer.remaining(false).ifPresent(left ->
                lines.add(Text.literal("Обычные торги  " + human(left))));
        timer.remaining(true).ifPresent(left ->
                lines.add(Text.literal("Особые торги   " + human(left))));

        if (!lines.isEmpty()) {
            lines.add(0, Text.literal("HolyHelper"));
        } else if (!timer.known()) {
            // Совсем ничего не знаем — молчим. Панель без данных только мешает.
            return List.of();
        }
        return lines;
    }

    /** Часы показываем только когда они есть, секунды — только на последних минутах. */
    private static String human(Duration left) {
        long hours = left.toHours();
        long minutes = left.toMinutesPart();
        long seconds = left.toSecondsPart();

        if (hours > 0) {
            return hours + " ч " + minutes + " мин";
        }
        if (minutes > 0) {
            return minutes + " мин " + seconds + " с";
        }
        return seconds + " с";
    }
}
