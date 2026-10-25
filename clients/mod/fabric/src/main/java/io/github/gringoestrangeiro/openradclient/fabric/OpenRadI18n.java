package io.github.gringoestrangeiro.openradclient.fabric;

import net.minecraft.network.chat.Component;

/**
 * Mod-owned translations only. Minecraft loads en_us beneath every selected locale, so a key a
 * locale file lacks shows the English text. Vanilla stand-in keys are never substituted: they
 * changed meanings (for example "Done" for "Save and reconnect").
 */
public final class OpenRadI18n {
    private OpenRadI18n() {}

    public static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
