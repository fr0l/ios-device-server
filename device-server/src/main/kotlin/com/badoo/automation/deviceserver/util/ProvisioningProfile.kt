package com.badoo.automation.deviceserver.util

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * embedded.mobileprovision
 */
class ProvisioningProfile(file: File) {
    private val config: XMLPropertyListConfiguration

    init {
        // The profile is a CMS envelope around a plain XML plist. `security cms -D` would decode it, but it imports
        // the signing certificates into the default keychain, which is the read-only System keychain under launchd.
        val content = file.readBytes().toString(Charsets.ISO_8859_1)
        val start = content.indexOf("<?xml")
        val end = content.indexOf("</plist>", start)
        check(start >= 0 && end > start) { "Failed to read Provisioning Profile ${file.absolutePath}: no plist found" }
        val plist = content.substring(start, end + "</plist>".length)

        config = XMLPropertyListConfiguration()
        BufferedReader(StringReader(plist)).use { input ->
            config.read(input)
        }
    }

    fun provisionedDevices(): List<String> = config.getList("ProvisionedDevices") as List<String>
}
