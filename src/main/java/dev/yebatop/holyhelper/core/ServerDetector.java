package dev.yebatop.holyhelper.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;
import java.util.Optional;

/**
 * Определяет, играем ли мы на HolyWorld и на каком именно режиме.
 * <p>
 * Вне серверов HolyWorld мод молчит целиком: он заточен под конкретные окна одного
 * проекта, и на чужом сервере от него был бы только вред.
 */
public final class ServerDetector {

    private static final String[] DOMAINS = {"holyworld.ru", "holyworld.me"};

    private ServerDetector() {
    }

    public static boolean onHolyWorld() {
        return currentAddress().map(address -> {
            String host = address.toLowerCase(Locale.ROOT);
            for (String domain : DOMAINS) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    public static Optional<String> currentAddress() {
        ServerInfo entry = MinecraftClient.getInstance().getCurrentServerEntry();
        if (entry == null || entry.address == null) {
            return Optional.empty();
        }
        // Адрес может быть вида host:port — порт нам не нужен.
        String address = entry.address;
        int colon = address.indexOf(':');
        return Optional.of(colon >= 0 ? address.substring(0, colon) : address);
    }
}
