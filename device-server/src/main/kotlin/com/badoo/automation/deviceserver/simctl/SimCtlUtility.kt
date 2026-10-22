package com.badoo.automation.deviceserver.simctl

import com.badoo.automation.deviceserver.command.CommandResult
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.data.AppInfo
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.slf4j.Marker
import java.io.File
import java.time.Duration

class SimCtlUtility(
    private val commandExecutor: IShellCommand
) {
    private val jacksonMapper = jacksonObjectMapper()

    companion object {
        const val SIMCTL_LIST_DEVICES_JSON = "/usr/bin/xcrun simctl list devices --json"
        const val SIMCTL_LIST_DEVICE_TYPES_JSON = "/usr/bin/xcrun simctl list devicetypes --json"
        const val SIMCTL_LIST_RUNTIMES_JSON = "/usr/bin/xcrun simctl runtime list --json"
        const val SIMCTL_DYLD_SHARED_CACHE_UPDATE = "/usr/bin/xcrun simctl runtime dyld_shared_cache update --all"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val jsonParser: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        prettyPrintIndent = "  "
        allowTrailingComma = true
        encodeDefaults = true
        coerceInputValues = true
        allowComments = true
    }

    // region: Create & Clone Simulators
    // Usage: simctl create <name> <device type id> [<runtime id>]
    fun createSimulator(name: String, deviceTypeId: String, runtimeIdentifier: String): UDID {
        val command = listOf(
            "/usr/bin/xcrun", "simctl", "create", name, deviceTypeId, runtimeIdentifier
        )
        val result = commandExecutor.exec(command)
        if (result.isSuccess) {
            val udid = result.stdOut.trim()

            if (udid.isEmpty() || udid.isBlank()) {
                throw SimCtlException("Simulator creation command returned an empty UDID.")
            }

            return udid
        } else {
            throw SimCtlException("Failed to create simulator: ${result.stdErr}")
        }
    }

    // Usage: simctl clone <device> <new name> [<destination device set>]
    fun cloneSimulator(sourceUdid: UDID, cloneName: String): UDID {
        val command = listOf(
            "/usr/bin/xcrun", "simctl", "clone", sourceUdid, cloneName
        )
        val result = commandExecutor.exec(command)
        if (result.isSuccess) {
            val cloneUdid = result.stdOut.trim()
            if (cloneUdid.isEmpty() || cloneUdid.isBlank()) {
                throw SimCtlException("Clone command returned an empty UDID.")
            }
            return cloneUdid
        } else {
            throw SimCtlException("Failed to clone simulator: ${result.stdErr}")
        }
    }
    // endregion

    // region: List Simulators, Device Types, and Runtimes

    private val cachedRuntimes: Set<SimulatorRuntime> by lazy { performListRuntimes() }
    private val cachedDeviceTypes: Set<DeviceType> by lazy { performListDeviceTypes() }

    fun listRuntimes(): Set<SimulatorRuntime> = cachedRuntimes
    fun listDeviceTypes(): Set<DeviceType> = cachedDeviceTypes

    private fun performListRuntimes(): Set<SimulatorRuntime> {
        val result = commandExecutor.exec(SIMCTL_LIST_RUNTIMES_JSON.split(" "))
        if (result.isSuccess) {
            return try {
                jsonParser.decodeFromString<Map<String, SimulatorRuntime>>(result.stdOut).values.toSet()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }

    // Update the shared cache after installing a new runtime.
    // https://developer.apple.com/documentation/xcode-release-notes/xcode-26_1-release-notes
    // Simulators may fail to boot during the first build after upgrading macOS. (152328794)
    fun dyldSharedCacheUpdate() {
        val result = commandExecutor.exec(SIMCTL_DYLD_SHARED_CACHE_UPDATE.split(" "))
        if (result.isSuccess) {

        } else {
            throw SimCtlException("Failed to update cache: ${result.stdErr}")
        }
    }

    private fun performListDeviceTypes(): Set<DeviceType> {
        val result = commandExecutor.exec(SIMCTL_LIST_DEVICE_TYPES_JSON.split(" "))
        if (result.isSuccess) {
            return try {
                jsonParser.decodeFromString<Map<String, List<DeviceType>>>(result.stdOut).values.first().toSet()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }

    fun listDevices(): Map<String, List<Simulator>> {
        val runtimes = listRuntimes()
        val result = commandExecutor.exec(SIMCTL_LIST_DEVICES_JSON.split(" "))
        if (result.isSuccess) {
            val devicesByRuntimeIdentifier: Map<String, List<Simulator>> = try {
                jsonParser.decodeFromString<Map<String, Map<String, List<Simulator>>>>(result.stdOut).values.first()
            } catch (e: kotlinx.serialization.SerializationException) {
                throw SimCtlException("Failed to decode JSON: ${e.message}.\nOriginal JSON string:\n${result.stdOut}\n=================\n", e)
            }
            return devicesByRuntimeIdentifier.map { (runtimeIdentifier, simulators) ->
                simulators.forEach { simulator ->
                    simulator.runtimeIdentifier = runtimeIdentifier
                    runtimes.find { runtime -> runtime.runtimeIdentifier == runtimeIdentifier }?.version?.let { version ->
                        simulator.osVersion = version
                    }
                }
                runtimeIdentifier to simulators
            }.toMap()
        } else {
            throw SimCtlException("Failed to list device types: ${result.stdErr}")
        }
    }
    // endregion

    // region: Boot & Shutdown Simulators
    fun bootSimulator(udid: UDID, timeOut: Duration, logMarker: Marker) {
        val command = listOf("/usr/bin/xcrun", "simctl", "boot", udid)
        val result = commandExecutor.exec(
            command = command,
            timeOut = timeOut,
            returnFailure = true,
            logMarker = logMarker
        )
        if (!result.isSuccess) {
            throw SimCtlException("Failed to boot simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun bootStatusSimulator(udid: UDID, timeOut: Duration, logMarker: Marker) {
        val command = listOf("/usr/bin/xcrun", "simctl", "bootstatus", udid)
        val result = commandExecutor.exec(
            command = command,
            timeOut = timeOut,
            returnFailure = true,
            logMarker = logMarker
        )
        if (!result.isSuccess) {
            throw SimCtlException("Failed to boot simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun shutdownSimulator(udid: UDID, ignoreError: Boolean): CommandResult {
        val command = listOf("/usr/bin/xcrun", "simctl", "shutdown", udid)
        val result = commandExecutor.exec(command)
        if (!result.isSuccess && !ignoreError) {
            throw SimCtlException("Failed to shutdown simulator with UDID '$udid': ${result.stdErr}")
        }

        return result
    }

    fun shutdownAllSimulators() {
        shutdownSimulator("all", ignoreError = true)
    }
    // endregion

    // region: Delete Simulators
    // Usage: simctl delete <device> [... <device n>] | unavailable | all
    fun deleteSimulator(udid: UDID) {
        val command = listOf("/usr/bin/xcrun", "simctl", "delete", udid)
        val result = commandExecutor.exec(command)
        if (!result.isSuccess) {
            throw SimCtlException("Failed to delete simulator with UDID '$udid': ${result.stdErr}")
        }
    }

    fun deleteAllSimulators() {
        deleteSimulator("all")
    }

    fun deleteUnavailableSimulators() {
        deleteSimulator("unavailable")
    }
    // endregion

    // region: App Management
    fun listApps(udid: UDID): List<AppInfo> {
        val result = commandExecutor.exec(
            listOf("/usr/bin/xcrun", "simctl", "listapps", udid),
            timeOut = Duration.ofSeconds(30)
        )
        if (!result.isSuccess) {
            throw SimCtlException("simctl listapps failed for $udid: ${result.stdErr}")
        }
        // simctl prints an old-style (OpenStep) plist, not JSON; plutil converts it
        val plist = File.createTempFile("simctl-listapps-", ".plist")
        try {
            plist.writeText(result.stdOut)
            val json = commandExecutor.exec(
                listOf("/usr/bin/plutil", "-convert", "json", "-o", "-", plist.absolutePath),
                timeOut = Duration.ofSeconds(30)
            )
            if (!json.isSuccess) {
                throw SimCtlException("plutil failed to convert simctl listapps output for $udid: ${json.stdErr}")
            }
            val entries: Map<String, SimctlAppEntry> = jacksonMapper.readValue(json.stdOut)
            return entries.values.map { it.toAppInfo() }
        } finally {
            plist.delete()
        }
    }

    fun terminateApp(udid: UDID, bundleId: String) {
        commandExecutor.exec(
            listOf("/usr/bin/xcrun", "simctl", "terminate", udid, bundleId),
            timeOut = Duration.ofSeconds(30),
            returnFailure = true
        )
    }

    fun uninstallApp(udid: UDID, bundleId: String) {
        val result = commandExecutor.exec(
            listOf("/usr/bin/xcrun", "simctl", "uninstall", udid, bundleId),
            timeOut = Duration.ofSeconds(60),
            returnFailure = true
        )
        if (!result.isSuccess) {
            throw SimCtlException("simctl uninstall failed for $bundleId on $udid: ${result.stdErr}")
        }
    }

    fun installApp(udid: UDID, bundlePath: File) {
        val result = commandExecutor.exec(
            listOf("/usr/bin/xcrun", "simctl", "install", udid, bundlePath.absolutePath),
            timeOut = Duration.ofSeconds(120),
            returnFailure = true
        )
        if (!result.isSuccess) {
            throw SimCtlException("simctl install failed for ${bundlePath.absolutePath} on $udid: ${result.stdErr}")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SimctlAppEntry(
        @JsonProperty("CFBundleIdentifier") val bundleId: String?,
        @JsonProperty("ApplicationType") val applicationType: String?,
        @JsonProperty("DataContainer") val dataContainer: String?,
        @JsonProperty("Path") val bundlePath: String?,
        @JsonProperty("CFBundleDisplayName") val displayName: String?,
        @JsonProperty("CFBundleName") val bundleName: String?
    ) {
        fun toAppInfo() = AppInfo(
            bundleId = bundleId ?: "",
            name = displayName ?: bundleName,
            bundlePath = bundlePath,
            dataContainer = dataContainer,
            installType = applicationType?.lowercase()
        )
    }
    // endregion
}