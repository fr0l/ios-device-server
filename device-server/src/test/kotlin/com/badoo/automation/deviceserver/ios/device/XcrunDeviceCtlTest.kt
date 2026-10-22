package com.badoo.automation.deviceserver.ios.device

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class XcrunDeviceCtlTest {
    private val deviceCtl = XcrunDeviceCtl(mock())

    private fun device(udid: String, identifier: String, platform: String, transport: String?) = """
        {
          "identifier": "$identifier",
          "connectionProperties": { ${transport?.let { "\"transportType\": \"$it\", " } ?: ""}"pairingState": "paired" },
          "deviceProperties": { "name": "Test phone", "osVersionNumber": "26.5" },
          "hardwareProperties": { "udid": "$udid", "platform": "$platform", "productType": "iPhone16,2", "cpuType": { "name": "arm64e" } }
        }
    """

    @Test
    fun listsPairedIosDevicesWithTheirUdidAndSkipsSimulators() {
        val json = jacksonObjectMapper().readTree("""{"result": {"devices": [
            ${device("00008130-0000000000000005", "33333333-0000-4000-8000-000000000000", "iOS", "wired")},
            ${device("00008110-0000000000000003", "11111111-0000-4000-8000-000000000000", "iOS", null)},
            ${device("9C3BE808-9E12-4EBF-ACE9-9C4E4672A8B3", "9C3BE808-9E12-4EBF-ACE9-9C4E4672A8B3", "iOS", "sameMachine")},
            ${device("00008301-0000000000000000", "22222222-0000-4000-8000-000000000000", "watchOS", "localNetwork")}
        ]}}""")

        val devices = deviceCtl.parseDeviceList(json)

        assertEquals(listOf("00008130-0000000000000005", "00008110-0000000000000003"), devices.map { it.udid })
        assertEquals(listOf(true, false), devices.map { it.isConnected })
        assertEquals("iPhone16,2", devices.first().model)
        assertEquals("26.5", devices.first().osVersion)
    }
}
