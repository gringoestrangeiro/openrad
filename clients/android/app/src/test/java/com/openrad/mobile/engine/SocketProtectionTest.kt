package com.openrad.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A refused `VpnService.protect()` must not abort the connection: the tunnel
 * does not exist before the control connection is ONLINE, so failing here
 * deadlocks the whole app (issue #24, Android 16).
 */
class SocketProtectionTest {
    private class RefusingProtector : SocketProtector {
        val failures = mutableListOf<String>()
        override fun protect(socket: Socket) = false
        override fun protect(socket: DatagramSocket) = false
        override fun onProtectFailed(kind: String) { failures += kind }
    }

    @Test fun refusedTcpProtectionStillConnectsAndIsReported() {
        val protector = RefusingProtector()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val stream = Framed.connect("127.0.0.1", server.localPort, 1000, protector, null)
            try { assertTrue(stream.socket.isConnected) } finally { stream.close() }
        }
        assertEquals(listOf("TCP"), protector.failures)
    }

    @Test fun refusedUdpProtectionStillBindsAndIsReported() {
        val protector = RefusingProtector()
        val socket = UdpSocket.bind(protector)
        try {
            assertEquals(listOf("UDP"), protector.failures)
        } finally { socket.close() }
    }

    @Test fun acceptedProtectionIsNotReported() {
        var tcp = 0
        val protector = object : SocketProtector {
            override fun protect(socket: Socket): Boolean { tcp++; return true }
            override fun protect(socket: DatagramSocket) = true
            override fun onProtectFailed(kind: String) { throw AssertionError("protect succeeded") }
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Framed.connect("127.0.0.1", server.localPort, 1000, protector, null).close()
        }
        assertEquals(1, tcp)
    }
}
