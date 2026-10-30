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

/**
 * 1.19.2 uses isDirty/getServers/markClean rather than takeDirtyServers.
 * Re-evaluate the server list whenever VPN peers change, even if the LAN list is clean.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {
    @Unique private List<String> openrad$previousVpn = List.of();

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/LanServerDetection$LanServerList;isDirty()Z"))
    private boolean openrad$needsRefresh(LanServerDetection.LanServerList physical) {
        List<String> signature = OpenRadClientMod.lanServers().stream()
                .map(server -> server.address() + ":" + server.port() + "\n" + server.motd()).toList();
        return physical.isDirty() || !signature.equals(openrad$previousVpn);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/LanServerDetection$LanServerList;getServers()Ljava/util/List;"))
    private List<LanServer> openrad$mergeServers(LanServerDetection.LanServerList physical) {
        var vpn = OpenRadClientMod.lanServers();
        openrad$previousVpn = vpn.stream()
                .map(server -> server.address() + ":" + server.port() + "\n" + server.motd()).toList();
        var combined = new LinkedHashMap<String, LanServer>();
        for (LanServer server : physical.getServers()) combined.put(server.getAddress(), server);
        for (var server : vpn) {
            String address = server.address() + ":" + server.port();
            combined.putIfAbsent(address, new LanServer("[OpenRad] " + server.motd(), address));
        }
        return new ArrayList<>(combined.values());
    }
}
