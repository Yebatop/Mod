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
        if (board.present()) {
            line(source, "Баланс", board.coins() + " монеток · "
                    + board.gems() + " гемов · " + board.tokens() + " жетонов");
        } else {
            line(source, "Баланс", "сайдбар не прочитан");
        }

        line(source, "Паттерны", "версия " + mod.patterns().version());
        return 1;
    }

    private static int board(FabricClientCommandSource source) {
        List<String> lines = HolyHelperClient.instance().board().readSidebar();
        head(source, "Сайдбар как его видит мод");
        if (lines.isEmpty()) {
            source.sendFeedback(Text.literal("  пусто").formatted(Formatting.GRAY));
            return 1;
        }
        for (String line : lines) {
            source.sendFeedback(Text.literal("  " + line).formatted(Formatting.GRAY));
        }
        return 1;
    }

    private static void head(FabricClientCommandSource source, String title) {
        source.sendFeedback(Text.literal(title).formatted(Formatting.GOLD, Formatting.BOLD));
    }

    private static void line(FabricClientCommandSource source, String key, String value) {
        source.sendFeedback(Text.literal("  " + key + ": ").formatted(Formatting.GRAY)
                .append(Text.literal(value).formatted(Formatting.WHITE)));
    }

    private static String safe(String value) {
        return value == null ? "причина неизвестна" : value;
    }
}
