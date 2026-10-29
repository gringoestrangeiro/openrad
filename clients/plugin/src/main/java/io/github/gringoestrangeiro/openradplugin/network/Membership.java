package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.net.Inet4Address;
import java.util.*;

/** Owned by the authenticated attachment worker. Returned views are immutable. */
public final class Membership {
    public record Network(String id, String name) {}

    public record Peer(
            long rid,
            String name,
            Inet4Address vip,
            String server,
            int state,
            Set<String> networks) {
        public Peer {
            networks = Set.copyOf(networks);
        }
    }

    static final int MAX_PEERS = 65536;

    private final long ownRid;
    private long ignoredPeers;
    private final Map<String, Network> networks = new LinkedHashMap<>();
    private final Map<Long, Peer> peers = new LinkedHashMap<>();
    private final Map<String, Map<Long, Integer>> roles = new HashMap<>();

    public Membership(long ownRid) {
        this.ownRid = ownRid;
    }

    /** Members skipped because the roster exceeded {@link #MAX_PEERS}. */
    public long ignoredPeers() {
        return ignoredPeers;
    }

    public long ownRid() {
        return ownRid;
    }

    public Map<String, Network> networks() {
        return Map.copyOf(networks);
    }

    public Map<Long, Peer> peers() {
        return Map.copyOf(peers);
    }

    public Integer role(String id, long rid) {
        return roles.getOrDefault(id, Map.of()).get(rid);
    }

    public void removeNetwork(String id) {
        networks.remove(id);
        roles.remove(id);
        for (Peer p : List.copyOf(peers.values())) {
            Set<String> ids = new HashSet<>(p.networks());
            ids.remove(id);
            if (ids.isEmpty()) peers.remove(p.rid());
            else
                peers.put(
                        p.rid(), new Peer(p.rid(), p.name(), p.vip(), p.server(), p.state(), ids));
        }
    }

    private void removeMember(String id, long rid) {
        if (rid == ownRid) {
            removeNetwork(id);
            return;
        }
        Map<Long, Integer> rolesIn = roles.get(id);
        if (rolesIn != null) rolesIn.remove(rid);
        Peer p = peers.get(rid);
        if (p == null) return;
        Set<String> ids = new HashSet<>(p.networks());
        ids.remove(id);
        if (ids.isEmpty()) peers.remove(rid);
        else peers.put(rid, new Peer(rid, p.name(), p.vip(), p.server(), p.state(), ids));
    }

    private void role(List<TlvCodec.Record> f, long rid) {
        TlvCodec.optional(f, 0x0100030a)
                .ifPresent(
                        v ->
                                roles.computeIfAbsent(
                                                guid(field(f, 0x0d000309)), k -> new HashMap<>())
                                        .put(rid, (int) TlvCodec.int32(v)));
    }

    private void network(byte[] value) {
        var f = fields(value);
        String id = guid(field(f, 0x0d000309));
        String name = display(field(f, 0x03000306));
        role(f, ownRid);
        networks.put(id, new Network(id, name));
    }

    private void peer(byte[] value, boolean change) {
        var f = fields(value);
        long rid = wide(f, 0x020001e1);
        String name = display(field(f, 0x03000304));
        Inet4Address ip = ipv4(number(f, 0x01000305));
        String server = TlvCodec.optional(f, 0x030001c9).map(TlvCodec::text).orElse(null);
        Long explicitState = TlvCodec.optional(f, 0x010003a0).map(TlvCodec::int32).orElse(null);
        Peer known = peers.get(rid);
        // Defensive memory bound far above real rosters. Never fail the session over it: a
        // throw here would make every login fail while the identity stays a member.
        if (known == null && peers.size() >= MAX_PEERS) {
            ignoredPeers++;
            return;
        }
        Set<String> ids = new HashSet<>(known != null ? known.networks() : Set.of());
        int state;
        if (change) {
            String id = guid(field(f, 0x0d000309));
            role(f, rid);
            ids.add(id);
            if (known != null) {
                // Partial updates keep the coordinator and presence already known.
                if (server == null) server = known.server();
                state = explicitState != null ? explicitState.intValue() : known.state();
            } else {
                // A new member announced by the authenticated service is live when
                // its coordinator is known, unless the service reports otherwise.
                state = explicitState != null ? explicitState.intValue() : server != null ? 1 : 0;
            }
        } else {
            state =
                    explicitState != null
                            ? explicitState.intValue()
                            : known != null ? known.state() : 0;
        }
        peers.put(rid, new Peer(rid, name, ip, server, state, ids));
    }

