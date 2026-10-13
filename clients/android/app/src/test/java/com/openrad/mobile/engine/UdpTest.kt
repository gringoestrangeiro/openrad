package com.openrad.mobile.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct UDP transport over loopback only: ENET wire bytes, ACK/retry, window,
 * reordering and reassembly bounds (ports of the `udp.rs` unit tests), the
 * punch/CONNECT rendezvous, UES mapping and the UDP protocol messages.
 */
class UdpTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val closeables = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> keep(value: T): T = value.also { closeables.add(it) }

    private fun keep(value: Enet): Enet = value.also { closeables.add(AutoCloseable { it.close() }) }

    /** A connected channel and the raw remote socket, without the rendezvous. */
    private fun pair(): Pair<Enet, DatagramSocket> {
        val socket = keep(UdpSocket.bind(null))
        val remote = keep(DatagramSocket(0, loopback))
        remote.connect(InetSocketAddress(loopback, socket.localPort))
        remote.soTimeout = 1000
        val endpoint = InetSocketAddress(loopback, remote.localPort)
        socket.connect(endpoint)
        val e = Enet(socket, endpoint, null, null)
        e.connected = true
        e.remoteId = 0
        return e to remote
    }

    private fun packet(command: ByteArray): ByteArray {
        val b = byteArrayOf(0, 0, 0, 0, 0x80.toByte(), 0, 0, 42) + command
        val crc = beU32(Protocol.rendezvousChecksum(b))
        System.arraycopy(crc, 0, b, 0, 4)
        return b
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ack(sequence: Int, channel: Int = 0) = bytes(1, channel, 0, 0, sequence ushr 8, sequence and 255, 0, 42)

    private fun reliable(sequence: Int, data: ByteArray) =
        bytes(0x86, 0, sequence ushr 8, sequence and 255, data.size ushr 8, data.size and 255) + data

    private fun fragmentMessage(start: Int, count: Int, index: Int, total: Int, offset: Int, data: ByteArray): ByteArray {
        val sequence = (start + index) and 0xffff
        return bytes(0x88, 0, sequence ushr 8, sequence and 255, start ushr 8, start and 255, data.size ushr 8, data.size and 255) +
            beU32(count.toLong()) + beU32(index.toLong()) + beU32(total.toLong()) + beU32(offset.toLong()) + data
    }

    private fun fragmentOf(start: Int, index: Int, data: ByteArray) = fragmentMessage(start, 2, index, 4, index * 2, data)

    private fun receivedCommand(remote: DatagramSocket): ByteArray {
        val wire = DatagramPacket(ByteArray(4096), 4096)
        remote.receive(wire)
        assertEquals(0x8000, readBeU16(wire.data, 4))
        assertEquals(readBeU32(wire.data, 0), Crc.enet(wire.data, wire.length).toLong() and 0xffffffffL)
        return wire.data.copyOfRange(8, wire.length)
    }

    private fun silent(remote: DatagramSocket) {
        remote.soTimeout = 100
        assertThrows(SocketTimeoutException::class.java) { remote.receive(DatagramPacket(ByteArray(4096), 4096)) }
        remote.soTimeout = 1000
    }

    /** Tests move command timestamps backwards to avoid real retry waits. */
    private fun refreshRetryDeadline(e: Enet) {
        e.nextRetry = e.pending.values.minOfOrNull { it.last + Udp.RETRY_INTERVAL_MS }
    }

    private fun message(e: Enet): ByteArray? = e.ready.removeFirstOrNull()

    @Test
    fun tableChecksumMatchesTheBitwiseRendezvousChecksumAndZeroesTheEnetPrefix() {
        for (len in listOf(0, 1, 4, 8, 15, 16, 64, 1400, 4096)) {
            val data = ByteArray(len) { (it * 37 + 11).toByte() }
            val reference = Protocol.rendezvousChecksum(data)
            for (split in listOf(0, len / 3, len)) {
                val crc = Crc.slices(data.copyOfRange(0, split), data.copyOfRange(split, len))
                assertEquals(reference, crc.toLong() and 0xffffffffL)
            }
            if (len >= 4) {
                val zeroed = data.copyOf().also { it.fill(0, 0, 4) }
                assertEquals(Protocol.rendezvousChecksum(zeroed), Crc.enet(data).toLong() and 0xffffffffL)
            }
        }
        // Shorter than the checksum field: hashes as the zeroed field alone, without throwing.
        assertEquals(Protocol.rendezvousChecksum(ByteArray(4)), Crc.enet(bytes(1, 2, 3)).toLong() and 0xffffffffL)
    }

    @Test
    fun sendAndAckPreserveWireBytesAndFragmentSequenceWraparound() {
        val (e, remote) = pair()
        e.send("abc".toByteArray())
        assertArrayEquals(bytes(0x86, 0, 0, 1, 0, 3) + "abc".toByteArray(), receivedCommand(remote))
        e.ingest(packet(bytes(0x86, 0, 0, 1, 0, 1, 'x'.code)))
        assertArrayEquals(bytes(1, 0, 0, 0, 0, 1, 0, 42), receivedCommand(remote))
        e.ingest(packet(ack(1)))

        e.mtu = 576
        e.outgoing = 0xffff - 1
        val data = ByteArray(600) { (it % 251).toByte() }
        e.send(data)
        val first = receivedCommand(remote)
        assertArrayEquals(
            bytes(0x88, 0, 255, 255, 255, 255, 2, 32, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 2, 88, 0, 0, 0, 0),
            first.copyOfRange(0, 24),
        )
        assertArrayEquals(data.copyOfRange(0, 544), first.copyOfRange(24, first.size))
        val second = receivedCommand(remote)
        assertArrayEquals(
            bytes(0x88, 0, 0, 0, 255, 255, 0, 56, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 2, 88, 0, 0, 2, 32),
            second.copyOfRange(0, 24),
        )
        assertArrayEquals(data.copyOfRange(544, 600), second.copyOfRange(24, second.size))
    }

    @Test
    fun retriesPreserveCommandBytesKeyOrderAndRetryDeadlines() {
        val (e, remote) = pair()
        e.outgoing = 0xffff - 1
        e.send("old".toByteArray())
        e.send("new".toByteArray())
        val old = receivedCommand(remote)
        val new = receivedCommand(remote)
        // Only the first command is due; fresh entries stay untouched.
        e.pending.getValue(Enet.key(0, 0xffff)).last = now() - 1000
        refreshRetryDeadline(e)
        e.pump()
        assertArrayEquals(old, receivedCommand(remote))
        assertEquals(1, e.pending.getValue(Enet.key(0, 0)).tries)
        assertEquals(2, e.pending.getValue(Enet.key(0, 0xffff)).tries)
        for (p in e.pending.values) p.last = now() - 1000
        refreshRetryDeadline(e)
        e.pump()
        // Key order follows the Rust (channel, sequence) tree: 0 before 0xffff.
        assertArrayEquals(new, receivedCommand(remote))
        assertArrayEquals(old, receivedCommand(remote))
        e.pump()
        silent(remote)
    }

    @Test
    fun readinessWaitKeepsRetransmissionDeadlinesWithoutIncomingData() {
        val (e, remote) = pair()
        e.send("needs retry while waiting".toByteArray())
        val command = receivedCommand(remote)
        val started = now()
        assertNull(e.poll(65536, 550))
        assertTrue(now() - started < 2000)
        assertArrayEquals(command, receivedCommand(remote))
        assertEquals(2, e.pending.getValue(Enet.key(0, 1)).tries)
    }

    @Test
    fun waitingConsumesAckBeforeFinalRetryAndWakesOnPayload() {
        val (e, remote) = pair()
        e.send("final attempt".toByteArray())
        receivedCommand(remote)
        e.pending.getValue(Enet.key(0, 1)).apply {
            tries = Udp.MAX_TRIES
            last = now() - 250
        }
        refreshRetryDeadline(e)
        val worker = thread {
            Thread.sleep(20)
            remote.send(DatagramPacket(packet(ack(1)), 16))
            val data = packet(reliable(1, "immediate incoming data".toByteArray()))
            remote.send(DatagramPacket(data, data.size))
        }
        assertArrayEquals("immediate incoming data".toByteArray(), e.poll(128, 1000))
        assertTrue(e.pending.isEmpty())
        worker.join()
    }

    @Test
    fun idleWaitIsCancelledByTheStopFlag() {
        val (e, _) = pair()
        val stop = AtomicBoolean(false)
        e.stop = stop
        val result = CompletableFuture<Throwable?>()
        thread { result.complete(runCatching { e.poll(65536, 15_000) }.exceptionOrNull()) }
        Thread.sleep(20)
        val started = now()
        stop.set(true)
        assertTrue(result.get(2, TimeUnit.SECONDS) is CancelledException)
        assertTrue(now() - started < 1000)
    }

    @Test
    fun aFullSendBufferKeepsCommandsPendingAndRetriesThemSoon() {
        val (e, remote) = pair()
        e.refuseSends = true
        e.send("queued while the kernel buffer is full".toByteArray())
        assertEquals(1, e.pending.size)
        assertEquals(0, e.pending.getValue(Enet.key(0, 1)).tries)
        // Due again after the short refused interval, not 400 ms.
        assertTrue(e.nextRetry!! <= now() + Udp.REFUSED_RETRY_MS)
        silent(remote)
        // A retransmission that is refused again is not a failed attempt.
        Thread.sleep(Udp.REFUSED_RETRY_MS + 5)
        e.pump()
        assertEquals(0, e.pending.getValue(Enet.key(0, 1)).tries)
        e.refuseSends = false
        Thread.sleep(Udp.REFUSED_RETRY_MS + 5)
        e.pump()
        val command = receivedCommand(remote)
        assertArrayEquals(bytes(0x86, 0, 0, 1), command.copyOfRange(0, 4))
        assertArrayEquals("queued while the kernel buffer is full".toByteArray(), command.copyOfRange(6, command.size))
        assertEquals(1, e.pending.getValue(Enet.key(0, 1)).tries)
        e.ingest(packet(ack(1)))
        assertTrue(e.pending.isEmpty())
    }

    @Test
    fun aCongestedSendBufferTimesOutInsteadOfRetryingForever() {
        val (e, _) = pair()
        e.refuseSends = true
        e.send("congested".toByteArray())
        // Age past the congestion bound while the retry is due and liveness stays fresh.
        val aged = Enet.Pending(e.pending.getValue(Enet.key(0, 1)).command, now() - Udp.RETRY_INTERVAL_MS, 0, now() - 36_000)
        e.pending[Enet.key(0, 1)] = aged
        e.lastReceive = now()
        refreshRetryDeadline(e)
        val error = runCatching { e.pump() }.exceptionOrNull()
        assertTrue("unexpected: $error", error?.message?.contains("congestion") == true)
    }

    @Test
    fun aRefusedAcknowledgementDoesNotDropTheDeliveredMessage() {
        val (e, _) = pair()
        e.refuseSends = true
        e.ingest(packet(reliable(1, "payload".toByteArray())))
        e.refuseSends = false
        assertArrayEquals("payload".toByteArray(), message(e))
    }

    @Test
    fun reliableReordersDeduplicatesAndReassemblesWithoutReplayingData() {
        val (e, _) = pair()
        e.ingest(packet(reliable(2, "second".toByteArray())))
        assertTrue(e.ready.isEmpty())
        e.ingest(packet(reliable(1, "first".toByteArray())))
        e.ingest(packet(reliable(1, "first".toByteArray())))
        assertArrayEquals("first".toByteArray(), message(e))
        assertArrayEquals("second".toByteArray(), message(e))
        assertTrue(e.ready.isEmpty())
        val fragment = { sequence: Int, index: Int, offset: Int, data: String ->
            bytes(0x88, 0, 0, sequence, 0, 3, 0, data.length) + beU32(2) + beU32(index.toLong()) + beU32(4) + beU32(offset.toLong()) +
                data.toByteArray()
        }
        e.ingest(packet(fragment(4, 1, 2, "cd")))
        assertTrue(e.ready.isEmpty())
        e.ingest(packet(fragment(3, 0, 0, "ab")))
        assertArrayEquals("abcd".toByteArray(), message(e))
        e.ingest(packet(fragment(4, 1, 2, "cd")))
        assertTrue(e.ready.isEmpty())
    }

    @Test
    fun checksumAndAckSequenceAreRequiredBeforeReleasingAPendingSend() {
        val (e, _) = pair()
        e.send("retained until acknowledged".toByteArray())
        val corrupt = packet(ack(1)).also { it[0] = (it[0].toInt() xor 1).toByte() }
        e.ingest(corrupt)
        assertEquals(1, e.pending.size)
        e.ingest(packet(ack(2)))
        assertEquals(1, e.pending.size)
        e.ingest(packet(ack(1)))
        assertTrue(e.pending.isEmpty())
    }

    @Test
    fun queuedAckPreventsFalseLivenessAndRetryTimeoutsAfterAStall() {
        val (e, remote) = pair()
        e.sustain()
        e.send("already acknowledged on the wire".toByteArray())
        e.lastReceive = now() - 36_000
        for (p in e.pending.values) {
            p.tries = Udp.MAX_TRIES
            p.last = now() - 1000
        }
        refreshRetryDeadline(e)
        remote.send(DatagramPacket(packet(ack(1)), 16))
        Thread.sleep(20)
        e.pump()
        assertTrue(e.pending.isEmpty())
        assertTrue(now() - e.lastReceive < 1000)
    }

    @Test
    fun missingAckAndRealSilenceStillExpire() {
        val (e, _) = pair()
        e.sustain()
        e.lastReceive = now() - 36_000
        assertTrue(runCatching { e.pump() }.exceptionOrNull()!!.message!!.contains("liveness timeout"))
        e.lastReceive = now()
        e.send("unacknowledged".toByteArray())
        for (p in e.pending.values) {
            p.tries = Udp.MAX_TRIES
            p.last = now() - 1000
        }
        refreshRetryDeadline(e)
        assertTrue(runCatching { e.pump() }.exceptionOrNull()!!.message!!.contains("acknowledgement timeout"))
    }

    @Test
    fun malformedFragmentsAndSequenceJumpsAreBounded() {
        val (e, _) = pair()
        e.ingest(packet(reliable(600, "outside window".toByteArray())))
        assertTrue(e.ordered.isEmpty() && e.ready.isEmpty())
        e.incoming = 2
        val inconsistent = { sequence: Int, index: Int, offset: Int, data: String ->
            bytes(0x88, 0, 0, sequence, 0, 3, 0, data.length) + beU32(2) + beU32(index.toLong()) + beU32(4) + beU32(offset.toLong()) +
                data.toByteArray()
        }
        e.ingest(packet(inconsistent(3, 0, 0, "abc")))
        assertThrows(ProtocolException::class.java) { e.ingest(packet(inconsistent(4, 1, 2, "cd"))) }
        assertThrows(ProtocolException::class.java) { e.ingest(packet(bytes(0x86, 0, 0, 5, 255, 255))) }
        assertFalse(Udp.validPunch(Udp.punch(1, 2, 3, 'P'), 7, 2))
        assertFalse(Udp.validPunch(ByteArray(7), 7, 1))
        assertTrue(Udp.validPunch(Udp.punch(1, 2, 3, 'C'), 7, 1))
    }

    @Test
    fun aFullSendWindowRefusesRoomInsteadOfFailing() {
        val (e, _) = pair()
        val message = ByteArray(1440) { 7 }
        var sent = 0
        while (e.hasRoom(message.size)) {
            e.send(message)
            sent++
        }
        assertEquals(128, sent)
        // Acknowledging everything but the oldest command does not reopen the window.
        for (sequence in 2..256) e.ingest(packet(ack(sequence)))
        assertFalse(e.hasRoom(message.size))
        e.ingest(packet(ack(1)))
        assertTrue(e.hasRoom(message.size))
    }

    @Test
    fun aBurstAfterALostCommandIsBufferedOrLeftUnacknowledged() {
        val (e, _) = pair()
        for (sequence in 2..400) e.ingest(packet(reliable(sequence, bytes(sequence ushr 8, sequence and 255))))
        assertTrue(e.ready.isEmpty())
        e.ingest(packet(reliable(1, "lost".toByteArray())))
        assertEquals(257, e.ready.size)
        assertArrayEquals("lost".toByteArray(), message(e))
    }

    @Test
    fun retransmittedFragmentsOfBufferedMessagesDoNotAccumulate() {
        val (e, _) = pair()
        for (round in 0 until 20) {
            val base = round * 3
            e.ingest(packet(fragmentOf(base + 2, 0, "ab".toByteArray())))
            e.ingest(packet(fragmentOf(base + 2, 1, "cd".toByteArray())))
            e.ingest(packet(fragmentOf(base + 2, 1, "cd".toByteArray())))
            assertTrue(e.fragments.isEmpty())
            e.ingest(packet(reliable(base + 1, "gap".toByteArray())))
            assertArrayEquals("gap".toByteArray(), message(e))
            assertArrayEquals("abcd".toByteArray(), message(e))
        }
    }

    @Test
    fun completedFragmentsSurviveAFullReorderBuffer() {
        val (e, _) = pair()
        e.ingest(packet(fragmentOf(300, 0, "ab".toByteArray())))
        for (sequence in 2..257) e.ingest(packet(reliable(sequence, "queued".toByteArray())))
        assertEquals(256, e.ordered.size)
        e.ingest(packet(fragmentOf(300, 1, "cd".toByteArray())))
        // Close the gap and drain before the sender retries only the final fragment.
        e.ingest(packet(reliable(1, "gap".toByteArray())))
        e.ready.clear()
        for (sequence in 258 until 300) e.ingest(packet(reliable(sequence, "queued".toByteArray())))
        e.ready.clear()
        e.ingest(packet(fragmentOf(300, 1, "cd".toByteArray())))
        assertArrayEquals("abcd".toByteArray(), message(e))
        assertEquals(301, e.incoming)
        assertTrue(e.fragments.isEmpty())
    }

    @Test
    fun refusedCommandsAreNotAcknowledgedAndCanBeRetried() {
        val (e, remote) = pair()
        e.ingest(packet(reliable(Udp.RECEIVE_WINDOW + 1, "later".toByteArray())))
        silent(remote)
        e.incoming = Udp.RECEIVE_WINDOW
        e.ingest(packet(reliable(Udp.RECEIVE_WINDOW + 1, "later".toByteArray())))
        val ack = receivedCommand(remote)
        assertEquals(1, ack[0].toInt())
        assertEquals(Udp.RECEIVE_WINDOW + 1, readBeU16(ack, 4))
        assertArrayEquals("later".toByteArray(), message(e))
    }

    @Test
    fun sendWindowTracksALostCommandAcrossSequenceWraparound() {
        val (e, _) = pair()
        e.outgoing = 0xffff - 1
        e.send("lost".toByteArray())
        e.send("received".toByteArray())
        e.ingest(packet(ack(0)))
        assertEquals(2, e.inFlight())
        assertTrue(e.hasRoom((Udp.SEND_WINDOW - 2) * (e.mtu - 32)))
        assertFalse(e.hasRoom((Udp.SEND_WINDOW - 1) * (e.mtu - 32)))
        e.ingest(packet(ack(0xffff)))
        assertEquals(0, e.inFlight())
    }

    @Test
    fun cachedSendWindowSkipsAcknowledgedHolesAndIgnoresControlCommands() {
        val (e, _) = pair()
        e.outgoing = 0xffff - 2
        for (data in listOf("a", "b", "c", "d")) e.send(data.toByteArray())
        e.queue(bytes(0x85, 255, 0, 1))
        assertEquals(0xffff - 1, e.oldestPending)
        assertEquals(4, e.inFlight())
        for (sequence in listOf(0xffff, 0)) {
            e.ingest(packet(ack(sequence)))
            assertEquals(4, e.inFlight())
        }
        e.ingest(packet(ack(0xfffe)))
        assertEquals(1, e.oldestPending)
        assertEquals(1, e.inFlight())
        e.ingest(packet(ack(99)))
        assertEquals(1, e.oldestPending)
        e.ingest(packet(ack(1)))
        assertNull(e.oldestPending)
        assertEquals(0, e.inFlight())
        assertEquals(1, e.pending.size)
        assertTrue(e.nextRetry != null)
        e.ingest(packet(ack(1, channel = 255)))
        assertTrue(e.pending.isEmpty())
        assertNull(e.nextRetry)
    }

    @Test
    fun reassemblyBitmapHandles128FragmentsAcrossSequenceWraparound() {
        val (e, _) = pair()
        e.incoming = 0xffff - 1
        val start = 0xffff
        e.ingest(packet(fragmentMessage(start, 128, 127, 128, 127, bytes(127))))
        for (index in 126 downTo 0) e.ingest(packet(fragmentMessage(start, 128, index, 128, index, bytes(index))))
        assertTrue(e.fragments.isEmpty())
        assertEquals((start + 127) and 0xffff, e.incoming)
        assertArrayEquals(ByteArray(128) { it.toByte() }, message(e))
    }

    @Test
    fun reassemblyRejectsGapsOverlapsAndIncompleteTotalsBeforeDelivery() {
        for ((total, offset, expected) in listOf(Triple(5, 3, "overlapping/gapped"), Triple(4, 1, "overlapping/gapped"), Triple(5, 2, "total mismatch"))) {
            val (e, _) = pair()
            e.ingest(packet(fragmentMessage(1, 2, 0, total, 0, "ab".toByteArray())))
            val error = runCatching { e.ingest(packet(fragmentMessage(1, 2, 1, total, offset, "cd".toByteArray()))) }.exceptionOrNull()
            assertTrue("$error", error?.message?.contains(expected) == true)
            assertTrue(e.ready.isEmpty() && e.ordered.isEmpty())
        }
    }

    // ------------------------------------------------------------------ rendezvous

    private fun rendezvous(nonce: Int = 123): Pair<Enet, Enet> {
        val a = UdpSocket.bind(null)
        val b = UdpSocket.bind(null)
        val aAddress = InetSocketAddress(loopback, a.localPort)
        val bAddress = InetSocketAddress(loopback, b.localPort)
        val incoming = CompletableFuture<Enet>()
        thread { runCatching { Enet.accept(b, listOf(aAddress), nonce, 5000, null) }.fold(incoming::complete, incoming::completeExceptionally) }
        val outgoing = keep(Enet.connect(a, listOf(bAddress), nonce, 5000, null))
        val receiver = keep(incoming.get(5, TimeUnit.SECONDS))
        outgoing.sustain()
        receiver.sustain()
        return outgoing to receiver
    }

    @Test
    fun loopbackRendezvousCarriesSmallAndFragmentedMessagesBothWays() {
        val (outgoing, incoming) = rendezvous()
        val large = ByteArray(60_000) { (it % 253).toByte() }
        val reader = CompletableFuture<List<ByteArray>>()
        thread { reader.complete(List(3) { incoming.receive(65536, now() + 5000) }) }
        outgoing.send("hello".toByteArray())
        outgoing.send(large)
        outgoing.send("after".toByteArray())
        // Keep ACK/retry processing alive while the receiver drains.
        val until = now() + 5000
        while (!reader.isDone && now() < until) outgoing.poll(65536, 20)
        val received = reader.get(1, TimeUnit.SECONDS)
        assertArrayEquals("hello".toByteArray(), received[0])
        assertArrayEquals(large, received[1])
        assertArrayEquals("after".toByteArray(), received[2])
        incoming.send("reply".toByteArray())
        assertArrayEquals("reply".toByteArray(), outgoing.receive(65536, now() + 3000))
    }

    @Test
    fun closingAnEstablishedChannelTellsThePeerAtOnce() {
        val (outgoing, incoming) = rendezvous()
        outgoing.close()
        val started = now()
        val error = runCatching { incoming.receive(65536, now() + 5000) }.exceptionOrNull()
        assertEquals("UDP peer disconnected", error?.message)
        assertTrue(now() - started < 1000)
    }

    @Test
    fun rendezvousIgnoresWrongNoncesAndUnlistedSourcesUntilItsDeadline() {
        val a = UdpSocket.bind(null)
        val b = UdpSocket.bind(null)
        val bAddress = InetSocketAddress(loopback, b.localPort)
        val incoming = CompletableFuture<Throwable?>()
        // The incoming side only trusts an unrelated candidate.
        thread { incoming.complete(runCatching { Enet.accept(b, listOf(InetSocketAddress(loopback, 9)), 7, 700, null) }.exceptionOrNull()) }
        val outgoing = runCatching { Enet.connect(a, listOf(bAddress), 8, 700, null) }.exceptionOrNull()
        assertTrue("$outgoing", outgoing?.message?.startsWith("UDP rendezvous timeout") == true)
        assertEquals("incoming UDP rendezvous timeout", incoming.get(3, TimeUnit.SECONDS)?.message)
    }

    @Test
    fun peerChannelsAuthenticateOverUdpAndDropBeforeAdvancingTheCipherChain() {
        val (outgoing, incoming) = rendezvous()
        val password = "udp-connection-password".toByteArray()
        val listener = CompletableFuture<PeerChannel>()
        thread {
            runCatching { PeerChannel(incoming, 200, Ip.parse("26.0.0.3"), 100, password, true, null, TransportPath.DIRECT_UDP, 5000) }
                .fold(listener::complete, listener::completeExceptionally)
        }
        val dialer = PeerChannel(outgoing, 100, Ip.parse("26.0.0.2"), 200, password, false, null, TransportPath.DIRECT_UDP, 5000)
        val receiver = listener.get(5, TimeUnit.SECONDS)
        assertEquals(TransportPath.DIRECT_UDP, dialer.transport)
        assertArrayEquals(Protocol.macForIp(Ip.parse("26.0.0.3")), dialer.mac)

        // Deliver every record but leave ACKs unread on the sender, filling its window.
        var accepted = 0
        while (dialer.send(byteArrayOf(accepted.toByte()))) {
            assertArrayEquals(byteArrayOf(accepted.toByte()), receiver.receive(now() + 3000))
            accepted++
            assertTrue(accepted <= Udp.SEND_WINDOW)
        }
        assertTrue("window admitted $accepted", accepted >= Udp.SEND_WINDOW - 2)
        assertFalse(dialer.send("dropped".toByteArray()))
        outgoing.poll(65536, 0)
        assertTrue(dialer.send("resumed".toByteArray()))
        // Decryption succeeds only if the dropped record never advanced the CBC chain.
        assertArrayEquals("resumed".toByteArray(), receiver.receive(now() + 5000))
        dialer.close()
        receiver.close()
    }

    // ------------------------------------------------------------------ UES mapping

    private fun mappingResponse(transaction: ByteArray, port: Int) =
        bytes(1, 1, 0, 12) + transaction + bytes(0, 1, 0, 8, 0, 1, port ushr 8, port and 255, 192, 0, 2, 10)

    private fun server() = keep(DatagramSocket(0, loopback)).apply { soTimeout = 1000 }

    @Test
    fun mappingQueriesBothServersBeforeWaitingAndCorrelatesReorderedReplies() {
        val a = server()
        val b = server()
        val servers = listOf(InetSocketAddress(loopback, a.localPort), InetSocketAddress(loopback, b.localPort))
        val result = CompletableFuture<InetSocketAddress>()
        thread {
            runCatching { Udp.discoverMappingAt(keep(UdpSocket.bind(null)), servers, null, 3000) }
                .fold(result::complete, result::completeExceptionally)
        }
        val requestA = DatagramPacket(ByteArray(20), 20).also { a.receive(it) }
        // A sequential implementation would wait for A's reply here.
        val requestB = DatagramPacket(ByteArray(20), 20).also { b.receive(it) }
        assertEquals(requestA.socketAddress, requestB.socketAddress)
        val wrong = requestA.data.copyOfRange(4, 20).also { it[0] = (it[0].toInt() xor 1).toByte() }
        a.send(DatagramPacket(mappingResponse(wrong, 1234), 32, requestA.socketAddress))
        b.send(DatagramPacket(mappingResponse(requestB.data.copyOfRange(4, 20), 1234), 32, requestB.socketAddress))
        a.send(DatagramPacket(mappingResponse(requestA.data.copyOfRange(4, 20), 1234), 32, requestA.socketAddress))
        assertEquals(Udp.endpoint(Ip.parse("192.0.2.10"), 1234), result.get(3, TimeUnit.SECONDS))
    }

    @Test
    fun mappingRejectsDisagreementAndNeedsBothServers() {
        for (disagree in listOf(true, false)) {
            val a = server()
            val b = server()
            val servers = listOf(InetSocketAddress(loopback, a.localPort), InetSocketAddress(loopback, b.localPort))
            val result = CompletableFuture<Throwable?>()
            thread { result.complete(runCatching { Udp.discoverMappingAt(keep(UdpSocket.bind(null)), servers, null, 300) }.exceptionOrNull()) }
            val request = DatagramPacket(ByteArray(20), 20).also { a.receive(it) }
            val response = mappingResponse(request.data.copyOfRange(4, 20), 1234)
            a.send(DatagramPacket(response, 32, request.socketAddress))
            a.send(DatagramPacket(response, 32, request.socketAddress))
            if (disagree) {
                val other = DatagramPacket(ByteArray(20), 20).also { b.receive(it) }
                b.send(DatagramPacket(mappingResponse(other.data.copyOfRange(4, 20), 1235), 32, other.socketAddress))
            }
            val error = result.get(3, TimeUnit.SECONDS)?.message.orEmpty()
            assertTrue(error, error.contains(if (disagree) "changes by destination" else "timeout"))
        }
    }

    @Test
    fun mappingNeedsTwoDistinctAuthenticatedHosts() {
        val socket = keep(UdpSocket.bind(null))
        val host = Ip.parse("192.0.2.1")
        assertThrows(ProtocolException::class.java) { Udp.discoverMapping(socket, listOf(host, host), null) }
    }

    // ------------------------------------------------------------------ candidates and messages

    @Test
    fun localCandidatesIncludeOnlyRoutableAddressesOnTheSelectedInterface() {
        val route = Ip.parse("192.0.2.5")
        val entries = listOf(
            "selected" to route, "selected" to route, "selected" to Ip.parse("192.0.2.9"), "other" to Ip.parse("192.0.2.6"),
            "selected" to Ip.parse("127.0.0.1"), "selected" to Ip.parse("224.0.0.1"),
        )
        assertEquals(
            listOf(Udp.endpoint(route, 1234), Udp.endpoint(Ip.parse("192.0.2.9"), 1234)),
            Udp.candidatesFromInventory(entries, route, 1234),
        )
        assertThrows(ProtocolException::class.java) { Udp.candidatesFromInventory(entries, Ip.parse("198.51.100.1"), 1234) }
        val many = (1..64).map { "selected" to Ip.parse("192.0.2.$it") }
        assertEquals(32, Udp.candidatesFromInventory(many, route, 1234).size)
        // An explicitly local coordinator keeps its loopback route usable.
        val local = Ip.parse("127.0.0.1")
        assertEquals(listOf(Udp.endpoint(local, 9)), Udp.candidatesFromInventory(listOf("lo" to local), local, 9))
    }

    private val cid = 0x0102030405060708L

    private fun candidateRecord(host: String, port: Long) =
        Tlv.tlv(0x127dL, Tlv.text(0x030001c2L, host) + Tlv.u32(0x010001c3L, port))

    @Test
    fun udpAdvertisementsCarryCandidatesAndTheIncomingNonce() {
        val endpoints = listOf(Udp.endpoint(Ip.parse("192.0.2.5"), 4000), Udp.endpoint(Ip.parse("192.0.2.6"), 4001))
        val advert = Tlv.parse(Protocol.advertiseUdp(cid, endpoints, 77))
        assertEquals(28L, Tlv.unique(advert, Protocol.CLIENT_OP).u32())
        assertEquals(cid, Tlv.unique(advert, 0x020001c1L).u64())
        val body = Tlv.parse(Tlv.unique(advert, 0x127bL).value)
        assertEquals(77L, Tlv.unique(body, 0x0100020aL).u32())
        // The coordinator forwards the body unchanged to the dialer as op 29.
        val forwarded = Tlv.u32(Protocol.SERVER_OP, 29) + Tlv.u64(0x020001c1L, cid) + Tlv.tlv(0x127cL, Tlv.unique(advert, 0x127bL).value)
        assertEquals(endpoints, Protocol.directCandidates(forwarded, cid, 29, 0x127cL, Ip.parse("192.0.2.200")))
        assertEquals(77, Protocol.udpNonce(forwarded))
        val outgoing = Tlv.parse(Tlv.unique(Tlv.parse(Protocol.advertiseUdp(cid, endpoints, 0)), 0x127bL).value)
        assertNull(Tlv.optional(outgoing, 0x0100020aL))
    }

    @Test
    fun directCandidatesExcludeUnsafeOrMalformedEntriesAndCheckCorrelation() {
        val body = concat(
            candidateRecord("192.0.2.5", 4000), candidateRecord("192.0.2.5", 4000), candidateRecord("127.0.0.1", 4001),
            candidateRecord("224.0.0.1", 4002), candidateRecord("0.1.2.3", 4003), candidateRecord("2001:db8::1", 4004),
            candidateRecord("192.0.2.7", 0), candidateRecord("192.0.2.8", 4005),
        )
        val data = Tlv.u32(Protocol.SERVER_OP, 29) + Tlv.u64(0x020001c1L, cid) + Tlv.tlv(0x127cL, body)
        assertEquals(
            listOf(Udp.endpoint(Ip.parse("192.0.2.5"), 4000), Udp.endpoint(Ip.parse("192.0.2.8"), 4005)),
            Protocol.directCandidates(data, cid, 29, 0x127cL, Ip.parse("192.0.2.200")),
        )
        // Loopback only for an explicitly local coordinator route.
        assertTrue(Udp.endpoint(Ip.parse("127.0.0.1"), 4001) in Protocol.directCandidates(data, cid, 29, 0x127cL, Ip.parse("127.0.0.1")))
        assertThrows(ProtocolException::class.java) { Protocol.directCandidates(data, cid + 1, 29, 0x127cL, null) }
        assertThrows(ProtocolException::class.java) { Protocol.directCandidates(data, cid, 6, 0x127cL, null) }
        assertNull(Protocol.udpNonce(data))
        val tooMany = Tlv.u32(Protocol.SERVER_OP, 29) + Tlv.u64(0x020001c1L, cid) +
            Tlv.tlv(0x127cL, concat(*Array(33) { candidateRecord("192.0.2.1", 1000L + it) }))
        assertThrows(ProtocolException::class.java) { Protocol.directCandidates(tooMany, cid, 29, 0x127cL, null) }
    }

    @Test
    fun mappedCandidatesAndAdvertisementsRoundTrip() {
        val endpoint = Udp.endpoint(Ip.parse("198.51.100.4"), 50000)
        val advert = Tlv.parse(Protocol.advertiseMappedUdp(cid, endpoint, 9))
        assertEquals(3L, Tlv.unique(advert, Protocol.CLIENT_OP).u32())
        val fields = Tlv.parse(Tlv.unique(advert, 0x1231L).value)
        assertEquals("198.51.100.4", Tlv.decodeText(Tlv.unique(fields, 0x030001c4L)))
        assertEquals(50000L, Tlv.unique(fields, 0x010001c5L).u32())
        assertEquals(0L, Tlv.unique(fields, 0x0b0001dcL).u32())
        assertEquals(9L, Tlv.unique(fields, 0x0100020aL).u32())
        val forwarded = Tlv.u32(Protocol.SERVER_OP, 7) + Tlv.u64(0x020001c1L, cid) + Tlv.tlv(0x1237L, Tlv.unique(advert, 0x1231L).value)
        assertEquals(endpoint to 9, Protocol.mappedUdpCandidate(forwarded, cid, null))
        assertEquals(endpoint, Protocol.incomingMappedCandidate(forwarded, cid, null))
        val withoutNonce = Tlv.u32(Protocol.SERVER_OP, 7) + Tlv.u64(0x020001c1L, cid) +
            Tlv.tlv(0x1237L, Tlv.unique(Tlv.parse(Protocol.advertiseMappedUdp(cid, endpoint, 0)), 0x1231L).value)
        assertThrows(ProtocolException::class.java) { Protocol.mappedUdpCandidate(withoutNonce, cid, null) }
        assertEquals(endpoint, Protocol.incomingMappedCandidate(withoutNonce, cid, null))
        assertThrows(ProtocolException::class.java) { Protocol.incomingMappedCandidate(withoutNonce, cid + 1, null) }
    }

    @Test
    fun uesHostsAreDeduplicatedAndRejectUnsafeAddresses() {
        fun host(text: String) = Tlv.tlv(0x0e00036cL, text.toByteArray() + 0.toByte())
        fun login(vararg hosts: ByteArray) = Tlv.u32(Protocol.SERVER_OP, 21) + Tlv.tlv(0x1340L, concat(*hosts))
        assertEquals(
            listOf(Ip.parse("198.51.100.1"), Ip.parse("198.51.100.2")),
            Protocol.uesHosts(login(host("198.51.100.1"), host("198.51.100.2"), host("198.51.100.1"))),
        )
        assertEquals(emptyList<Int>(), Protocol.uesHosts(Tlv.u32(Protocol.SERVER_OP, 21)))
        assertThrows(ProtocolException::class.java) { Protocol.uesHosts(login(host("127.0.0.1"))) }
        assertThrows(ProtocolException::class.java) { Protocol.uesHosts(login(Tlv.tlv(0x0e00036cL, "198.51.100.1".toByteArray()))) }
    }
}
