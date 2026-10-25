package io.github.gringoestrangeiro.openradclient;

import static io.github.gringoestrangeiro.openradplugin.protocol.Wire.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.gringoestrangeiro.openradplugin.protocol.TlvCodec;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;

class MinecraftCatalogQueryTest {
    private static byte[] response(long id, long cursor, List<String> names) {
        List<byte[]> fields = new ArrayList<>();
        fields.add(u64(0x02000340, id));
        fields.add(u64(0x0200030f, cursor));
        for (String name : names)
            fields.add(tlv(0x1333, concat(text(0x03000306, name), u32(0x01000341, 12))));
        return concat(u32(TlvCodec.SERVER_OP, 45), tlv(0x1334, concat(fields.toArray(byte[][]::new))));
    }

    private static final class Exchange implements MinecraftCatalogQuery.Exchange {
        final ArrayDeque<byte[]> records = new ArrayDeque<>();
        final AtomicLong clock = new AtomicLong();
        final BiFunction<Long, Long, List<byte[]>> listing;
        int requests, heartbeats;
        long readyAdvance = Duration.ofMillis(1).toNanos();
        Exchange(BiFunction<Long, Long, List<byte[]>> listing) { this.listing = listing; }
        public void send(byte[] record) {
            if (number(fields(record), TlvCodec.CLIENT_OP) == 4) { heartbeats++; return; }
            requests++;
            var body = fields(field(fields(record), 0x1332));
            records.addAll(listing.apply(wide(body, 0x02000340), wide(body, 0x0200030f)));
        }
        public boolean ready(Duration timeout) { clock.addAndGet(readyAdvance); return !records.isEmpty(); }
        public byte[] receive() { return records.removeFirst(); }
    }

    @Test void authenticatedPagesStayCorrelatedAndDispatchMembershipBetweenListingResponses() throws Exception {
        Exchange exchange = new Exchange((id, cursor) -> List.of(u32(TlvCodec.SERVER_OP, 42),
                cursor == 0 ? response(id, 77, List.of("Minecraft [Português 1]", "Minecraft [Português 3]"))
                        : response(id, 0, List.of("Minecraft [English 2]"))));
        AtomicInteger membership = new AtomicInteger();
        var catalog = MinecraftCatalogQuery.fetch(exchange, ignored -> membership.incrementAndGet(), () -> false, exchange.clock::get);
        assertEquals(2, exchange.requests);
        assertEquals(2, membership.get());
        assertEquals(Set.of("ptbr-1", "ptbr-3", "english-2"),
                catalog.stream().map(entry -> entry.selector()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void mismatchedResponseIdAndCursorLoopsAreRejected() {
        Exchange wrongId = new Exchange((id, cursor) -> List.of(response(id + 1, 0, List.of())));
        assertThrows(IOException.class, () -> MinecraftCatalogQuery.fetch(wrongId, ignored -> {}, () -> false, wrongId.clock::get));
        Exchange loop = new Exchange((id, cursor) -> List.of(response(id, 7, List.of())));
        IOException failure = assertThrows(IOException.class,
                () -> MinecraftCatalogQuery.fetch(loop, ignored -> {}, () -> false, loop.clock::get));
        assertTrue(failure.getMessage().contains("cursor loop"));
        assertEquals(2, loop.requests);
    }

    @Test void totalEntriesAndPageCountStayBoundedAcrossIndividuallyValidResponses() {
        List<String> page = java.util.stream.IntStream.range(1, 514).mapToObj(i -> "Minecraft [English " + i + "]").toList();
        Exchange tooMany = new Exchange((id, cursor) -> List.of(response(id, cursor == 0 ? 1 : 0, page)));
        IOException failure = assertThrows(IOException.class,
                () -> MinecraftCatalogQuery.fetch(tooMany, ignored -> {}, () -> false, tooMany.clock::get));
        assertTrue(failure.getMessage().contains("entry limit"));
        Exchange pages = new Exchange((id, cursor) -> List.of(response(id, cursor + 1, List.of())));
        IOException pageFailure = assertThrows(IOException.class,
                () -> MinecraftCatalogQuery.fetch(pages, ignored -> {}, () -> false, pages.clock::get));
        assertTrue(pageFailure.getMessage().contains("page limit"));
        assertEquals(16, pages.requests);
    }

    @Test void unrelatedRecordFloodIsBoundedAndCancellationCreatesNoRequest() {
        Exchange noisy = new Exchange((id, cursor) -> java.util.Collections.nCopies(513, u32(TlvCodec.SERVER_OP, 4)));
        AtomicInteger dispatched = new AtomicInteger();
        IOException failure = assertThrows(IOException.class,
                () -> MinecraftCatalogQuery.fetch(noisy, ignored -> dispatched.incrementAndGet(), () -> false, noisy.clock::get));
        assertTrue(failure.getMessage().contains("record limit"));
        assertEquals(512, dispatched.get());
        Exchange cancelled = new Exchange((id, cursor) -> List.of());
        assertThrows(InterruptedIOException.class,
                () -> MinecraftCatalogQuery.fetch(cancelled, ignored -> {}, () -> true, cancelled.clock::get));
        assertEquals(0, cancelled.requests);
    }

    @Test void stalledCatalogTimesOutWhileControlHeartbeatsContinue() {
        Exchange stalled = new Exchange((id, cursor) -> List.of());
        stalled.readyAdvance = Duration.ofSeconds(1).toNanos();
        assertThrows(SocketTimeoutException.class,
                () -> MinecraftCatalogQuery.fetch(stalled, ignored -> {}, () -> false, stalled.clock::get));
        assertEquals(1, stalled.heartbeats);
        assertEquals(1, stalled.requests);
    }
}
