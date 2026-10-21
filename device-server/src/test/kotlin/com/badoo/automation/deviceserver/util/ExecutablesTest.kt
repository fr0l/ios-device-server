package com.badoo.automation.deviceserver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutablesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tool(dir: File, name: String, executable: Boolean = true): File =
        File(dir, name).apply {
            writeText("#!/bin/sh\n")
            setExecutable(executable)
        }

    @Test
    fun findsFirstExecutableInPath() {
        val first = folder.newFolder("first")
        val second = folder.newFolder("second")
        tool(first, "iproxy-test", executable = false)
        val expected = tool(second, "iproxy-test")

        assertEquals(expected, Executables.find("iproxy-test", "${first.path}:${second.path}"))
    }

    @Test
    fun fallsBackToHomebrewDirsWhenNotInPath() {
        val homebrew = folder.newFolder("homebrew")
        val expected = tool(homebrew, "socat-test")

        assertEquals(expected, Executables.find("socat-test", folder.newFolder("path").path, listOf(homebrew.path)))
    }

    @Test
    fun returnsNullWhenMissing() {
        assertNull(Executables.find("surely-not-installed-tool", "", emptyList()))
    }
}
