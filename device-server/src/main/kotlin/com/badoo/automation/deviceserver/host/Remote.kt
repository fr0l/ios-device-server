package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.*
import com.badoo.automation.deviceserver.ios.device.GoIos
import com.badoo.automation.deviceserver.ios.device.XcrunDeviceCtl
import com.badoo.automation.deviceserver.simctl.SimCtlUtility
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.time.Duration
import java.time.Duration.ofSeconds

class Remote(
    override val hostName: String,
    override val publicHostName: String,
    override val commandExecutor: IShellCommand = ShellCommand(),
    appConfig: ApplicationConfiguration = ApplicationConfiguration()
) : IRemote {
    companion object {
        fun getLocalCommandExecutor(): IShellCommand {
            return ShellCommand()
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(
            LogMarkers.HOSTNAME to hostName
    ))

    override fun toString(): String = "<Node:$publicHostName>"

    override val homeBrewPath: File = File("/usr/local/bin")

    override val tmpDir: File = appConfig.tempFolder

    override val simCtlControl: SimCtlUtility = SimCtlUtility(commandExecutor)
    override val deviceCtl: XcrunDeviceCtl = XcrunDeviceCtl(commandExecutor)
    override val goIos: GoIos = GoIos(commandExecutor)

    override fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult {
        return commandExecutor.exec(command, env, returnFailure = returnFailure, timeOut = ofSeconds(timeOutSeconds))
    }

    override fun escape(value: String) = commandExecutor.escape(value)

    override fun shell(command: String, returnOnFailure: Boolean, environment: Map<String, String>): CommandResult {
        val cmd = listOf("bash", "-c", command)

        return commandExecutor.exec(cmd, environment, returnFailure = returnOnFailure)
    }

    override fun captureFile(file: File): File {
        if (!file.exists()) {
            throw FileNotFoundException("File $file is not found.")
        }
        return file
    }

    private enum class Signal(val signal: Int) {
        SIGKILL(9),
        SIGTERM(15);

        override fun toString(): String {
            return signal.toString()
        }
    }

    override fun pkill(matchString: String, force: Boolean): CommandResult {
        val signal = if (force) { Signal.SIGKILL } else { Signal.SIGTERM }
        return execIgnoringErrors(listOf("pkill", "-$signal", "-f", matchString))
    }

    override fun isDirectory(path: String): Boolean {
        return commandExecutor.exec(listOf("test", "-d", path), mapOf(), returnFailure = true).isSuccess
    }

    override fun rm(path: String, timeOut: Duration) {
        val result = commandExecutor.exec(listOf("/bin/rm", "-rf", path), timeOut = timeOut, returnFailure = true)

        ensure(result.isSuccess) {
            val message = "Failed to delete remote files. Stderr: ${result.stdErr}"
            logger.error(logMarker, message)
            RuntimeException(message)
        }
    }
}
