package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.repository.SimulatorRegistry
import com.badoo.automation.deviceserver.repository.SimulatorRepository
import com.badoo.automation.deviceserver.simctl.LaunchdDisabledStore
import com.badoo.automation.deviceserver.simctl.SimulatorTempFiles
import com.badoo.automation.deviceserver.simctl.SimCtlException
import com.badoo.automation.deviceserver.simctl.models.DeviceType
import com.badoo.automation.deviceserver.simctl.models.Simulator
import com.badoo.automation.deviceserver.simctl.models.SimulatorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

class SimulatorProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = simulator("BASE", "iPhone 17")
    private val namedClone = simulator("11111111-2222-3333-4444-555555555555","iPhone 17 Clone 1760000000")
    private val registeredClone = simulator("REGISTERED", "Renamed by someone")
    private val busyClone = simulator("BUSY", "iPhone 17 Clone 1760000001")
    private val foreign = simulator("FOREIGN", "My iPhone")

    private val repository: SimulatorRepository = mock()
    private lateinit var registry: SimulatorRegistry
    private lateinit var store: LaunchdDisabledStore
    private lateinit var provider: SimulatorProvider

    @Before
    fun setUp() {
        registry = SimulatorRegistry(File(tmp.root, "registry.json"))
        registry.addBaseSimulator(base)
        registry.addClonedSimulator(registeredClone)
        val varTmp = tmp.newFolder("var-tmp")
        store = LaunchdDisabledStore(varTmp.toPath())
        provider = SimulatorProvider(mock(), repository, registry, SimulatorTempFiles(tmp.newFolder("tmp"), varTmp))

        val all = listOf(base, namedClone, registeredClone, busyClone, foreign)
        whenever(repository.listDevices()).doReturn(mapOf("runtime" to all))
        whenever(repository.isClone(any())).doAnswer { Regex(".+ Clone \\d+").matches((it.arguments[0] as Simulator).name) }
    }

    @Test
    fun deletesClonesNotInUse() {
        store.disable(namedClone.udid, listOf("com.apple.a"))

        val deleted = provider.deleteOrphanClones { setOf(busyClone.udid) }

        assertEquals(listOf(namedClone.udid, registeredClone.udid), deleted)
        listOf(base, busyClone, foreign).forEach { verify(repository, never()).deleteSimulator(it.udid) }
        assertEquals(emptySet<Simulator>(), registry.getClonedSimulators())
        assertFalse(Files.exists(tmp.root.toPath().resolve("var-tmp/com.apple.CoreSimulator.SimDevice.${namedClone.udid}")))
    }

    @Test
    fun neverDeletesBaseSimulatorWithCloneLikeName() {
        val oddBase = simulator("ODD_BASE", "iPhone 17 Clone 1")
        registry.addBaseSimulator(oddBase)
        whenever(repository.listDevices()).doReturn(mapOf("runtime" to listOf(oddBase)))

        assertEquals(emptyList<String>(), provider.deleteOrphanClones { emptySet() })
    }

    @Test
    fun failedDeleteDoesNotStopTheRest() {
        whenever(repository.deleteSimulator(namedClone.udid)).thenThrow(SimCtlException("busy"))

        val deleted = provider.deleteOrphanClones { emptySet() }

        assertEquals(listOf(registeredClone.udid, busyClone.udid), deleted)
    }

    @Test
    fun cloneCreatesTheMissingBaseSimulatorOnTheIosRuntime() {
        val iPhone11 = DeviceType("iPhone", "/iPhone 11.simdevicetype", "com.apple.CoreSimulator.SimDeviceType.iPhone-11", "iPhone12,1", "13.0", "iPhone 11")
        val tvOs = SimulatorRuntime("23L470", "TV", "com.apple.platform.appletvsimulator", "com.apple.CoreSimulator.SimRuntime.tvOS-26-5", "Ready", "26.5")
        val iOs = SimulatorRuntime("23F77", "IOS", "com.apple.platform.iphonesimulator", "com.apple.CoreSimulator.SimRuntime.iOS-26-5", "Ready", "26.5")
        val newBase = simulator("NEW-BASE", "iPhone 11").apply { runtimeIdentifier = iOs.runtimeIdentifier }
        val clone = simulator("CLONE", "iPhone 11 Clone 1760000002")
        whenever(repository.listDeviceTypes()).doReturn(setOf(iPhone11))
        whenever(repository.listRuntimes()).doReturn(setOf(tvOs, iOs))
        whenever(repository.createSimulator("iPhone 11", iPhone11, iOs)).doReturn(newBase)
        whenever(repository.cloneSimulator(newBase)).doReturn(clone)

        val created = provider.createSimulatorClone(DesiredCapabilities(null, "iPhone 11", "iOS 26.5"), emptySet())

        assertEquals(clone, created)
        assertEquals(true, registry.getBaseSimulators().any { it.udid == newBase.udid })
    }

    @Test(expected = RuntimeException::class)
    fun ciHostsDoNotCreateMissingBaseSimulators() {
        val iPhone11 = DeviceType("iPhone", "/iPhone 11.simdevicetype", "com.apple.CoreSimulator.SimDeviceType.iPhone-11", "iPhone12,1", "13.0", "iPhone 11")
        val iOs = SimulatorRuntime("23F77", "IOS", "com.apple.platform.iphonesimulator", "com.apple.CoreSimulator.SimRuntime.iOS-26-5", "Ready", "26.5")
        whenever(repository.listDeviceTypes()).doReturn(setOf(iPhone11))
        whenever(repository.listRuntimes()).doReturn(setOf(iOs))
        val strict = SimulatorProvider(mock(), repository, registry, SimulatorTempFiles(tmp.newFolder("tmp-ci"), tmp.newFolder("var-tmp-ci")), createMissingBaseSimulators = false)

        strict.createSimulatorClone(DesiredCapabilities(null, "iPhone 11", "iOS 26.5"), emptySet())
    }

    private fun simulator(udid: String, name: String) = Simulator(
        dataPath = "/data/$udid",
        logPath = "/logs/$udid",
        udid = udid,
        isAvailable = true,
        deviceTypeIdentifier = "com.apple.CoreSimulator.SimDeviceType.iPhone-17",
        state = "Shutdown",
        name = name
    )
}
