package io.github.gringoestrangeiro.openradclient.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prefer mod-owned translations. For Minecraft locales without a dedicated
 * OpenRad translation, reuse native Minecraft language keys. No external
 * translation service, network requests or hardcoded game language is involved.
 *
 * Native fallback is intentionally short rather than inventing a bad translation
 * for detailed, security-sensitive settings. Those can fall back to English.
 */
public final class OpenRadI18n {
    private static final Map<String, Set<String>> LOCALE_KEYS = new ConcurrentHashMap<>();
    private OpenRadI18n() {}

    private static Set<String> availableKeys(String locale) {
        return LOCALE_KEYS.computeIfAbsent(locale, code -> {
            String resource = "assets/openrad-client/lang/" + code + ".json";
            try (var in = OpenRadI18n.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) return Set.of();
                JsonObject json = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                return Set.copyOf(json.keySet());
            } catch (Exception invalid) {
                return Set.of();
            }
        });
    }

    public static Component tr(String key, Object... args) {
        String selected = Minecraft.getInstance().getLanguageManager()
                .getSelected().toLowerCase(Locale.ROOT);
        if (availableKeys(selected).contains(key))
            return Component.translatable(key, args);
        return fallback(key, args);
    }

    private static Component argument(Object[] args, int index) {
        if (args.length <= index) return Component.empty();
        Object item = args[index];
        return item instanceof Component c ? c : Component.literal(String.valueOf(item));
    }

    private static Component pair(Component a, Component b) {
        return a.copy().append(" • ").append(b);
    }

    private static Component fallback(String key, Object[] args) {
        // The vanilla UI already translates these semantic building blocks into
        // the player's selected Minecraft language, including Japanese/Arabic.
        return switch (key) {
            case "openrad.menu.connect", "openrad.command.connecting" ->
                    Component.translatable("gui.friends.join");
            case "openrad.menu.disconnect", "openrad.command.disconnected" ->
                    Component.translatable("menu.disconnect");
            case "openrad.menu.save", "openrad.menu.saved", "openrad.menu.save_reconnect" ->
                    Component.translatable("gui.done");
            case "openrad.menu.loading", "openrad.menu.busy", "openrad.menu.catalog_loading",
                 "openrad.phase.connecting", "openrad.phase.joining_networks",
                 "openrad.phase.retry_wait" -> Component.translatable("gui.loading");
            case "openrad.menu.advanced", "openrad.menu.selectors_technical" ->
                    Component.translatable("menu.options");
            case "openrad.menu.language" -> Component.translatable("options.language");
            case "openrad.menu.any_language" ->
                    pair(Component.translatable("gui.all"), Component.translatable("options.language"));
            case "openrad.menu.all_networks" ->
                    pair(Component.translatable("gui.all"), Component.translatable("lanServer.title"));
            case "openrad.menu.all_language" ->
                    pair(Component.translatable("gui.all"), argument(args, 0));
            case "openrad.menu.network_numbered" ->
                    pair(argument(args, 0), argument(args, 1));
            case "openrad.menu.networks", "openrad.command.networks" ->
                    pair(Component.translatable("menu.multiplayer"), argument(args, 0));
            case "openrad.menu.catalog_count", "openrad.menu.page" ->
                    pair(argument(args, 0), argument(args, 1));
            case "openrad.menu.no_networks", "openrad.menu.catalog_unavailable",
                 "openrad.menu.filter_none" -> Component.translatable("gui.none");
            case "openrad.menu.clear" -> Component.translatable("selectServer.delete");
            case "openrad.menu.auto_connect" ->
                    Component.literal("OpenRad • ").append(Component.translatable("options.online"));
            case "openrad.menu.status" -> Component.literal("OpenRad • ").append(argument(args, 0));
            case "openrad.menu.summary" -> Component.literal("IP ")
                    .append(argument(args, 0)).append(" • #")
                    .append(argument(args, 1)).append(" • #").append(argument(args, 2));
            case "openrad.phase.offline", "openrad.phase.stopped" ->
                    Component.translatable("gui.friends.presence.status.offline");
            case "openrad.phase.connected" ->
                    Component.translatable("gui.friends.presence.status.online");
            case "openrad.phase.failed" -> Component.translatable("disconnect.lost");
            case "openrad.menu.exact_networks" -> Component.translatable("addServer.enterName");
            case "openrad.menu.reconnect_note" -> Component.translatable("gui.continue");
            // Preserve technical meaning for less common diagnostics if no
            // translation is provided, rather than mislabeling a control.
            default -> Component.translatable(key, args);
        };
    }
}
