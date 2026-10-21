package com.badoo.automation.deviceserver.data

import kotlin.reflect.full.memberProperties

data class DeviceAllocatedPorts(
    val wdaPort: Int,
    val calabashPort: Int,
    val mjpegServerPort: Int,
) {
    fun toList(): List<Int> {
        return listOf(wdaPort, calabashPort, mjpegServerPort)
    }

    companion object {
        fun portsCount(): Int {
            return DeviceAllocatedPorts::class.memberProperties.size
        }

        fun fromList(ports: List<Int>): DeviceAllocatedPorts {
            if (ports.size != portsCount()) {
                throw IllegalArgumentException("Expected ${portsCount()} ports, but got ${ports.size}")
            }
            return DeviceAllocatedPorts(
                wdaPort = ports[0],
                calabashPort = ports[1],
                mjpegServerPort = ports[2]
            )
        }
    }
}

/** Client-facing [public] ports are relayed to host-local [private] ones. */
data class SimulatorPorts(
    val public: DeviceAllocatedPorts,
    val private: DeviceAllocatedPorts
)
