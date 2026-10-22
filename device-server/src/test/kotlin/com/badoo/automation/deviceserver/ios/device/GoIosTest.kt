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
            {"deviceList":[{"ProductName":"iPhone OS","ProductType":"iPhone14,2","ProductVersion":"26.0","Udid":"00008110-0000000000000003"}]}
        """.trimIndent()

        val device = goIos.parseDeviceList(output).single()

        assertEquals("00008110-0000000000000003", device.udid)
        assertEquals("iPhone14,2", device.model)
        assertEquals("26.0", device.osVersion)
    }

    @Test
    fun parsesEmptyDeviceList() {
        assertEquals(emptyList<PhysicalDeviceInfo>(), goIos.parseDeviceList("""{"deviceList":[]}"""))
    }

    @Test
    fun parsesOlderArrayFormat() {
        val output = """[{"DeviceID":"0123456789abcdef0123456789abcdef01234567","Properties":{"DeviceName":"Test iPhone","ProductType":"iPhone12,1","ProductVersion":"17.5","CPUArchitecture":"arm64e"}}]"""

        val device = goIos.parseDeviceList(output).single()

        assertEquals("0123456789abcdef0123456789abcdef01234567", device.udid)
        assertEquals("Test iPhone", device.name)
        assertEquals("arm64e", device.arch)
    }
}
