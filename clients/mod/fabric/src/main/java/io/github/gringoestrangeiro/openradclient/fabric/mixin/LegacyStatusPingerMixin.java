package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.net.InetSocketAddress;

@Mixin(ServerStatusPinger.class)
public abstract class LegacyStatusPingerMixin {
    /** The old ping fallback bypasses Connection.connect and must also stay inside the VPN. */
    @ModifyVariable(method = "pingLegacyServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private InetSocketAddress openrad$routeLegacyPing(InetSocketAddress destination) {
        return OpenRadClientMod.route(destination);
    }
}
