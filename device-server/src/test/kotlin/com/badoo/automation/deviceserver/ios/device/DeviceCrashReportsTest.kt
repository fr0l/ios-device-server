package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.CrashLog
import com.badoo.automation.deviceserver.host.IRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class DeviceCrashReportsTest {
    private val udid = "00009110-0000000000000006"
    private val remote: IRemote = mock { on { hostName } doReturn "localhost" }
    private val commands = mutableListOf<String>()
    private val crashReports = DeviceCrashReports(remote, udid, lazyOf("/usr/local/bin/idevicecrashreport"))

    // idevicecrashreport copies the device's reports into the directory given last, then removes them from the device
    private fun deviceHasReports(vararg names: String, exitCode: Int = 0) {
        whenever(remote.shell(any(), any(), any())).thenAnswer { invocation ->
            val command = invocation.getArgument<String>(0)
            commands += command
            val directory = File(command.substringAfterLast(' '))
            names.forEach { File(directory, it).writeText("report $it") }
            CommandResult(stdOut = "", stdErr = "", exitCode = exitCode, pid = 1)
        }
    }

    @Test
    fun deleteMovesReportsOffTheDeviceAndDiscardsThem() {
        deviceHasReports("MyApp.diskwrites_resource-2026-10-12-165624.ips")

        assertTrue(crashReports.delete())

        val command = commands.single()
        assertTrue(command, command.startsWith("/usr/local/bin/idevicecrashreport --udid $udid "))
        // -k would keep the reports on the device
        assertFalse(command, command.contains(" -k"))
        assertFalse(File(command.substringAfterLast(' ')).exists())
    }

    @Test
    fun deleteReportsFailureOfIdevicecrashreport() {
        deviceHasReports(exitCode = 1)

        assertFalse(crashReports.delete())
    }

    @Test
    fun pullReturnsReportsForTheApp() {
        deviceHasReports("MyApp-2026-10-26-033506.ips")

        val reports = crashReports.pull("MyApp")

        assertEquals(listOf(CrashLog("MyApp-2026-10-26-033506.ips", "report MyApp-2026-10-26-033506.ips")), reports)
        val command = commands.single()
        assertTrue(command, command.contains(" --filter \"MyApp\" "))
        assertFalse(File(command.substringAfterLast(' ')).exists())
    }
}
