package com.openrad.mobile.engine

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SocketProtectionTest {
    @Test fun rejectedTcpProtectionStopsBeforeConnectingAndClosesTheSocket() {
        var rejected: Socket? = null
        val protector = object : SocketProtector {
            override fun protect(socket: Socket): Boolean { rejected = socket; return false }
            override fun protect(socket: DatagramSocket) = false
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                assertThrows(IOException::class.java) {
                    Framed.connect("127.0.0.1", server.localPort, 1000, protector, null)
                }
                assertTrue(rejected!!.isClosed)
                assertTrue(!rejected!!.isConnected)
            } finally { rejected?.close() }
        }
    }

    @Test fun rejectedUdpProtectionStopsBeforeBindingAndClosesTheSocket() {
        var rejected: DatagramSocket? = null
        val protector = object : SocketProtector {
            override fun protect(socket: Socket) = false
            override fun protect(socket: DatagramSocket): Boolean { rejected = socket; return false }
        }
        try {
            assertThrows(IOException::class.java) { UdpSocket.bind(protector) }
            assertTrue(rejected!!.isClosed)
            assertTrue(!rejected!!.isBound)
        } finally { rejected?.close() }
    }
}
