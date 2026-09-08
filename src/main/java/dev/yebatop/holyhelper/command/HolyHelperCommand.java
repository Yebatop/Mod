package dev.yebatop.holyhelper.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Клиентская команда {@code /holyhelper}. На сервер ничего не уходит. */
public final class HolyHelperCommand {

    private HolyHelperCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("holyhelper")
                .executes(context -> status(context.getSource()))
                .then(ClientCommandManager.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(ClientCommandManager.literal("board")
                        .executes(context -> board(context.getSource()))));
    }

    private static int status(FabricClientCommandSource source) {
        HolyHelperClient mod = HolyHelperClient.instance();

        head(source, "HolyHelper");
        line(source, "Сервер", ServerDetector.currentAddress().orElse("не подключены")
                + (ServerDetector.onHolyWorld() ? " — HolyWorld" : " — чужой, мод спит"));

        FeatureGate gate = mod.featureGate();
        line(source, "LiteAPI", switch (gate.status()) {
            case ANSWERED -> "отвечает";
            case NO_CHANNEL -> "канал не объявлен сервером";
            case NO_ANSWER -> "не ответил (" + safe(mod.channel().lastError()) + ")";
            case NOT_ASKED -> "ещё не спрашивали";
        });

        if (gate.status() == FeatureGate.Status.ANSWERED) {
            line(source, "Заблокировано", gate.blocked().isEmpty()
                    ? "ничего" : String.join(", ", gate.blocked()));
        }

        ScoreboardWatcher.Snapshot board = mod.board().snapshot();
        if (!board.present()) {
            line(source, "Сайдбар", "не прочитан (слот: " + mod.board().lastSlot() + ")");
        } else {
            line(source, "Баланс", money(board.coins()) + " монеток · "
                    + money(board.gems()) + " гемов · " + money(board.tokens()) + " жетонов");
            line(source, "Из панели", (board.nick().isEmpty() ? "ник не найден" : board.nick())
                    + " · " + (board.server().isEmpty() ? "режим не найден" : board.server()));
        }

        line(source, "Паттерны", "версия " + mod.patterns().version());
        return 1;
    }

    private static int board(FabricClientCommandSource source) {
        ScoreboardWatcher watcher = HolyHelperClient.instance().board();
        List<String> lines = watcher.readSidebar();

        head(source, "Сайдбар как его видит мод");
        line(source, "Слот", watcher.lastSlot());

        if (lines.isEmpty()) {
            source.sendFeedback(Text.literal("  пусто").formatted(Formatting.GRAY));
            return 1;
        }

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            source.sendFeedback(Text.literal("  " + i + ": ").formatted(Formatting.DARK_GRAY)
                    .append(Text.literal(raw).formatted(Formatting.GRAY)));
            String odd = unusual(raw);
            if (!odd.isEmpty()) {
                source.sendFeedback(Text.literal("     " + odd).formatted(Formatting.DARK_GRAY));
            }
        }
        return 1;
    }

    /**
     * Кодовые точки символов, которых не ждёшь в обычной строке.
     * <p>
     * Ровно на этом мод и споткнулся в первый раз: слева от метки сервер рисует
     * цветную полоску, регулярка с якорем {@code ^} её не переживала, а по чату было
     * не понять, что там за символ. Теперь видно сразу.
     */
    private static String unusual(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            boolean ordinary = cp == ' ' || Character.isLetterOrDigit(cp)
                    || (cp >= 0x21 && cp <= 0x7E);
            if (ordinary) {
                return;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format("U+%04X", cp));
        });
        return out.length() == 0 ? "" : "необычные символы: " + out;
    }

    private static void head(FabricClientCommandSource source, String title) {
        source.sendFeedback(Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD));
    }

    private static void line(FabricClientCommandSource source, String key, String value) {
        source.sendFeedback(Text.literal("  " + key + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(value).formatted(Formatting.WHITE)));
    }

    /** -1 — это «не нашли в панели», а не сумма. Печатать его как число нечестно. */
    private static String money(long value) {
        return value < 0 ? "?" : Long.toString(value);
    }

    private static String safe(String value) {
        return value == null ? "причина неизвестна" : value;
    }
}
