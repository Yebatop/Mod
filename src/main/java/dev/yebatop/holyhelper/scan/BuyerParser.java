package dev.yebatop.holyhelper.scan;

import dev.yebatop.holyhelper.core.Numbers;
import dev.yebatop.holyhelper.core.Patterns;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;

/**
 * Разбор товаров Скупца из названия предмета и строк его подсказки.
 * <p>
 * Здесь намеренно нет ни одного класса Minecraft. Всё, что зависит от игры, живёт
 * в {@link BuyerScanner}, а сюда приходят уже готовые строки. Иначе эту логику
 * нельзя было бы проверить иначе как запуском игры, а её нужно проверять на строках,
 * снятых с живого сервера, — именно так вскрылось, что у особого предложения цена
 * подписана другими словами.
 */
public final class BuyerParser {

    private final Patterns patterns;

    public BuyerParser(Patterns patterns) {
        this.patterns = patterns;
    }

    /**
     * Товар Скупца.
     *
     * @param batchSize котировка сервера — сколько штук в объявленной цене.
     *                  Сейчас у всех товаров 16, но читаем из строки, а не считаем константой.
     * @param rotation  сколько осталось до обновления ассортимента; {@code null}, если
     *                  сервер не написал таймер.
     */
    public record Offer(
            String name,
            String itemId,
            boolean special,
            long available,
            int batchSize,
            long batchPrice,
            long finalPrice,
            Duration rotation) {

        /**
         * Цена за штуку. Дробная и обязана таковой остаться: у глины 346 за 16 даёт 21,62,
         * а округление до 21,6 уже врёт при сравнении с Маркетом.
         */
        public double unitPrice() {
            return batchSize <= 0 ? 0 : (double) finalPrice / batchSize;
        }

        /** Во сколько раз множители подняли цену. 1.0 — множителей нет. */
        public double multiplierFactor() {
            return batchPrice <= 0 ? 1 : (double) finalPrice / batchPrice;
        }
    }

    /** Множитель торговли: категория и остаток в стаках. */
    public record Multiplier(String category, int level, int stacks) {
    }

    /**
     * Этап торговли.
     *
     * @param goal        сколько монеток нужно заработать
     * @param description чем именно торговать; сервер разбивает текст переносами
     *                    на несколько строк, здесь он уже склеен
     * @param locked      закрыт ли этап невыполненными предыдущими
     * @param progress    накоплено; {@code -1}, если сервер не написал прогресс —
     *                    у закрытых этапов его нет вовсе
     */
    public record Stage(
            int number,
            long goal,
            String description,
            boolean locked,
            boolean completed,
            long progress) {

        public boolean hasProgress() {
            return progress >= 0;
        }

        /** Доля выполнения от нуля до единицы. Ноль, если прогресс неизвестен. */
        public double completion() {
            return hasProgress() && goal > 0 ? Math.min(1.0, (double) progress / goal) : 0;
        }
    }

    /** Закрытая ячейка товара: сколько этапов нужно, чтобы её открыть. */
    public record LockedSlot(int stagesRequired) {
    }

    /**
     * Надбавки уровней множителя в процентах, как их печатает справка сервера.
     * <p>
     * Это не украшение, а арифметика выгоды: множители разных категорий
     * перемножаются, поэтому итог считается как произведение {@code (1 + надбавка)}.
     * Зашивать 5, 10 и 15 в код незачем — сервер называет их сам.
     */
    public record Bonuses(int levelOne, int levelTwo, int levelThree) {

        /** Надбавка уровня в долях единицы. Ноль для неизвестного уровня. */
        public double share(int level) {
            return switch (level) {
                case 1 -> levelOne / 100.0;
                case 2 -> levelTwo / 100.0;
                case 3 -> levelThree / 100.0;
                default -> 0;
            };
        }
    }

