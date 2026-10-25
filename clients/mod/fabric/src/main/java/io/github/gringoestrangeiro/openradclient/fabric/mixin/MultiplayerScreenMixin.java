package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {
    @Unique private List<LanServer> openrad$physical = List.of();
    @Unique private List<String> openrad$previousVpn = List.of();

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/LanServerDetection$LanServerList;takeDirtyServers()Ljava/util/List;"))
    private List<LanServer> openrad$mergeVpnServers(LanServerDetection.LanServerList physical) {
        List<LanServer> dirty = physical.takeDirtyServers();
        if (dirty != null) openrad$physical = List.copyOf(dirty);
        var vpn = OpenRadClientMod.lanServers();
        List<String> signature = vpn.stream().map(server -> server.address() + ":" + server.port() + "\n" + server.motd()).toList();
        if (dirty == null && signature.equals(openrad$previousVpn)) return null;
        openrad$previousVpn = signature;
        var combined = new LinkedHashMap<String, LanServer>();
        for (LanServer server : openrad$physical) combined.put(server.getAddress(), server);
        for (var server : vpn) {
            String address = server.address() + ":" + server.port();
            combined.putIfAbsent(address, new LanServer("[OpenRad] " + server.motd(), address));
        }
        return new ArrayList<>(combined.values());
    }
}
