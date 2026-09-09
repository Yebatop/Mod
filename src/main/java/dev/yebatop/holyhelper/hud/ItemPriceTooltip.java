package dev.yebatop.holyhelper.hud;

import dev.yebatop.holyhelper.HolyHelperClient;
import dev.yebatop.holyhelper.core.ServerDetector;
import dev.yebatop.holyhelper.liteapi.FeatureGate;
import dev.yebatop.holyhelper.scan.BuyerParser;
import dev.yebatop.holyhelper.store.PriceStore;
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

        Text buyer = buyerLine(mod, itemId);
        Text market = marketLine(mod, itemId);
        if (buyer == null && market == null) {
            return;
        }

        lines.add(Text.empty());
        if (buyer != null) {
            lines.add(buyer);
        }
        if (market != null) {
            lines.add(market);
        }
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
        return Text.literal(text + age(age)).formatted(Formatting.GOLD);
    }

    private static Text marketLine(HolyHelperClient mod, String itemId) {
        PriceStore.Known known = mod.prices().known(itemId, MARKET_MEMORY).orElse(null);
        if (known == null) {
            return null;
        }

        String samples = known.samples() == 1 ? "видел 1 раз" : "видел " + known.samples() + " раз";
        String text = "Маркет от " + known.cheapestUnitPrice() + " · " + samples;

        // Одно наблюдение весит меньше десяти, и цвет об этом говорит так же,
        // как в списке товаров: жёлтое — «возможно», зелёное — «видел не раз».
        Formatting color = known.samples() > 1 ? Formatting.AQUA : Formatting.YELLOW;
        return Text.literal(text + age(Duration.between(known.seenAt(), Instant.now())))
                .formatted(color);
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
