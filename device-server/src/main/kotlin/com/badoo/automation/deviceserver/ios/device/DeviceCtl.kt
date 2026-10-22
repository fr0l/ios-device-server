package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.data.AppInfo
import com.badoo.automation.deviceserver.util.Executables
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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

    fun listDevices(): List<PhysicalDeviceInfo> {
        val json = withJsonOutput(listOf("list", "devices"), Duration.ofSeconds(30))
        return json.at("/result/devices").map { node ->
            val hardware = node.at("/hardwareProperties")
            val props = node.at("/deviceProperties")
            val conn = node.at("/connectionProperties")
            PhysicalDeviceInfo(
                udid = props.at("/ddiServicesUDID").asText(node.at("/identifier").asText()),
                name = props.at("/name").asText(""),
                model = hardware.at("/marketingName").asText(""),
                osVersion = props.at("/osVersionNumber").asText(""),
                arch = hardware.at("/cpuType/name").asText(""),
                isConnected = conn.at("/tunnelState").asText("") == "connected"
                    || conn.at("/pairingState").asText("") == "paired"
            )
        }
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

class GoIos(private val shellCommand: IShellCommand) {

    private val mapper = jacksonObjectMapper()

    fun listDevices(): List<PhysicalDeviceInfo> {
        val result = shellCommand.exec(
            listOf(GO_IOS, "list", "--details"),
            timeOut = Duration.ofSeconds(30),
            returnFailure = true
        )
        if (!result.isSuccess) return emptyList()
        return parseDeviceList(result.stdOut)
    }

    /** go-ios prints `{"deviceList": [...]}`, older builds a bare array; log lines may come first. */
    internal fun parseDeviceList(output: String): List<PhysicalDeviceInfo> {
        val json = output.lines().map { it.trim() }.lastOrNull { it.startsWith("{\"deviceList\"") || it.startsWith("[") }
            ?: throw DeviceCtlError("Unexpected go-ios list output: $output")
        val root = mapper.readTree(json)
        val nodes = if (root.isArray) root else root.path("deviceList")
        return nodes.map { node ->
            val props = node.path("Properties").takeIf { !it.isMissingNode } ?: node
            PhysicalDeviceInfo(
                udid = node.path("Udid").asText(null) ?: node.path("DeviceID").asText(""),
                name = props.path("DeviceName").asText(""),
                model = props.path("ProductType").asText(""),
                osVersion = props.path("ProductVersion").asText(""),
                arch = props.path("CPUArchitecture").asText(""),
                isConnected = true
            )
        }
    }

    fun listApps(udid: UDID): List<AppInfo> {
        val result = shellCommand.exec(
            listOf(GO_IOS, "apps", "--udid", udid),
            timeOut = Duration.ofSeconds(30),
            returnFailure = true
        )
        if (!result.isSuccess) {
            throw DeviceCtlError("go-ios apps failed for $udid: ${result.stdErr}")
        }
        val nodes: List<JsonNode> = mapper.readValue(result.stdOut)
        return nodes.map { node ->
            AppInfo(
                bundleId = node.at("/CFBundleIdentifier").asText(""),
                name = node.at("/CFBundleDisplayName").asText(null)?.ifEmpty { null }
                    ?: node.at("/CFBundleName").asText(null)?.ifEmpty { null },
                bundlePath = node.at("/BundlePath").asText(null)?.ifEmpty { null },
                dataContainer = null,
                installType = node.at("/ApplicationType").asText(null)?.lowercase()
            )
        }
    }

    fun installApp(udid: UDID, appPath: File) {
        val result = shellCommand.exec(
            listOf(GO_IOS, "install", "--path=${appPath.absolutePath}", "--udid", udid),
            timeOut = Duration.ofSeconds(120),
            returnFailure = true
        )
        if (!result.isSuccess) {
            throw DeviceCtlError("go-ios install failed for ${appPath.absolutePath} on $udid: ${result.stdErr}")
        }
    }

    fun uninstallApp(udid: UDID, bundleId: String) {
        val result = shellCommand.exec(
            listOf(GO_IOS, "uninstall", bundleId, "--udid", udid),
            timeOut = Duration.ofSeconds(60),
            returnFailure = true
        )
        if (!result.isSuccess) {
            throw DeviceCtlError("go-ios uninstall failed for $bundleId on $udid: ${result.stdErr}")
        }
    }

    companion object {
        private val GO_IOS = Executables.find("go-ios")?.absolutePath ?: "go-ios"
    }
}
