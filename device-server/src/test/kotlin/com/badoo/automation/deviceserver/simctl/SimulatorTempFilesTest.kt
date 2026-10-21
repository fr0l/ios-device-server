package com.badoo.automation.deviceserver.simctl

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SimulatorTempFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val gone = "01448E9A-22D1-4D0F-A683-88FC9561E024"
    private val alive = "05C14183-E877-4C4B-AF29-385AF1B1D1B5"
    private val realDevice = "00008110-0000000000000004"

    private lateinit var tmpDir: File
    private lateinit var varTmpDir: File
    private lateinit var files: SimulatorTempFiles

    @Before
    fun setUp() {
        tmpDir = tmp.newFolder("tmp")
        varTmpDir = tmp.newFolder("var-tmp")
        files = SimulatorTempFiles(tmpDir, varTmpDir)
        listOf(gone, alive, realDevice).forEach { udid ->
            File(tmpDir, "derivedData_$udid/Logs").mkdirs()
            File(tmpDir, "xctestRunDir_$udid").mkdirs()
            File(tmpDir, "web_driver_agent_log_$udid.txt").writeText("log")
            File(tmpDir, "iOS_SysLog_$udid.log").writeText("log")
            File(tmpDir, "iOS_SysLog_$udid.err.log").writeText("log")
            File(varTmpDir, "com.apple.CoreSimulator.SimDevice.$udid").mkdirs()
        }
        File(tmpDir, gone).mkdirs()
        File(tmpDir, "com.apple.sharingd").mkdirs()
    }

    @Test
    fun deleteOrphansKeepsExistingSimulatorsAndUnrelatedFiles() {
        val deleted = files.deleteOrphans { setOf(alive) }

        assertEquals(6, deleted.size)
        assertEquals((names(alive) + names(realDevice) + gone + "com.apple.sharingd").sorted(), tmpDir.list()!!.sorted())
        assertEquals(
            listOf(alive, realDevice).map { "com.apple.CoreSimulator.SimDevice.$it" }.sorted(),
            varTmpDir.list()!!.sorted()
        )
    }

    @Test
    fun deleteRemovesOnlyThatSimulator() {
        files.delete(gone)

        assertEquals((names(alive) + names(realDevice) + gone + "com.apple.sharingd").sorted(), tmpDir.list()!!.sorted())
    }

    private fun names(udid: String) = listOf(
        "derivedData_$udid",
        "xctestRunDir_$udid",
        "web_driver_agent_log_$udid.txt",
        "iOS_SysLog_$udid.log",
        "iOS_SysLog_$udid.err.log",
    ).sorted()
}
