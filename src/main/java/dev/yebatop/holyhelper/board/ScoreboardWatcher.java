package dev.yebatop.holyhelper.board;

import dev.yebatop.holyhelper.core.Patterns;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Читает боковую панель сервера.
 * <p>
 * Сайдбар — самый дешёвый и самый честный источник баланса: сервер сам держит там
 * монетки, гемы и жетоны и сам их обновляет. Клиент только смотрит на то, что ему уже
 * прислали, — ни одного лишнего пакета, никакого сканирования окон.
 * <p>
 * Строки собираются так же, как их рисует ванильный HUD: имя записи, украшенное
 * префиксом и суффиксом команды. Иначе половина текста потеряется — сервер кладёт
 * значения именно в суффиксы.
 */
public final class ScoreboardWatcher {

    private static final Logger LOG = LoggerFactory.getLogger("holyhelper/board");

    private final Patterns patterns;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public record Snapshot(String nick, long coins, long gems, long tokens, String server, boolean present) {
        public static final Snapshot EMPTY = new Snapshot("", -1, -1, -1, "", false);
    }

    public ScoreboardWatcher(Patterns patterns) {
        this.patterns = patterns;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Перечитывает сайдбар. Дёшево, но звать каждый кадр незачем — хватает раза в секунду. */
    public void refresh() {
        List<String> lines = readSidebar();
        if (lines.isEmpty()) {
            snapshot = Snapshot.EMPTY;
            return;
        }

        String nick = "";
        String server = "";
        long coins = -1;
        long gems = -1;
        long tokens = -1;

        for (String line : lines) {
            Optional<java.util.regex.Matcher> nickMatch = patterns.match("board.nick", line);
            if (nickMatch.isPresent()) {
                nick = nickMatch.get().group(1);
                continue;
            }
            Optional<java.util.regex.Matcher> serverMatch = patterns.match("board.server", line);
            if (serverMatch.isPresent()) {
                server = serverMatch.get().group(1) + " #" + serverMatch.get().group(2);
                continue;
            }
            OptionalLong value = patterns.number("board.coins", line);
            if (value.isPresent()) {
                coins = value.getAsLong();
                continue;
            }
            value = patterns.number("board.gems", line);
            if (value.isPresent()) {
                gems = value.getAsLong();
                continue;
            }
            value = patterns.number("board.tokens", line);
            if (value.isPresent()) {
                tokens = value.getAsLong();
            }
        }

        snapshot = new Snapshot(nick, coins, gems, tokens, server, true);
    }

    /** Строки боковой панели сверху вниз, уже с префиксами и суффиксами команд. */
    public List<String> readSidebar() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return List.of();
        }

        try {
            Scoreboard scoreboard = client.world.getScoreboard();
            ScoreboardObjective objective =
                    scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (objective == null) {
                return List.of();
            }

            List<String> lines = new ArrayList<>();
            for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
                if (entry.hidden()) {
                    continue;
                }
                Team team = scoreboard.getScoreHolderTeam(entry.owner());
                Text decorated = Team.decorateName(team, entry.name());
                lines.add(decorated.getString());
            }
            return lines;
        } catch (RuntimeException e) {
            // Сайдбар — не критичная функция: если версия игры поменяла API, мод должен жить дальше.
            LOG.warn("Не удалось прочитать сайдбар: {}", e.toString());
            return List.of();
        }
    }
}
