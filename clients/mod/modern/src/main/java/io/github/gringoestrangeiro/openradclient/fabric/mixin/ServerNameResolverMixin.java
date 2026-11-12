package io.github.gringoestrangeiro.openradclient.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.gringoestrangeiro.openradclient.fabric.OpenRadClientMod;
import io.github.gringoestrangeiro.openradclient.fabric.VirtualAddressResolution;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    @WrapOperation(method = "resolveAddress", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;"
                    + "resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
    private Optional<ResolvedServerAddress> openrad$resolveLiteral(ServerAddressResolver resolver,
            ServerAddress address, Operation<Optional<ResolvedServerAddress>> original) {
        if (OpenRadClientMod.usesVirtualRoute(address.getHost())) {
            return VirtualAddressResolution.resolve(address.getHost(), address.getPort());
        }
        return original.call(resolver, address);
    }

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
