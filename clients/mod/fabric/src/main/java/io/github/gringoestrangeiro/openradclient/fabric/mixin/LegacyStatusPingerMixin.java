package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Mixin(ServerStatusPinger.class)
public abstract class LegacyStatusPingerMixin {
    /** The old ping fallback bypasses Connection.connect and must also stay inside the VPN. */
    @WrapOperation(method = "pingLegacyServer", at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;",
                    remap = false))
    private ChannelFuture openrad$connect(Bootstrap bootstrap, InetAddress host, int port,
            Operation<ChannelFuture> connect, @Local(argsOnly = true) InetSocketAddress destination) {
        return OpenRadClientMod.connect(destination, true, routed -> routed == destination
                ? connect.call(bootstrap, host, port)
                : connect.call(bootstrap, routed.getAddress(), routed.getPort()));
    }
}
