package dev.yebatop.holyhelper.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.board.ScoreboardWatcher;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.BuyerParser;
import dev.yebatop.holyhelper.scan.BuyerScanner;
import dev.yebatop.holyhelper.scan.MarketParser;
import dev.yebatop.holyhelper.scan.MarketScanner;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.Duration;
import java.time.Instant;
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
                        .executes(context -> board(context.getSource())))
                .then(ClientCommandManager.literal("buy")
                        .executes(context -> buy(context.getSource())))
                .then(ClientCommandManager.literal("ah")
                        .executes(context -> market(context.getSource()))));
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
            case NOT_ASKED -> gate.waiting() ? "ждём, объявит ли сервер канал" : "ещё не спрашивали";
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

    /**
     * Что мод прочитал в окне Скупца.
     * <p>
     * Показывает последний снимок, а не текущий экран: с открытым окном чат не открыть,
     * поэтому мод читает его сам, пока оно на виду, а команда набирается уже после.
     * Возраст снимка печатается всегда — иначе он однажды соврёт молча.
     */
    private static int buy(FabricClientCommandSource source) {
        BuyerScanner.Snapshot snapshot = HolyHelperClient.instance().buyer().last();

        if (snapshot.kind() == BuyerScanner.Kind.NONE) {
            head(source, "Скупец");
            source.sendFeedback(Text.literal("  окно ещё не открывали — зайдите к Скупцу через /b, "
                    + "посмотрите на товары и закройте окно").formatted(Formatting.GRAY));
            return 1;
        }

        head(source, snapshot.title());
        line(source, "Снято", humanAge(Duration.between(snapshot.seenAt(), Instant.now())));

        BuyerParser.Bonuses bonuses = snapshot.bonuses();
        if (bonuses != null) {
            line(source, "Надбавки", "I +" + bonuses.levelOne() + "% · II +"
                    + bonuses.levelTwo() + "% · III +" + bonuses.levelThree()
                    + "%, разные категории перемножаются");
        }

        for (BuyerParser.Multiplier multiplier : snapshot.multipliers()) {
            StringBuilder value = new StringBuilder(multiplier.stacks() + " стаков");
            if (multiplier.level() > 0) {
                value.append(" · ").append(multiplier.level()).append(" ур.");
                if (bonuses != null) {
                    value.append(String.format(" (+%.0f%%)", bonuses.share(multiplier.level()) * 100));
                }
            }
            line(source, multiplier.category(), value.toString());
        }

        for (BuyerParser.Stage stage : snapshot.stages()) {
            MutableText row = Text.literal("  ")
                    .append(Text.literal("Этап #" + stage.number()).formatted(Formatting.GOLD))
                    .append(Text.literal("  " + stage.goal() + " монеток").formatted(Formatting.WHITE));

            if (stage.completed()) {
                row.append(Text.literal("  выполнен").formatted(Formatting.GREEN));
            } else if (stage.locked()) {
                row.append(Text.literal("  закрыт").formatted(Formatting.DARK_GRAY));
            } else if (stage.hasProgress()) {
                row.append(Text.literal(String.format("  %d (%.0f%%)",
                        stage.progress(), stage.completion() * 100)).formatted(Formatting.GREEN));
            }
            source.sendFeedback(row);
        }

        if (!snapshot.lockedSlots().isEmpty()) {
            int stagesNeeded = snapshot.lockedSlots().stream()
                    .mapToInt(BuyerParser.LockedSlot::stagesRequired)
                    .filter(value -> value > 0)
                    .min()
                    .orElse(0);
            line(source, "Закрыто ячеек", snapshot.lockedSlots().size()
                    + (stagesNeeded > 0 ? " · ближайшая с " + stagesNeeded + " этапов" : ""));
        }

        if (snapshot.offers().isEmpty()) {
            if (snapshot.stages().isEmpty() && snapshot.multipliers().isEmpty()) {
                source.sendFeedback(Text.literal("  разбирать в этом окне нечего")
                        .formatted(Formatting.GRAY));
            }
            return 1;
        }

        source.sendFeedback(Text.literal("  " + snapshot.offers().size()
                + " товаров, дороже сверху — цена за штуку").formatted(Formatting.GRAY));

        for (BuyerParser.Offer offer : snapshot.offers()) {
            MutableText row = Text.literal("  ")
                    .append(Text.literal(String.format("%8.2f", offer.unitPrice()))
                            .formatted(Formatting.GOLD))
                    .append(Text.literal("  " + offer.name()).formatted(Formatting.WHITE));

            if (offer.special()) {
                row.append(Text.literal(" ✦").formatted(Formatting.GREEN));
            }
            if (offer.multiplierFactor() > 1.001) {
                row.append(Text.literal(String.format(" ×%.2f", offer.multiplierFactor()))
                        .formatted(Formatting.LIGHT_PURPLE));
            }

            row.append(Text.literal("  осталось " + offer.available()).formatted(Formatting.DARK_GRAY));
            if (offer.rotation() != null) {
                row.append(Text.literal("  " + humanTime(offer.rotation())).formatted(Formatting.DARK_GRAY));
            }
            source.sendFeedback(row);
        }
        return 1;
    }

    /**
     * Что мод прочитал на витрине Маркета.
     * <p>
     * Вместе с лотами печатаются страница, категория и сортировка. Без них список
     * вводит в заблуждение: под «сначала дешёвые» первая страница — это дно рынка,
     * под «сначала дорогие» — потолок, и одни и те же числа значат разное.
     */
    private static int market(FabricClientCommandSource source) {
        MarketScanner.Snapshot snapshot = HolyHelperClient.instance().market().last();

        if (!snapshot.present()) {
            head(source, "Маркет");
            source.sendFeedback(Text.literal("  витрину ещё не открывали — зайдите через /ah "
                    + "и закройте окно").formatted(Formatting.GRAY));
            return 1;
        }

        head(source, "Маркет " + snapshot.page().current() + "/" + snapshot.page().total());
        line(source, "Снято", humanAge(Duration.between(snapshot.seenAt(), Instant.now())));
        line(source, "Срез", (snapshot.category().isEmpty() ? "категория неизвестна" : snapshot.category())
                + " · " + (snapshot.sort().isEmpty() ? "сортировка неизвестна" : snapshot.sort()));

        if (snapshot.lots().isEmpty()) {
            source.sendFeedback(Text.literal("  лотов на этой странице нет").formatted(Formatting.GRAY));
            return 1;
        }

        source.sendFeedback(Text.literal("  " + snapshot.lots().size()
                + " лотов, дешевле сверху — цена за штуку").formatted(Formatting.GRAY));

        for (MarketParser.Lot lot : snapshot.lots()) {
            MutableText row = Text.literal("  ")
                    .append(Text.literal(String.format("%8d", lot.unitPrice())).formatted(Formatting.GOLD))
                    .append(Text.literal("  " + lot.name()).formatted(Formatting.WHITE));

            if (lot.quantity() > 1) {
                row.append(Text.literal(" ×" + lot.quantity()).formatted(Formatting.GRAY));
            }
            row.append(Text.literal("  " + lot.seller()).formatted(Formatting.DARK_GRAY));
            if (lot.expiresIn() != null) {
                row.append(Text.literal("  " + humanTime(lot.expiresIn())).formatted(Formatting.DARK_GRAY));
            }
            source.sendFeedback(row);
        }
        return 1;
    }

    /** Давность снимка словами. Секунды важнее всего: остатки меняются быстро. */
    private static String humanAge(Duration age) {
        long seconds = Math.max(0, age.toSeconds());
        if (seconds < 60) {
            return seconds + " с назад";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " мин назад";
        }
        return age.toHours() + " ч назад — откройте окно заново, числа наверняка устарели";
    }

    /** Остаток времени коротко: часы показываем только когда они есть. */
    private static String humanTime(Duration left) {
        long hours = left.toHours();
        long minutes = left.toMinutesPart();
        return hours > 0 ? hours + " ч " + minutes + " мин" : minutes + " мин";
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
