package com.openrad.mobile.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Both channel roles over local sockets: SH, race marker, SYN/ACK and envelopes. */
class PeerChannelTest {
    private val dialerVip = Ip.parse("26.0.0.2")
    private val listenerVip = Ip.parse("26.0.0.3")
    private val password = "connection-password".toByteArray()

    private fun pair(listenerPassword: ByteArray = password): Pair<PeerChannel, PeerChannel> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val incoming = AtomicReference<Result<PeerChannel>>()
        val t = thread {
            incoming.set(
                runCatching {
                    PeerChannel(Framed(server.accept()), 200, listenerVip, 100, listenerPassword, true, null, TransportPath.DIRECT_TCP, 5000)
                },
            )
        }
        try {
            val stream = Framed.connect("127.0.0.1", server.localPort, 3000, null, null)
            val outgoing = PeerChannel(stream, 100, dialerVip, 200, password, false, null, TransportPath.DIRECT_TCP, 5000)
            t.join()
            return outgoing to incoming.get().getOrThrow()
        } finally {
            t.join()
            server.close()
        }
    }

    @Test
    fun outgoingAndIncomingRolesNegotiateMacsAndExchangeFrames() {
        val (dialer, listener) = pair()
        assertArrayEquals(Protocol.macForIp(listenerVip), dialer.mac)
        assertArrayEquals(Protocol.macForIp(dialerVip), listener.mac)
        assertEquals(8, dialer.version)
        val frame = Tunnel.gratuitousArp(dialerVip)
        dialer.send(Tunnel.encode(frame))
        val received = Tunnel.decode(listener.receive(now() + 3000)) as Tunnel.Packet.Frames
        assertArrayEquals(frame, received.frames.single())
        listener.send(Tunnel.keepalive(9, reply = false))
        val k = Tunnel.decode(dialer.poll(3000)!!) as Tunnel.Packet.Keepalive
        assertEquals(9, k.sequence)
        dialer.close()
        listener.close()
    }

    @Test
    fun sharedFramesAndBatchesArriveInOrderBetweenPlainSends() {
        val (dialer, listener) = pair()
        assertTrue(dialer.batches)
        val frames = (0 until 70).map { i -> OutFrame(Tunnel.gratuitousArp(Ip.parse("26.0.0.${i + 10}")) + ByteArray(i * 20), shared = i % 2 == 0) }
        dialer.send(Tunnel.keepalive(1, reply = false))
        assertTrue(dialer.sendFrame(frames[0]))
        dialer.sendFrames(frames.subList(1, 33))
        dialer.send(Tunnel.keepalive(2, reply = false))
        dialer.sendFrames(frames.subList(33, 70))
        val expected = listOf<Any>(1L, frames[0]) + frames.subList(1, 33) + listOf(2L) + frames.subList(33, 70)
        for (want in expected) {
            when (val got = Tunnel.decode(listener.receive(now() + 3000))) {
                is Tunnel.Packet.Keepalive -> assertEquals(want, got.sequence)
                is Tunnel.Packet.Frames -> assertArrayEquals((want as OutFrame).frame, got.frames.single())
                else -> throw AssertionError("unexpected packet")
            }
        }
        dialer.close()
        listener.close()
    }

    @Test
    fun wrongConnectionPasswordFailsBothSides() {
        val e = runCatching { pair(listenerPassword = "another-password".toByteArray()) }.exceptionOrNull()
        assertTrue(e != null)
    }

    @Test
    fun uuidBytesLeMatchesPythonLayout() {
        val uuid = java.util.UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        assertEquals("33221100554477668899aabbccddeeff", PeerChannel.uuidBytesLe(uuid).toHex())
    }

    @Test
    fun synackRejectsRefusalAndMulticastMac() {
        val refused = Tlv.u64(0x02000303, 1) + Tlv.u32(0x01000302, 8) + Tlv.tlv(0x09000304, Protocol.macForIp(dialerVip))
        assertThrows(ProtocolException::class.java) { Tunnel.synack(refused) }
        val multicast = Tlv.u64(0x02000303, 0) + Tlv.u32(0x01000302, 8) + Tlv.tlv(0x09000304, byteArrayOf(1, 0, 0, 0, 0, 1))
        assertThrows(ProtocolException::class.java) { Tunnel.synack(multicast) }
        val old = Tlv.u64(0x02000303, 0) + Tlv.u32(0x01000302, 4) + Tlv.tlv(0x09000304, Protocol.macForIp(dialerVip))
        assertThrows(ProtocolException::class.java) { Tunnel.synack(old) }
    }
}
