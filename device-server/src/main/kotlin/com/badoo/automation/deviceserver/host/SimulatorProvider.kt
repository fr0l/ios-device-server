package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.SimulatorsDto
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.repository.SimulatorRegistry
import com.badoo.automation.deviceserver.repository.SimulatorRepository
import com.badoo.automation.deviceserver.simctl.SimulatorTempFiles
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.RuntimeException

class SimulatorProvider(
    val remote: IRemote,
    private val simulatorRepository: SimulatorRepository,
    private val simulatorRegistry: SimulatorRegistry,
    private val simulatorTempFiles: SimulatorTempFiles = SimulatorTempFiles()
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    fun syncSimulatorsWithRegistry() {
        val simulators = listSimulators()
        simulators.baseSimulators.forEach { baseSimulator: Simulator ->
            val simulator = simulatorRepository.findSimulatorByUdid(baseSimulator.udid)
            if (simulator == null) {
                simulatorRegistry.removeSimulator(baseSimulator.udid)
            }
        }

        simulators.clonedSimulators.forEach { clonedSimulator: Simulator ->
            val simulator = simulatorRepository.findSimulatorByUdid(clonedSimulator.udid)
            if (simulator == null) {
                simulatorRegistry.removeSimulator(clonedSimulator.udid)
            }
        }
    }

    fun createBaseSimulator(desiredCaps: DesiredCapabilities): Simulator {
        val deviceType: DeviceType = desiredCaps.toDeviceType(simulatorRepository)
        val runtime = desiredCaps.toRuntime(simulatorRepository)

        // check if exists
        simulatorRegistry.getBaseSimulators().find { baseSimulator: Simulator ->
            baseSimulator.runtimeIdentifier == runtime.runtimeIdentifier && baseSimulator.deviceTypeIdentifier == deviceType.identifier
        }?.let { registryRecord ->
            simulatorRepository.findSimulatorByUdid(registryRecord.udid)?.let { foundSimulator ->
                return foundSimulator
            }
        }

        val simulator = simulatorRepository.createSimulator(deviceType.name, deviceType, runtime)
            ?: throw RuntimeException("Failed to create base simulator with deviceName: ${desiredCaps.model ?: "null"}, deviceType: ${deviceType}, runtime: $runtime")
        simulatorRegistry.addBaseSimulator(simulator)
        return simulator
    }

    fun createSimulatorClone(desiredCaps: DesiredCapabilities, usedUdids: Set<String>): Simulator {
        if (desiredCaps.udid != null && desiredCaps.udid.isNotBlank()) {
            return provideSimulatorByUdid(usedUdids = usedUdids, udid = desiredCaps.udid, simulators = simulatorRepository.listDevices().values.flatten())
        }

        val deviceType: DeviceType = desiredCaps.toDeviceType(simulatorRepository)
        val runtime: SimulatorRuntime = desiredCaps.toRuntime(simulatorRepository)
        val baseSimulator: Simulator = simulatorRegistry.getBaseSimulators().find { it.deviceTypeIdentifier == deviceType.identifier && it.runtimeIdentifier == runtime.runtimeIdentifier }
            ?: throw RuntimeException("Base simulator not found for device type: $deviceType and runtime: $runtime")

        val clone = simulatorRepository.cloneSimulator(baseSimulator) ?: throw RuntimeException("Unable to clone base simulator: $baseSimulator")
        simulatorRegistry.addClonedSimulator(clone)
        return clone
    }

    fun deleteSimulator(udid: UDID) {
        simulatorRepository.deleteSimulator(udid)
        simulatorRegistry.removeSimulator(udid)
        simulatorTempFiles.delete(udid)
    }

    /** Deletes temp files of simulators that no longer exist. */
    fun deleteOrphanTempFiles(): List<File> = simulatorTempFiles.deleteOrphans {
        simulatorRepository.listDevices().values.flatten().map { it.udid }.toSet()
    }

    /**
     * Deletes clones left on disk (registered, or named like ours) that [inUse] does not hold, e.g. after a restart
     * or a failed release. Base simulators are never touched.
     */
    fun deleteOrphanClones(inUse: () -> Set<UDID>): List<UDID> {
        val registeredClones = simulatorRegistry.getClonedSimulators().map { it.udid }.toSet()
        val baseSimulators = simulatorRegistry.getBaseSimulators().map { it.udid }.toSet()
        val clones = simulatorRepository.listDevices().values.flatten()
            .filter { (it.udid in registeredClones || simulatorRepository.isClone(it)) && it.udid !in baseSimulators }
            .map { it.udid }
        // Taken after listing, so a clone being created right now is either not listed or already in use.
        val used = inUse()
        return clones.filterNot { it in used }.filter { udid ->
            try {
                simulatorRepository.shutdownSimulator(udid)
                deleteSimulator(udid)
                true
            } catch (e: Exception) {
                logger.warn("Failed to delete orphan simulator clone $udid: $e")
                false
            }
        }
    }

    fun listSimulators(): SimulatorsDto {
        return SimulatorsDto(
            baseSimulators = simulatorRegistry.getBaseSimulators(),
            clonedSimulators = simulatorRegistry.getClonedSimulators(),
            allSimulators = simulatorRepository.listDevices().values.flatten().toSet()
        )
    }

    private fun provideSimulatorByUdid(usedUdids: Set<String>, udid: String, simulators: List<Simulator>): Simulator {
        if (usedUdids.contains(udid)) {
            throw RuntimeException("Simulator with UDID ${udid} is already in use. List of used devices is $usedUdids")
        }

        val matched = simulators.find { it.udid == udid }

        if (matched == null) {
            throw RuntimeException("Unable to find requested device with UDID ${udid}. List of known devices is $simulators")
        }

        return matched
    }
}

private fun DesiredCapabilities.toDeviceType(simulatorRepository: SimulatorRepository): DeviceType {
    model?.let {
        return simulatorRepository.listDeviceTypes().find { deviceType -> deviceType.name == it }
            ?: throw RuntimeException("Unable to device type for desired capabilities: $this. Available runtimes: ${simulatorRepository.listDeviceTypes()}")
    }

    throw RuntimeException("Device \"model\" cannot be null. $this")
}

private fun DesiredCapabilities.toRuntime(simulatorRepository: SimulatorRepository): SimulatorRuntime {
    osVersion?.let {
        return simulatorRepository.listRuntimes().find { runtime -> runtime.version == it }
            ?: throw RuntimeException("Unable to find runtime for desired capabilities: $this. Available runtimes: ${simulatorRepository.listRuntimes()}")
    }

    throw RuntimeException("Device \"osVersion\" cannot be null. $this")
}
