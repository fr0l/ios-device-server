package com.badoo.automation.deviceserver.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class TcpRelayTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = closeables.forEach { it.close() }

    private fun <T : AutoCloseable> T.cleanedUp(): T = also { closeables += it }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun echoServer(): ServerSocket {
        val server = ServerSocket(0).cleanedUp()
        Thread.startVirtualThread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { return@startVirtualThread }
                Thread.startVirtualThread {
                    socket.use { it.getInputStream().copyTo(it.getOutputStream()) }
                }
            }
        }
        return server
    }

    @Test
    fun relaysBytesInBothDirections() {
        val target = echoServer()
        val listenPort = freePort()
        TcpRelay(listenPort, "127.0.0.1", target.localPort).cleanedUp().start()

        Socket("127.0.0.1", listenPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write("ping".toByteArray())
            client.shutdownOutput()

            assertEquals("ping", client.getInputStream().readBytes().decodeToString())
        }
    }

    @Test
    fun closesClientWhenTargetIsNotListening() {
        val listenPort = freePort()
        TcpRelay(listenPort, "127.0.0.1", freePort()).cleanedUp().start()

        Socket("127.0.0.1", listenPort).use { client ->
            client.soTimeout = 5_000

            assertEquals(-1, client.getInputStream().read())
        }
    }

    @Test
    fun closeFreesListenPort() {
        val listenPort = freePort()
        val relay = TcpRelay(listenPort, "127.0.0.1", freePort())
        relay.start()

        relay.close()

        ServerSocket(listenPort).use { assertEquals(listenPort, it.localPort) }
    }

    @Test
    fun closeDropsEstablishedConnections() {
        val target = echoServer()
        val listenPort = freePort()
        val relay = TcpRelay(listenPort, "127.0.0.1", target.localPort)
        relay.start()

        Socket("127.0.0.1", listenPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(1)
            assertEquals(1, client.getInputStream().read())

            relay.close()

            val end = runCatching { client.getInputStream().read() }.getOrDefault(-1)
            assertTrue(end == -1)
        }
    }

    @Test
    fun startFailsWhenPortIsTaken() {
        val taken = ServerSocket(0).cleanedUp()

        val error = runCatching { TcpRelay(taken.localPort, "127.0.0.1", freePort()).start() }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
    }
}
