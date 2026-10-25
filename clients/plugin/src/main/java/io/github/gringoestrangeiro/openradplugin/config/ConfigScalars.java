package io.github.gringoestrangeiro.openradplugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Strict YAML scalar readers: a wrong type fails closed instead of silently falling back to the
 * default, as Bukkit's {@code getInt}/{@code getBoolean} do for quoted values.
 */
final class ConfigScalars {
    private ConfigScalars() {}

    static int integer(FileConfiguration c, String key, int def, int lo, int hi) {
        Object raw = c.get(key);
        if (raw == null) return def;
        if (!(raw instanceof Byte
                || raw instanceof Short
                || raw instanceof Integer
                || raw instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an unquoted integer");
        }
        long value = ((Number) raw).longValue();
        if (value < lo || value > hi) {
            throw new IllegalArgumentException(
                    key + " must be an unquoted integer between " + lo + " and " + hi);
        }
        return (int) value;
    }

    static boolean flag(FileConfiguration c, String key, boolean def) {
        Object raw = c.get(key);
        if (raw == null) return def;
        if (!(raw instanceof Boolean)) {
            throw new IllegalArgumentException(key + " must be an unquoted true/false");
        }
        return (Boolean) raw;
    }
}
