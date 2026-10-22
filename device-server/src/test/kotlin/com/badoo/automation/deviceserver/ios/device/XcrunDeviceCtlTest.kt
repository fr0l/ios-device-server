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
          "deviceProperties": { "name": "QA phone", "osVersionNumber": "26.5" },
          "hardwareProperties": { "udid": "$udid", "platform": "$platform", "productType": "iPhone16,2", "cpuType": { "name": "arm64e" } }
        }
    """

    @Test
    fun listsPairedIosDevicesWithTheirUdidAndSkipsSimulators() {
        val json = jacksonObjectMapper().readTree("""{"result": {"devices": [
            ${device("00008130-000045C91EFA001C", "C483E12C-DD62-5C8E-A19C-F84E822DE622", "iOS", "wired")},
            ${device("00008110-001A2C3E0E8A801E", "11111111-DD62-5C8E-A19C-F84E822DE622", "iOS", null)},
            ${device("9C3BE808-9E12-4EBF-ACE9-9C4E4672A8B3", "9C3BE808-9E12-4EBF-ACE9-9C4E4672A8B3", "iOS", "sameMachine")},
            ${device("00008301-0000000000000000", "22222222-DD62-5C8E-A19C-F84E822DE622", "watchOS", "localNetwork")}
        ]}}""")

        val devices = deviceCtl.parseDeviceList(json)

        assertEquals(listOf("00008130-000045C91EFA001C", "00008110-001A2C3E0E8A801E"), devices.map { it.udid })
        assertEquals(listOf(true, false), devices.map { it.isConnected })
        assertEquals("iPhone16,2", devices.first().model)
        assertEquals("26.5", devices.first().osVersion)
    }
}