    /**
     * Собирает товар из подсказки. Пусто — значит это не товар: стекло-разделитель,
     * кнопка «Назад» или книга со справкой.
     */
    public Optional<Offer> parseOffer(String name, String itemId, List<String> lore) {
        OptionalLong available = firstNumber("buyer.available", lore);
        if (available.isEmpty()) {
            // Единственный обязательный признак товара. Всё остальное сервер может
            // и не написать, но объём приёма есть у каждого предложения.
            return Optional.empty();
        }

        int batchSize = 0;
        long batchPrice = 0;
        for (String line : lore) {
            Optional<Matcher> batch = patterns.match("buyer.batchPrice", line);
            if (batch.isPresent()) {
                batchSize = (int) Numbers.parse(batch.get().group(1)).orElse(0);
                batchPrice = Numbers.parse(batch.get().group(2)).orElse(0);
                break;
            }
        }

        // «Цена с множителями» у обычного товара и «с учётом множителей» у особого —
        // одна регулярка. Если строки нет вовсе, итог равен начальной цене.
        long finalPrice = firstNumber("buyer.finalPrice", lore).orElse(batchPrice);

        boolean special = lore.stream()
                .anyMatch(line -> patterns.match("buyer.special", line).isPresent());

        return Optional.of(new Offer(
                name, itemId, special, available.getAsLong(),
                batchSize, batchPrice, finalPrice, parseRotation(lore)));
    }

    /**
     * Множитель торговли.
     * <p>
     * Здесь единственное место, где метка и значение стоят на разных строках:
     * «Доступно:», а стаки — следующей строкой. Поэтому идём по строкам с оглядкой
     * на предыдущую, а не разбираем каждую саму по себе.
     */
    public Optional<Multiplier> parseMultiplier(String name, List<String> lore) {
        Optional<Matcher> title = patterns.match("multiplier.name", name);
        if (title.isEmpty()) {
            return Optional.empty();
        }
        String category = title.get().group(1);

        // Уровень стоит не в названии, а в строке остатка, и римской цифрой:
        // «100 стаков с множителем I ур.». В названии его нет вовсе.
        int level = valueNearLabel("multiplier.availableLabel", "multiplier.level", lore)
                .map(matcher -> roman(matcher.group(1)))
                .orElse(0);

        int stacks = valueNearLabel("multiplier.availableLabel", "multiplier.stacks", lore)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .orElse(0);

        return Optional.of(new Multiplier(category, level, stacks));
    }

    /**
     * Этап из подсказки золотого слитка в «Этапах и наградах».
     * <p>
     * Цель здесь звучит иначе, чем у ежедневной сделки: «Заработать 175 000 монеток,
     * торгуя» против «Заработайте у Скупца 15 000 монеток». Это разные строки, и одной
     * регуляркой их не поймать. Сам текст цели сервер разбивает переносами на три
     * строки, поэтому описание собирается склейкой до первого служебного раздела.
     */
    public Optional<Stage> parseStage(String name, List<String> lore) {
        Optional<Matcher> number = patterns.match("stage.number", name);
        if (number.isEmpty()) {
            return Optional.empty();
        }

        long goal = firstNumber("stage.goal", lore).orElse(-1);
        boolean locked = lore.stream()
                .anyMatch(line -> patterns.match("stage.locked", line).isPresent());

        // Прогресс есть только у открытого этапа: у закрытого сервер его не пишет,
        // и подставлять туда ноль было бы враньём — это «неизвестно», а не «нисколько».
        long progress = valueNearLabel("buyer.progressLabel", "buyer.progressValue", lore)
                .map(matcher -> Numbers.parse(matcher.group(1)).orElse(-1))
                .orElse(-1L);

        // Состояний три, а не два: закрытый предыдущими, текущий с прогрессом
        // и уже выполненный. У выполненного нет ни прогресса, ни отметки о закрытии,
        // и без отдельного признака он выглядел бы как текущий с неизвестным прогрессом.
        boolean completed = lore.stream()
                .anyMatch(line -> patterns.match("stage.completed", line).isPresent());

        return Optional.of(new Stage(
                Integer.parseInt(number.get().group(1)),
                goal, describe(lore), locked, completed, progress));
    }

    /** Закрытая ячейка товара. У неё нет объёма приёма, поэтому товаром она не считается. */
    public Optional<LockedSlot> parseLockedSlot(List<String> lore) {
        boolean locked = lore.stream()
                .anyMatch(line -> patterns.match("buyer.lockedSlot", line).isPresent());
        if (!locked) {
            return Optional.empty();
        }
        int stages = firstMatch("buyer.lockedSlotStages", lore)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .orElse(0);
        return Optional.of(new LockedSlot(stages));
    }

