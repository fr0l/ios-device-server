package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.data.CrashLog
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.Executables
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Crash reports on a physical device; idevicecrashreport removes them from the device as it copies them. */
class DeviceCrashReports(
    private val remote: IRemote,
    private val udid: UDID,
    private val idevicecrashreport: Lazy<String> = lazy {
        Executables.require("idevicecrashreport", Executables.LIBIMOBILEDEVICE_INSTALL)
    }
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    fun pull(appName: String?): List<CrashLog> = withPulledReports(appName) { _, directory ->
        directory.toFile()
            .walk()
            .filter { it.isFile }
            .map { CrashLog(it.name, String(Files.readAllBytes(it.toPath()))) }
            .toList()
    }

    // Leaves the device without reports, so the next test's crash check only sees its own crashes.
    fun delete(): Boolean = withPulledReports(null) { result, _ -> result.isSuccess }

    private fun <T> withPulledReports(appName: String?, action: (CommandResult, Path) -> T): T {
        val directory = Files.createTempDirectory("crashReports")
        val filter = appName?.let { "--filter \"$it\"" } ?: ""
        val command = "${idevicecrashreport.value} --udid $udid $filter ${directory.toAbsolutePath()}"

        try {
            val result = remote.shell(command, returnOnFailure = true)

            if (!result.isSuccess) {
                logger.error(logMarker, "Failed to collect crash reports. Result stdErr: ${result.stdErr}")
            }

            return action(result, directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
