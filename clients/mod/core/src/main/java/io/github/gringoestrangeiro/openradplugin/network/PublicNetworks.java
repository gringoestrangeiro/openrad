package io.github.gringoestrangeiro.openradplugin.network;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;

import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;

import java.util.*;

/** Correlated, bounded public network discovery from protocol.rs::public_list/listing. */
public final class PublicNetworks {
    private PublicNetworks() {}

    public record Network(String name, long reportedCount) {}

    public record Page(List<Network> networks, long cursor) {
        public Page {
            networks = List.copyOf(networks);
        }
    }

    public static byte[] request(String query, long id, long cursor) {
        require(id != 0, "listing correlation required");
        return concat(
                op(43),
                tlv(
                        0x1332,
                        concat(
                                u64(0x02000340, id),
                                u64(0x0200030f, cursor),
                                text(0x03000306, query))));
    }

    public static Page parse(byte[] data, long id) {
        require(TlvCodec.operation(data) == 45, "expected public listing");
        var root = fields(field(fields(data), 0x1334));
        require(wide(root, 0x02000340) == id, "listing correlation mismatch");
        List<Network> result = new ArrayList<>();
        for (var r : root)
            if (r.tag() == 0x1333) {
                require(result.size() < 1024, "listing count limit");
                var f = fields(r.value());
                result.add(new Network(display(field(f, 0x03000306)), number(f, 0x01000341)));
            }
        return new Page(result, wide(root, 0x0200030f));
    }
}
