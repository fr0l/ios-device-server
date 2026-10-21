package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.ios.device.GoIos
import com.badoo.automation.deviceserver.ios.device.XcrunDeviceCtl
import com.badoo.automation.deviceserver.simctl.SimCtlUtility
import java.io.File
import java.time.Duration

interface IRemote {
    val homeBrewPath: File
    val tmpDir: File
    val hostName: String
    val publicHostName: String
    val commandExecutor: IShellCommand

    fun execIgnoringErrors(command: List<String>, env: Map<String, String> = emptyMap(), timeOutSeconds: Long = 60): CommandResult
            = exec(command, env, returnFailure = true, timeOutSeconds = timeOutSeconds)

    fun exec(command: List<String>, env: Map<String, String>, returnFailure: Boolean, timeOutSeconds: Long): CommandResult

    fun shell(command: String, returnOnFailure: Boolean = true, environment: Map<String, String> = emptyMap()) : CommandResult

    fun escape(value: String) : String

    /**
     * Returns [CommandResult] file contents
     * //FIXME: should be a better way of streaming a file over HTTP. without caching bytes in server's memory. Investigate ByteReadChannel
     */
    fun captureFile(file: File): File

    fun pkill(matchString: String, force: Boolean): CommandResult

    val simCtlControl: SimCtlUtility
    val deviceCtl: XcrunDeviceCtl
    val goIos: GoIos
    fun isDirectory(path: String): Boolean
    fun rm(path: String, timeOut: Duration = Duration.ofMinutes(3))
}
