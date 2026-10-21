package com.badoo.automation.deviceserver.simctl

import com.badoo.automation.deviceserver.data.UDID
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.END_DOCUMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT

/**
 * Per-simulator launchd disable overrides read by `launchd_sim` on start; replaces `simctl boot --disabledJob=`,
 * which stopped working in Xcode 26. Must be written while the simulator is shut down.
 */
class LaunchdDisabledStore(private val root: Path = Path.of("/private/var/tmp")) {
    fun disable(udid: UDID, labels: Collection<String>) {
        if (labels.isEmpty()) return

        val dir = deviceDir(udid)
        val file = dir.resolve(FILE_NAME)
        // Entries launchd_sim wrote itself (including explicit enables) must survive the merge.
        val entries = sortedMapOf<String, Boolean>()
        if (Files.exists(file)) {
            Files.newInputStream(file).use { entries.putAll(parse(it)) }
        }
        if (labels.all { entries[it] == true }) return

        labels.forEach { entries[it] = true }
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "$FILE_NAME.", null)
        try {
            Files.writeString(temp, encode(entries))
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"))
            Files.move(temp, file, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun deviceDir(udid: UDID): Path = root.resolve("com.apple.CoreSimulator.SimDevice.$udid")

    private fun parse(input: InputStream): Map<String, Boolean> {
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val reader = factory.createXMLStreamReader(input)
        val entries = mutableMapOf<String, Boolean>()
        var key: String? = null
        while (reader.hasNext()) {
            if (reader.next() == END_DOCUMENT) break
            if (reader.eventType != START_ELEMENT) continue
            when (reader.localName) {
                "key" -> key = reader.elementText
                "true", "false" -> key?.let { entries[it] = reader.localName == "true"; key = null }
            }
        }
        return entries
    }

    private fun encode(entries: Map<String, Boolean>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        entries.forEach { (label, disabled) ->
            appendLine("\t<key>${label.escapeXml()}</key>")
            appendLine("\t<$disabled/>")
        }
        appendLine("</dict>")
        appendLine("</plist>")
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        private const val FILE_NAME = "disabled.plist"
    }
}
