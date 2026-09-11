package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.Numbers;
import dev.yebatop.holyhelper.analytics.Liquidity;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.BuyerParser;
import dev.yebatop.holyhelper.scan.MarketParser;
import dev.yebatop.holyhelper.scan.ScreenReader;
import dev.yebatop.holyhelper.store.PriceStore;
import dev.yebatop.holyhelper.ui.Fonts;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Дописывает к подсказке предмета то, что мод знает о его цене.
 * <p>
 * Это единственное место, где ответ появляется там же, где вопрос: игрок держит
 * предмет в инвентаре и решает, нести его Скупцу или выставлять на Маркет. За тем
 * же самым иначе пришлось бы закрыть окно и набрать команду.
 * <p>
 * Обе цифры сопровождаются давностью, если они не свежие. Ассортимент Скупца
 * меняется каждые несколько часов, а лоты Маркета раскупают, и цена без отметки
 * времени однажды соврала бы молча.
 * <p>
 * Строки мода набраны его же гарнитурой, а не вшитой в клиент: в подсказке они
 * стоят вплотную к тексту сервера, и по шрифту сразу видно, где кончается одно
 * и начинается другое.
 */
public final class ItemPriceTooltip {

    /** Имя функции в реестре LiteAPI. */
    public static final String FEATURE = "price-tooltip";

    /** Наблюдения Маркета старше этого срока в подсказку не идут. */
    private static final Duration MARKET_MEMORY = Duration.ofHours(12);

    /** Цена Скупца живёт до ротации; после шести часов она заведомо не про этот ассортимент. */
    private static final Duration BUYER_MEMORY = Duration.ofHours(6);

    /** До этого возраста давность не пишем — она ничего не меняет и только шумит. */
    private static final Duration QUIET_AGE = Duration.ofMinutes(15);

    private ItemPriceTooltip() {
    }

    public static void register() {
        // Типы аргументов выводятся сами, поэтому имена классов подсказки
        // называть не нужно — на них и ломается совместимость между версиями.
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> append(stack, lines));
    }

    private static void append(ItemStack stack, List<Text> lines) {
        HolyHelperClient mod = HolyHelperClient.instance();
        if (mod == null || stack.isEmpty() || !ServerDetector.onHolyWorld()) {
            return;
        }
        if (mod.featureGate().status() == FeatureGate.Status.ANSWERED
                && !mod.featureGate().isAllowed(FEATURE)) {
            return;
        }

        String itemId;
        try {
            itemId = Registries.ITEM.getId(stack.getItem()).toString();
        } catch (RuntimeException e) {
            return;
        }

        Text unit = unitLine(mod, stack, itemId);
        Text buyer = buyerLine(mod, itemId);
        Text market = marketLine(mod, itemId);
        Text moves = movesLine(mod, itemId);
        if (unit == null && buyer == null && market == null && moves == null) {
            return;
        }

        lines.add(Text.empty());
        if (unit != null) {
            lines.add(unit);
        }
        if (buyer != null) {
            lines.add(buyer);
        }
        if (market != null) {
            lines.add(market);
        }
        if (moves != null) {
            lines.add(moves);
        }
    }

    /**
     * По какой цене лоты уходят, а по какой висят.
     * <p>
     * Единственная строка в подсказке, которая говорит не о запросе, а о спросе.
     * Всё остальное здесь — сколько просят; это — доживают ли лоты такой цены до
     * конца срока. Стоит она последней намеренно: это вывод, а не наблюдение, и
     * читать его надо после того, из чего он сделан.
     */
    private static Text movesLine(HolyHelperClient mod, String itemId) {
        Liquidity.Split split = Liquidity.split(mod.prices().samples(itemId, MARKET_MEMORY))
                .orElse(null);
        if (split == null || !split.notable()) {
            // Лотов мало, цены одинаковы или разницы в возрасте нет — сказать
            // нечего. Пустая строка «данных недостаточно» в подсказке, которую
            // открывают сотни раз за вечер, обходится дороже, чем стоит.
            return null;
        }

        String text;
        Formatting color;
        if (split.cheapMovesFaster()) {
            text = "По " + split.cheaper().medianPrice() + " разбирают, по "
                    + split.dearer().medianPrice() + " висят";
            color = Formatting.AQUA;
        } else {
            // Обратный случай: дорогие лоты моложе. Это не про спрос — просто
            // кто-то выставил их недавно. Говорим ровно это.
            text = "Дорогие лоты свежие — про спрос это ничего не говорит";
            color = Formatting.DARK_GRAY;
        }
        return Text.literal(text + " · " + split.cheaper().lots() + "+"
                        + split.dearer().lots() + " лотов")
                .setStyle(Fonts.NUM.withColor(color));
    }

    /**
     * Настоящая цена за штуку у лота, который продают только целиком.
     * <p>
     * Сервер в таком лоте пишет «Цена за 1 ед.» равной полной цене — раз единицу
     * не купить, то и цены у неё как бы нет. Для решения это бесполезно: чтобы
     * понять, дорого или дёшево, нужна цена за блок, и её приходится делить в уме
     * прямо над окном. Мод делит сам.
     */
    private static Text unitLine(HolyHelperClient mod, ItemStack stack, String itemId) {
        List<String> lore = ScreenReader.loreOf(stack);
        if (lore.isEmpty()) {
            return null;
        }
        MarketParser.Lot lot = mod.market().parser()
                .parseLot(stack.getName().getString(), itemId, stack.getCount(), lore)
                .orElse(null);
        if (lot == null || !lot.wholeOnly() || lot.count() <= 1) {
            return null;
        }
        return Text.literal("За штуку " + lot.unitPrice() + " · сервер этого не пишет")
                .setStyle(Fonts.NUM.withColor(Formatting.GOLD));
    }

    private static Text buyerLine(HolyHelperClient mod, String itemId) {
        BuyerParser.Offer offer = mod.buyer().offerOf(itemId).orElse(null);
        if (offer == null) {
            return null;
        }
        Duration age = Duration.between(mod.buyer().tradeSeenAt(), Instant.now());
        if (age.compareTo(BUYER_MEMORY) > 0) {
            // Ассортимент за это время сменился минимум раз — цена уже не про него.
            return null;
        }

        String text = String.format(Locale.ROOT, "Скупец %.2f за шт", offer.unitPrice());
        if (offer.available() > 0) {
            text += " · осталось " + offer.available();
        }
        return Text.literal(text + age(age)).setStyle(Fonts.NUM.withColor(Formatting.GOLD));
    }

    private static Text marketLine(HolyHelperClient mod, String itemId) {
        PriceStore.Known known = mod.prices().known(itemId, MARKET_MEMORY).orElse(null);
        if (known == null) {
            return null;
        }

        String samples = Numbers.counted(known.samples(), "лот", "лота", "лотов");
        String text = "Маркет от " + known.cheapestUnitPrice() + " · " + samples;

        // Одно наблюдение весит меньше десяти, и цвет об этом говорит так же,
        // как в списке товаров: жёлтое — «возможно», зелёное — «видел не раз».
        Formatting color = known.samples() > 1 ? Formatting.AQUA : Formatting.YELLOW;
        return Text.literal(text + age(Duration.between(known.seenAt(), Instant.now())))
                .setStyle(Fonts.NUM.withColor(color));
    }

    /** Давность в скобках — только когда она уже что-то значит. */
    private static String age(Duration age) {
        if (age.compareTo(QUIET_AGE) < 0) {
            return "";
        }
        long hours = age.toHours();
        return hours > 0 ? " (" + hours + " ч назад)" : " (" + age.toMinutes() + " мин назад)";
    }
}
