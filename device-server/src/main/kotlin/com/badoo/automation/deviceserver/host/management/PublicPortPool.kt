package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DeviceAllocatedPorts
import java.io.IOException
import java.net.ServerSocket

/**
 * Client-facing ports. Only this range is open in the firewall, so ports are recycled round-robin
 * and tracked in memory: every socket here is owned by this process (relays, iproxy).
 */
class PublicPortPool(
    min: Int = PORT_RANGE_START,
    max: Int = PORT_RANGE_END,
    private val isBindable: (Int) -> Boolean = ::canBind
) {
    companion object {
        const val PORT_RANGE_START = 41800
        const val PORT_RANGE_END = 42800

        private fun canBind(port: Int): Boolean = try {
            ServerSocket(port).use { true }
        } catch (e: IOException) {
            false
        }
    }

    private val freePorts = LinkedHashSet<Int>().apply { addAll(min..max) }

    @Synchronized
    fun allocateDAP(): DeviceAllocatedPorts {
        val count = DeviceAllocatedPorts.portsCount()
        val taken = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()

        val candidates = freePorts.toList()
        for (port in candidates) {
            if (taken.size == count) break
            freePorts.remove(port)
            // a foreign process may hold the port; leave it in the pool for later
            if (isBindable(port)) taken += port else skipped += port
        }
        freePorts.addAll(skipped)

        if (taken.size < count) {
            freePorts.addAll(taken)
            throw RuntimeException("No more public ports to allocate")
        }
        return DeviceAllocatedPorts.fromList(taken)
    }

    @Synchronized
    fun deallocateDAP(ports: DeviceAllocatedPorts) {
        freePorts.addAll(ports.toList())
    }
}
