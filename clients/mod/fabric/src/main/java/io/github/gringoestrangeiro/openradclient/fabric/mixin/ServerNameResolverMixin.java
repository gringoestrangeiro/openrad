package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    /** A literal VPN address needs neither an SRV lookup nor a replacement destination. */
    @WrapOperation(method = "resolveAddress", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;"
                    + "lookupRedirect(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
    private Optional<ServerAddress> openrad$lookupRedirect(ServerRedirectHandler redirects,
            ServerAddress address, Operation<Optional<ServerAddress>> lookup) {
        return OpenRadClientMod.usesVirtualRoute(address.getHost())
                ? Optional.empty()
                : lookup.call(redirects, address);
    }
}
