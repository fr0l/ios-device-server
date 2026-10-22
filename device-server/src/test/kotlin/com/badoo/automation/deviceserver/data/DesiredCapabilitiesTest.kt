package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Test
import kotlin.test.assertEquals


class DesiredCapabilitiesTest {
    private fun fromJson(json: String): DesiredCapabilities {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesEmptyCapabilities() {
        val json = "{}"
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, false, true), actual)
    }

    @Test
    fun fromJsonParsesModelAndVersionCapabilities() {
        val json = """{"model":"iPhone 6", "os": "iOS 11.0"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, "iPhone 6", "iOS 11.0", false, true), actual)
    }

    @Test
    fun fromJsonParsesUdidCapability() {
        val udid = "CD391B89-64C6-4106-BE37-EC1956956D28"
        val json = """{"udid":"$udid"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(udid, null, null, false, true), actual)
    }

    @Test
    fun fromJsonParsesUseWdaFalseCapability() {
        val json = """{"use_wda": false}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, useWda = false), actual)
    }

    @Test
    fun fromJsonParsesUseWdaBoolAsTextCapability() {
        val json = """{"use_wda": "false"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, null, null, useWda = false), actual)
    }

    @Test
    fun fromJsonParsesDeviceTypeAndIgnoresArchFromOlderClients() {
        val json = """{"model":"iPhone 11", "os":"iOS 26.5", "arch":"x86_64", "headless":false, "device_type":"simulator"}"""
        val actual = fromJson(json)

        assertEquals(DesiredCapabilities(null, "iPhone 11", "iOS 26.5", deviceType = DeviceType.Simulator), actual)
    }

    @Test
    fun requestedDeviceTypeComesFromDeviceTypeThenUdidAndDefaultsToSimulator() {
        assertEquals(DeviceType.Device, fromJson("""{"device_type":"device"}""").requestedDeviceType)
        assertEquals(DeviceType.Simulator, fromJson("""{"udid":"00008110-001A2C3E0E8A801E","device_type":"simulator"}""").requestedDeviceType)
        assertEquals(DeviceType.Device, fromJson("""{"udid":"00008110-001A2C3E0E8A801E"}""").requestedDeviceType)
        assertEquals(DeviceType.Device, fromJson("""{"udid":"6009c0645780eb1ac297693cf988cde5924c2f33"}""").requestedDeviceType)
        assertEquals(DeviceType.Simulator, fromJson("""{"udid":"CD391B89-64C6-4106-BE37-EC1956956D28"}""").requestedDeviceType)
        assertEquals(DeviceType.Simulator, fromJson("""{"model":"iPhone 11","os":"iOS 26.5"}""").requestedDeviceType)
    }
}
