package com.badoo.automation.deviceserver.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProvisioningProfileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsProvisionedDevicesFromSignedProfile() {
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Name</key>
                <string>WebDriverAgent</string>
                <key>ProvisionedDevices</key>
                <array>
                    <string>00008110-0000000000000001</string>
                    <string>00008110-0000000000000002</string>
                </array>
            </dict>
            </plist>
        """.trimIndent()
        // A signed profile is CMS: DER bytes around the plist, with certificates after it.
        val signedBytes = byteArrayOf(0x30, 0x80.toByte(), 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48) +
            plist.toByteArray() +
            byteArrayOf(0xa0.toByte(), 0x82.toByte(), 0x0d, 0x3c, 0x30, 0x82.toByte(), 0x00, 0xff.toByte())
        val file = temporaryFolder.newFile("embedded.mobileprovision").apply { writeBytes(signedBytes) }

        val devices = ProvisioningProfile(file).provisionedDevices()

        assertEquals(listOf("00008110-0000000000000001", "00008110-0000000000000002"), devices)
    }
}
