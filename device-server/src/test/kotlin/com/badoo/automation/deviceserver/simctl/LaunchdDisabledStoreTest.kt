package com.badoo.automation.deviceserver.simctl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LaunchdDisabledStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val udid = "ABC-123"
    private fun file() = tmp.root.toPath().resolve("com.apple.CoreSimulator.SimDevice.$udid/disabled.plist")
    private fun store() = LaunchdDisabledStore(tmp.root.toPath())

    @Test
    fun writesPlistForNewDevice() {
        store().disable(udid, listOf("com.apple.b", "com.apple.a"))

        val text = Files.readString(file())
        assertEquals(listOf("com.apple.a", "com.apple.b"), Regex("<key>(.*)</key>").findAll(text).map { it.groupValues[1] }.toList())
        assertEquals(2, Regex("<true/>").findAll(text).count())
    }

    @Test
    fun preservesExistingEntries() {
        Files.createDirectories(file().parent)
        Files.writeString(
            file(),
            """<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>com.apple.keep</key><false/><key>com.apple.old</key><true/></dict></plist>"""
        )

        store().disable(udid, listOf("com.apple.new"))

        val text = Files.readString(file())
        assertTrue(Regex("<key>com.apple.keep</key>\\s*<false/>").containsMatchIn(text))
        assertEquals(2, Regex("<true/>").findAll(text).count())
        assertEquals(1, Regex("<false/>").findAll(text).count())
    }

    @Test
    fun emptyLabelsWritesNothing() {
        store().disable(udid, emptyList())

        assertFalse(Files.exists(file()))
    }

    @Test
    fun reEnablesNothingAndIsIdempotent() {
        store().disable(udid, listOf("com.apple.a"))
        val first = Files.readString(file())

        store().disable(udid, listOf("com.apple.a"))

        assertEquals(first, Files.readString(file()))
    }
}
