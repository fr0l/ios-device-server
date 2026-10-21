package com.badoo.automation.deviceserver.util

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Copies bytes between connections to [listenPort] and [targetHost]:[targetPort]. */
class TcpRelay(
    private val listenPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val connectTimeoutMillis: Int = 3_000
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private var serverSocket: ServerSocket? = null

    /** Binds [listenPort] before returning, so a taken port fails here. */
    @Synchronized
    fun start() {
        check(serverSocket == null) { "Relay for port $listenPort is already started" }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(listenPort))
        }
        serverSocket = server
        executor.submit { acceptLoop(server) }
    }

    @Synchronized
    override fun close() {
        serverSocket?.closeQuietly()
        serverSocket = null
        connections.forEach { it.closeQuietly() }
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                return // closed
            }
            try {
                executor.submit { relay(client) }
            } catch (e: RejectedExecutionException) {
                client.closeQuietly()
            }
        }
    }

    private fun relay(client: Socket) {
        val backend = Socket()
        try {
            backend.connect(InetSocketAddress(targetHost, targetPort), connectTimeoutMillis)
        } catch (e: IOException) {
            // target is not listening (yet); the client sees a reset
            logger.debug("Relay $listenPort -> $targetHost:$targetPort: target is unreachable: ${e.message}")
            backend.closeQuietly()
            client.closeQuietly()
            return
        }

        connections += client
        connections += backend
        try {
            val upstream = executor.submit { pump(client, backend) }
            pump(backend, client)
            upstream.get()
        } finally {
            client.closeQuietly()
            backend.closeQuietly()
            connections -= client
            connections -= backend
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
            to.shutdownOutput()
        } catch (e: IOException) {
            from.closeQuietly()
            to.closeQuietly()
        }
    }

    private fun AutoCloseable.closeQuietly() {
        try {
            close()
        } catch (ignored: IOException) {
        }
    }
}
