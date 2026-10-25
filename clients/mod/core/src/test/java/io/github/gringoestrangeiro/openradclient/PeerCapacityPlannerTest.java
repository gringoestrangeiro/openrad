package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.ipv4;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class PeerCapacityPlannerTest {
    @Test void requestedServerDisplacesOldestDiscoveryChannelWhileGameAndPendingRequestsStayPinned() {
        var game = new PeerCapacityPlanner.Candidate(1, ipv4(0x1a000001), 1);
        var pending = new PeerCapacityPlanner.Candidate(2, ipv4(0x1a000002), 2);
        var oldestIdle = new PeerCapacityPlanner.Candidate(3, ipv4(0x1a000003), 3);
        var recentIdle = new PeerCapacityPlanner.Candidate(4, ipv4(0x1a000004), 4);
        assertEquals(3, PeerCapacityPlanner.victim(List.of(game, pending, oldestIdle, recentIdle),
                Set.of(2L, 99L), vip -> vip.equals(game.vip())).orElseThrow());
        assertTrue(PeerCapacityPlanner.victim(List.of(game, pending), Set.of(2L, 99L),
                vip -> vip.equals(game.vip())).isEmpty());
    }
}
