package com.badoo.automation.deviceserver.ios.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class GoIosTest {
    private val goIos = GoIos(mock())

    @Test
    fun parsesDeviceListAfterLogLines() {
        val output = """
            {"level":"warning","msg":"go-ios agent is not running","time":"2026-10-22T00:58:04+01:00"}
            {"deviceList":[{"ProductName":"iPhone OS","ProductType":"iPhone14,2","ProductVersion":"26.0","Udid":"00008110-001A2C3E0E8A801E"}]}
        """.trimIndent()

        val device = goIos.parseDeviceList(output).single()

        assertEquals("00008110-001A2C3E0E8A801E", device.udid)
        assertEquals("iPhone14,2", device.model)
        assertEquals("26.0", device.osVersion)
    }

    @Test
    fun parsesEmptyDeviceList() {
        assertEquals(emptyList<PhysicalDeviceInfo>(), goIos.parseDeviceList("""{"deviceList":[]}"""))
    }

    @Test
    fun parsesOlderArrayFormat() {
        val output = """[{"DeviceID":"6009c0645780eb1ac297693cf988cde5924c2f33","Properties":{"DeviceName":"QA iPhone","ProductType":"iPhone12,1","ProductVersion":"17.5","CPUArchitecture":"arm64e"}}]"""

        val device = goIos.parseDeviceList(output).single()

        assertEquals("6009c0645780eb1ac297693cf988cde5924c2f33", device.udid)
        assertEquals("QA iPhone", device.name)
        assertEquals("arm64e", device.arch)
    }
}
