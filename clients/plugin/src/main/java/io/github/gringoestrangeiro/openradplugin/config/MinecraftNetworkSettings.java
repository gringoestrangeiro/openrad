package io.github.gringoestrangeiro.openradplugin.config;

import io.github.gringoestrangeiro.openradplugin.network.MinecraftNetworks;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

public record MinecraftNetworkSettings(boolean enabled, List<String> select, int maxNetworks) {
    public static final MinecraftNetworkSettings DISABLED =
            new MinecraftNetworkSettings(false, List.of(), 256);

    public MinecraftNetworkSettings {
        if (select.size() > 1024 || maxNetworks < 1 || maxNetworks > 1024)
            throw new IllegalArgumentException("invalid Minecraft network limits");
        select = select.stream().map(MinecraftNetworks::validateSelector).distinct().toList();
        if (!enabled && !select.isEmpty())
            throw new IllegalArgumentException(
                    "Minecraft selectors require minecraft-networks.enabled");
    }

    public static MinecraftNetworkSettings from(FileConfiguration c) {
        if (c.contains("vpn.minecraft-networks.select")
                && !c.isList("vpn.minecraft-networks.select"))
            throw new IllegalArgumentException("minecraft-networks.select must be a YAML list");
        for (Object value : c.getList("vpn.minecraft-networks.select", List.of()))
            if (!(value instanceof String))
                throw new IllegalArgumentException("Minecraft selectors must be strings");
        return new MinecraftNetworkSettings(
                ConfigScalars.flag(c, "vpn.minecraft-networks.enabled", true),
                c.getStringList("vpn.minecraft-networks.select"),
                ConfigScalars.integer(c, "vpn.minecraft-networks.max-networks", 256, 1, 1024));
    }
}