    /**
     * Склеивает текст цели этапа.
     * <p>
     * Идём от строки с целью и добираем следующие, пока не упрёмся в пустую строку
     * или в служебный раздел — отметку о закрытии либо заголовок награды.
     */
    private String describe(List<String> lore) {
        StringBuilder text = new StringBuilder();
        boolean started = false;

        for (String line : lore) {
            if (!started) {
                started = patterns.match("stage.goal", line).isPresent();
                if (!started) {
                    continue;
                }
            } else if (patterns.match("stage.locked", line).isPresent()
                    || patterns.match("stage.rewardsHeader", line).isPresent()
                    || clean(line).isEmpty()) {
                break;
            }

            String piece = clean(line);
            if (piece.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(piece);
        }
        return text.toString();
    }

    /** Снимает оформление: полоску слева и невидимый хвост справа. */
    private static String clean(String line) {
        return line.replaceAll("§.*$", "")
                .replaceAll("^[^\\p{L}\\p{N}]+", "")
                .trim();
    }

    /** Надбавки уровней из справки «Множители торговли». */
    public Optional<Bonuses> parseBonuses(List<String> lore) {
        return firstMatch("multiplier.bonusLevels", lore).map(matcher -> new Bonuses(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }

    /** Уровни сервер пишет римскими цифрами и не выше третьего. */
    private static int roman(String value) {
        return switch (value) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
    }

    /**
     * Периоды ротации из справки «Полезная информация».
     * <p>
     * Сервер печатает их сам — «обычные торги обновляются раз в 6 часов», «особые —
     * раз в 8». Читаем оттуда, а не зашиваем числа: поменяют правила, мод подхватит.
     */
    public Optional<Duration> parseRotationPeriod(List<String> lore, boolean special) {
        String key = special ? "info.specialRotation" : "info.regularRotation";
        return firstMatch(key, lore)
                .map(matcher -> Duration.ofHours(Long.parseLong(matcher.group(1))));
    }

    /** Остаток до обновления ассортимента. Часы сервер пишет не всегда. */
    private Duration parseRotation(List<String> lore) {
        return firstMatch("buyer.rotation", lore)
                .map(matcher -> Duration
                        .ofHours(group(matcher, 1))
                        .plusMinutes(group(matcher, 2))
                        .plusSeconds(group(matcher, 3)))
                .orElse(null);
    }

    private static long group(Matcher matcher, int index) {
        String value = matcher.group(index);
        return value == null ? 0 : Long.parseLong(value);
    }

    /**
     * Ищет значение рядом с меткой — в той же строке либо в одной из двух следующих.
     * <p>
     * Сервер переносит строку посреди фразы, и делает это непоследовательно: у
     * ежедневной сделки «Прогресс: 0 / 15 000» умещается в строку, а у этапа
     * «Прогресс:» и «800 / 1 000» разъезжаются на две. То же у множителя с
     * «Доступно:» и «0 стаков». Разбирать это по отдельности — значит чинить
     * одну и ту же ошибку снова и снова, поэтому приём общий.
     */
    private Optional<Matcher> valueNearLabel(String labelKey, String valueKey, List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (patterns.match(labelKey, lore.get(i)).isEmpty()) {
                continue;
            }
            // Сначала та же строка: если значение уместилось рядом с меткой, оно там.
            Optional<Matcher> here = patterns.match(valueKey, lore.get(i));
            if (here.isPresent()) {
                return here;
            }
            // Дальше — пара ближайших строк. Больше заглядывать нельзя: подхватим
            // чужое число из следующего раздела подсказки.
            for (int j = i + 1; j < lore.size() && j <= i + 2; j++) {
                Optional<Matcher> next = patterns.match(valueKey, lore.get(j));
                if (next.isPresent()) {
                    return next;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Matcher> firstMatch(String key, List<String> lore) {
        for (String line : lore) {
            Optional<Matcher> matcher = patterns.match(key, line);
            if (matcher.isPresent()) {
                return matcher;
            }
        }
        return Optional.empty();
    }

    private OptionalLong firstNumber(String key, List<String> lore) {
        for (String line : lore) {
            OptionalLong value = patterns.number(key, line);
            if (value.isPresent()) {
                return value;
            }
        }
        return OptionalLong.empty();
    }
}
