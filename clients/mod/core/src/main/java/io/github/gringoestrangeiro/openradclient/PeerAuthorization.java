package io.github.gringoestrangeiro.openradclient;

import io.github.gringoestrangeiro.openradplugin.network.Membership;
import java.net.Inet4Address;
import java.util.*;

/** Immutable per-scan grants. Persisted memberships outside explicit configuration grant nothing. */
final class PeerAuthorization {
    record Grant(Membership.Peer peer, Set<String> networkIds) {
        Grant { networkIds = Set.copyOf(networkIds); }
    }

    static Map<Long, Grant> select(Membership membership, Set<String> selectedNames) {
        Map<Long, Grant> result = new LinkedHashMap<>();
        Map<String, Membership.Network> networks = membership.networks();
        Map<Inet4Address, Long> addresses = new HashMap<>();
        Set<Inet4Address> ambiguous = new HashSet<>();
        for (Membership.Peer peer : membership.eligible(selectedNames, Set.of(), Set.of())) {
            if ((peer.vip().getAddress()[0] & 255) != 26) continue;
            Set<String> allowed = new HashSet<>();
            for (String id : peer.networks()) {
                Membership.Network network = networks.get(id);
                if (network != null && selectedNames.contains(network.name())
                        && !Integer.valueOf(0).equals(membership.role(id, membership.ownRid()))
                        && !Integer.valueOf(0).equals(membership.role(id, peer.rid()))) allowed.add(id);
            }
            if (allowed.isEmpty()) continue;
            if (addresses.putIfAbsent(peer.vip(), peer.rid()) != null) ambiguous.add(peer.vip());
            result.put(peer.rid(), new Grant(peer, allowed));
        }
        // A virtual address must resolve to one authenticated identity, never an arbitrary winner.
        result.values().removeIf(grant -> ambiguous.contains(grant.peer().vip()));
        return Map.copyOf(result);
    }
}
