package io.github.gringoestrangeiro.openradclient;

import java.net.Inet4Address;
import java.util.*;
import java.util.function.Predicate;

/** On-demand destinations can displace discovery-only channels while active TCP tuples stay pinned. */
final class PeerCapacityPlanner {
    record Candidate(long rid, Inet4Address vip, long lastStarted) {}

    static OptionalLong victim(Collection<Candidate> candidates, Set<Long> requested,
                               Predicate<Inet4Address> hasConnections) {
        return candidates.stream().filter(candidate -> !requested.contains(candidate.rid()))
                .filter(candidate -> !hasConnections.test(candidate.vip()))
                .min(Comparator.comparingLong(Candidate::lastStarted)
                        .thenComparing(Candidate::rid, Long::compareUnsigned))
                .map(candidate -> OptionalLong.of(candidate.rid())).orElseGet(OptionalLong::empty);
    }
}