    public void snapshot(byte[] data) {
        var root = TlvCodec.optional(fields(data), 0x1316);
        if (root.isEmpty()) return;
        var entries = fields(root.get());
        for (var r : entries) {
            if (r.tag() == 0x1315) network(r.value());
            else if (r.tag() == 0x1317) peer(r.value(), false);
        }
        for (var r : entries)
            if (r.tag() == 0x1318) {
                var f = fields(r.value());
                long rid = wide(f, 0x020001e1);
                role(f, rid);
                Peer p = peers.get(rid);
                if (p != null) {
                    var ids = new HashSet<>(p.networks());
                    ids.add(guid(field(f, 0x0d000309)));
                    peers.put(rid, new Peer(rid, p.name(), p.vip(), p.server(), p.state(), ids));
                }
            }
    }

    public void changes(byte[] data) {
        var root = TlvCodec.optional(fields(data), 0x131f);
        if (root.isEmpty()) return;
        var entries = fields(root.get());
        for (var r : entries)
            if (r.tag() == 0x131e) {
                var f = fields(r.value());
                if (TlvCodec.optional(f, 0x01000305).isPresent()) peer(r.value(), true);
                else role(f, wide(f, 0x020001e1));
            }
        for (var r : entries) if (r.tag() == 0x1323) network(r.value());
        for (var r : entries)
            if (r.tag() == 0x1318) {
                var f = fields(r.value());
                role(f, eventMember(f));
            }
        for (var r : entries)
            if (r.tag() == 0x131d) {
                var f = fields(r.value());
                removeMember(guid(field(f, 0x0d000309)), eventMember(f));
            }
        for (var r : entries)
            if (r.tag() == 0x132e) removeNetwork(guid(field(fields(r.value()), 0x0d000309)));
        for (var r : entries)
            if (r.tag() == 0x1366) {
                var f = fields(r.value());
                long rid = wide(f, 0x020001e1);
                int state = (int) number(f, 0x010003a0);
                Peer p = peers.get(rid);
                if (p != null)
                    peers.put(
                            rid, new Peer(rid, p.name(), p.vip(), p.server(), state, p.networks()));
            }
    }

    private long eventMember(List<TlvCodec.Record> f) {
        var ids = f.stream().filter(r -> r.tag() == 0x020001e1).toList();
        require(!ids.isEmpty() && ids.size() <= 2, "event member count");
        for (var r : ids) TlvCodec.int64(r.value());
        return TlvCodec.int64(ids.get(0).value());
    }

    public List<Peer> eligible(Set<String> names, Set<Long> allowed, Set<Long> denied) {
        Set<String> ids = new HashSet<>();
        for (Network n : networks.values())
            if (names.contains(n.name()) && !Integer.valueOf(0).equals(role(n.id(), ownRid)))
                ids.add(n.id());
        return peers.values().stream()
                .filter(
                        p ->
                                p.rid() != ownRid
                                        && p.server() != null
                                        && (p.state() == 1 || p.state() == 5)
                                        && !denied.contains(p.rid())
                                        && (allowed.isEmpty() || allowed.contains(p.rid()))
                                        && p.networks().stream()
                                                .anyMatch(
                                                        id ->
                                                                ids.contains(id)
                                                                        && !Integer.valueOf(0)
                                                                                .equals(
                                                                                        role(
                                                                                                id,
                                                                                                p
                                                                                                        .rid()))))
                .toList();
    }

    public static Inet4Address ownVip(byte[] data) {
        if (TlvCodec.operation(data) != 38) return null;
        var f = fields(field(fields(data), 0x1316));
        var ip = TlvCodec.optional(f, 0x01000305);
        if (ip.isEmpty()) return null;
        require(field(f, 0x09000305).length == 16, "companion address width");
        Inet4Address vip = ipv4(TlvCodec.int32(ip.get()));
        require((vip.getAddress()[0] & 255) == 26, "service VIP outside 26/8");
        return vip;
    }
}
