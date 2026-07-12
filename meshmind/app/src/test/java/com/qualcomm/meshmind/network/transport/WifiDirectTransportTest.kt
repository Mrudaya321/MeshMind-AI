package com.qualcomm.meshmind.network.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WifiDirectTransportTest {

    @Test
    fun testLocalTcpSocketDataTransfer() {
        val latch = CountDownLatch(1)
        var receivedData = ""
        val testPort = 9012 // Unique port distinct from normal app execution

        // 1. Start local test ServerSocket
        val serverThread = Thread {
            var server: ServerSocket? = null
            var client: Socket? = null
            try {
                server = ServerSocket(testPort)
                client = server.accept()
                val dis = DataInputStream(client.getInputStream())
                receivedData = dis.readUTF()
                latch.countDown()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    client?.close()
                    server?.close()
                } catch (ignored: Exception) {}
            }
        }
        serverThread.start()

        // Short sleep to allow the socket thread to bind
        Thread.sleep(150)

        // 2. Establish connection and transmit dummy string
        var clientSocket: Socket? = null
        try {
            clientSocket = Socket(java.net.InetAddress.getLoopbackAddress(), testPort)
            val dos = DataOutputStream(clientSocket.getOutputStream())
            dos.writeUTF("Hello Mesh TCP Socket Test")
            dos.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                clientSocket?.close()
            } catch (ignored: Exception) {}
        }

        // Wait for server thread receipt
        latch.await(2, TimeUnit.SECONDS)
        assertEquals("Hello Mesh TCP Socket Test", receivedData)
    }
}
