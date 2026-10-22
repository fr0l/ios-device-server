package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.data.AppInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

class DeviceCtlError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class PhysicalDeviceInfo(
    val udid: String,
    val name: String,
    val model: String,
    val osVersion: String,
    val arch: String,
    val isConnected: Boolean
)

class XcrunDeviceCtl(private val shellCommand: IShellCommand) {

    private val mapper = jacksonObjectMapper()

    /** Paired iPhones and iPads; [PhysicalDeviceInfo.isConnected] is true for those attached by USB right now. */
    fun listDevices(): List<PhysicalDeviceInfo> =
        parseDeviceList(withJsonOutput(listOf("list", "devices"), Duration.ofSeconds(30)))

    internal fun parseDeviceList(json: JsonNode): List<PhysicalDeviceInfo> =
        json.at("/result/devices")
            // devicectl also lists this Mac's simulators, as "sameMachine" devices
            .filter { it.at("/hardwareProperties/platform").asText() == "iOS" }
            .filter { it.at("/connectionProperties/transportType").asText() != "sameMachine" }
            .map { node ->
                val hardware = node.at("/hardwareProperties")
                val props = node.at("/deviceProperties")
                PhysicalDeviceInfo(
                    // identifier is the CoreDevice UUID; iproxy and the rest need the device's UDID
                    udid = hardware.at("/udid").asText(node.at("/identifier").asText()),
                    name = props.at("/name").asText(""),
                    model = hardware.at("/productType").asText(""),
                    osVersion = props.at("/osVersionNumber").asText(""),
                    arch = hardware.at("/cpuType/name").asText(""),
                    // iproxy forwards ports over USB only
                    isConnected = node.at("/connectionProperties/transportType").asText() == "wired"
                )
            }

    fun getDevice(udid: UDID): PhysicalDeviceInfo? = listDevices().firstOrNull { it.udid == udid }

    fun listApps(udid: UDID): List<AppInfo> {
        val json = withJsonOutput(
            listOf("device", "info", "apps", "--device", udid, "--include-all-apps"),
            Duration.ofSeconds(30)
        )
        return json.at("/result/apps").map { node ->
            AppInfo(
                bundleId = node.at("/bundleIdentifier").asText(""),
                name = node.at("/name").asText(null),
                bundlePath = node.at("/installedPath").asText(null)?.ifEmpty { null },
                dataContainer = node.at("/dataContainerPath").asText(null)?.ifEmpty { null },
                installType = mapInstalledType(node.at("/installedType").asText(null))
            )
        }
    }

    fun installApp(udid: UDID, appPath: File) {
        withJsonOutput(
            listOf("device", "install", "app", "--device", udid, appPath.absolutePath),
            Duration.ofSeconds(120)
        )
    }

    fun uninstallApp(udid: UDID, bundleId: String) {
        withJsonOutput(
            listOf("device", "uninstall", "app", "--device", udid, bundleId),
            Duration.ofSeconds(60)
        )
    }

    private fun withJsonOutput(args: List<String>, timeOut: Duration): JsonNode {
        val tmp = Files.createTempFile("devicectl-", ".json")
        try {
            val cmd = DEVICECTL + args + listOf("--json-output", tmp.absolutePathString())
            val result = shellCommand.exec(cmd, timeOut = timeOut, returnFailure = true)
            if (result.exitCode != 0) {
                throw DeviceCtlError("devicectl failed: ${result.stdErr}")
            }
            return mapper.readTree(tmp.toFile())
        } finally {
            tmp.deleteIfExists()
        }
    }

    private fun mapInstalledType(raw: String?): String? = when (raw?.lowercase()) {
        "userapp" -> "user"
        "systemapp" -> "system"
        else -> raw?.lowercase()
    }

    companion object {
        private val DEVICECTL = listOf("/usr/bin/xcrun", "devicectl")
    }
}
