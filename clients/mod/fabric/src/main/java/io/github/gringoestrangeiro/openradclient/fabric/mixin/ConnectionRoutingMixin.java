package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.net.InetSocketAddress;

/** Minecraft 1.21.11: second connect argument is EventLoopGroupHolder, no longer boolean. */
@Mixin(Connection.class)
public abstract class ConnectionRoutingMixin {
    @ModifyVariable(
            method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static InetSocketAddress openrad$route(InetSocketAddress destination) {
        return OpenRadClientMod.route(destination);
    }
}
