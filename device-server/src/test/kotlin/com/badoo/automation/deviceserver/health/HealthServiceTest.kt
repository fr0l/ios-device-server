package com.badoo.automation.deviceserver.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class HealthServiceTest {
    private fun check(name: String, status: CheckStatus, critical: Boolean = false) =
        HealthCheck(name, "resources", status, 1.0, null, "$name is $status", critical = critical)

    @Test
    fun verdictIsOkWhenAllOkOrNonCriticalUnknown() {
        val (health, reasons) = HealthReport.verdict(listOf(check("a", CheckStatus.OK), check("b", CheckStatus.UNKNOWN)))

        assertEquals(Health.OK, health)
        assertEquals(emptyList<String>(), reasons)
    }

    @Test
    fun verdictIsDegradedOnDegradedOrCriticalUnknown() {
        assertEquals(Health.DEGRADED, HealthReport.verdict(listOf(check("a", CheckStatus.DEGRADED))).first)
        assertEquals(Health.DEGRADED, HealthReport.verdict(listOf(check("a", CheckStatus.UNKNOWN, critical = true))).first)
    }

    @Test
    fun verdictIsFailingAndListsFailuresFirst() {
        val (health, reasons) = HealthReport.verdict(
            listOf(check("slow", CheckStatus.DEGRADED), check("ok", CheckStatus.OK), check("down", CheckStatus.FAILING))
        )

        assertEquals(Health.FAILING, health)
        assertEquals(listOf("down failing: down is FAILING", "slow degraded: slow is DEGRADED"), reasons)
    }

    @Test
    fun limitEvaluatesBothDirections() {
        val load = Limit(degraded = 1.0, failing = 2.0)
        assertEquals(CheckStatus.OK, load.evaluate(1.0, higherIsWorse = true))
        assertEquals(CheckStatus.DEGRADED, load.evaluate(1.5, higherIsWorse = true))
        assertEquals(CheckStatus.FAILING, load.evaluate(2.5, higherIsWorse = true))

        val disk = Limit(degraded = 15.0, failing = 5.0)
        assertEquals(CheckStatus.OK, disk.evaluate(40.0, higherIsWorse = false))
        assertEquals(CheckStatus.DEGRADED, disk.evaluate(10.0, higherIsWorse = false))
        assertEquals(CheckStatus.FAILING, disk.evaluate(4.0, higherIsWorse = false))

        assertEquals("degraded < 15%, failing < 5%", disk.describe(higherIsWorse = false, unit = "%"))
        assertEquals(false, Limit(degraded = 8.0).critical)
    }

    @Test
    fun failedOrSlowProbesBecomeUnknownInsteadOfFailingTheReport() {
        val service = HealthService(
            heavyProbes = listOf(
                Probe("works", "resources", critical = false) { listOf(check("works", CheckStatus.OK)) },
                Probe("throws", "time", critical = true) { error("no sntp") },
                Probe("hangs", "network", critical = false) { Thread.sleep(10_000); emptyList() }
            ),
            cheapProbes = listOf(Probe("cheap", "process", critical = false) { listOf(check("cheap", CheckStatus.OK)) }),
            refreshInterval = Duration.ofMinutes(1),
            probeTimeout = Duration.ofMillis(200)
        )
        service.use {
            val before = it.report()
            assertEquals(CheckStatus.UNKNOWN, before.checks.first { c -> c.name == "throws" }.status)
            assertEquals("not measured yet", before.checks.first { c -> c.name == "throws" }.message)

            it.refresh()
            val report = it.report()

            val byName = report.checks.associateBy { c -> c.name }
            assertEquals(listOf("cheap", "health_refresh_age_seconds", "works", "throws", "hangs"), report.checks.map { c -> c.name })
            assertEquals(CheckStatus.UNKNOWN, byName.getValue("throws").status)
            assertTrue(byName.getValue("throws").message.contains("no sntp"))
            assertTrue(byName.getValue("hangs").message.contains("timed out"))
            assertEquals(Health.DEGRADED, report.health)
            assertEquals(1, report.reasons.size)
        }
    }

    @Test
    fun refreshAgeDegradesThenFailsWhenBackgroundChecksStopUpdating() {
        var now = Instant.parse("2026-10-22T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val service = HealthService(
            heavyProbes = listOf(Probe("works", "resources", critical = false) { listOf(check("works", CheckStatus.OK)) }),
            cheapProbes = emptyList(),
            refreshInterval = Duration.ofSeconds(20),
            probeTimeout = Duration.ofSeconds(30),
            clock = clock
        )
        service.use {
            fun age() = it.report().checks.first { c -> c.name == "health_refresh_age_seconds" }

            assertEquals(CheckStatus.OK, age().status)
            it.refresh()
            now = now.plusSeconds(49)
            assertEquals(CheckStatus.OK, age().status)
            now = now.plusSeconds(2)
            assertEquals(CheckStatus.DEGRADED, age().status)
            now = now.plusSeconds(100)
            assertEquals(CheckStatus.FAILING, age().status)
            assertEquals(151L, age().value)
            assertEquals(Health.FAILING, it.report().health)
        }
    }

    @Test
    fun processProbesReportResidentMemoryCpuAndRecentBootFailures() {
        val boots = BootStats().apply {
            repeat(3) { recordSuccess(Duration.ofSeconds(30)) }
            repeat(5) { recordFailure(Duration.ofSeconds(180)) }
        }
        val probes = HostProbes(
            config = HealthConfig(probeUrls = emptyList()),
            volumes = emptyMap(),
            isDeviceManagerReady = { true },
            simulatorUsage = { 1 to 5 },
            runner = { command, _ ->
                assertEquals(listOf("/bin/ps", "-o", "rss=,%cpu=", "-p", "4242"), command)
                CommandOutput(0, "  1572864   12,5\n")
            },
            bootStats = boots,
            pid = 4242
        )

        val resources = probes.heavy().first { it.name == "process_resources" }.run().associateBy { it.name }
        assertEquals(CheckStatus.DEGRADED, resources.getValue("process_memory_mb").status)
        assertEquals(1536.0, resources.getValue("process_memory_mb").value)
        assertEquals(12.5, resources.getValue("process_cpu_percent").value)

        val process = probes.cheap().first().run().associateBy { it.name }
        assertEquals(3L, process.getValue("simulator_boots_succeeded_total").value)
        assertEquals(5L, process.getValue("simulator_boots_failed_total").value)
        assertEquals(CheckStatus.FAILING, process.getValue("simulator_boot_failures").status)
        assertEquals(5, process.getValue("simulator_boot_failures").value)
        assertEquals(30.0, process.getValue("simulator_boot_seconds").value)
    }

    @Test
    fun simulatorsMemoryAndHostCpuChecks() {
        val ticks = ArrayDeque(
            listOf(MacHostStats.CpuTicks(100, 50, 850, 0), MacHostStats.CpuTicks(160, 70, 970, 0))
        )
        val cpuSampler = HostResourcesSampler(
            sample = {
                MacHostStats.Sample(1L shl 30, 16384, 1000, 1000, 1, 0, listOf(1.0, 1.0, 1.0), ticks.removeFirst())
            },
            cores = 10
        )
        val probes = HostProbes(
            config = HealthConfig(probeUrls = emptyList()),
            volumes = emptyMap(),
            isDeviceManagerReady = { true },
            simulatorUsage = { 4 to 10 },
            runner = { _, _ ->
                val sim = "/bin/launchd_sim /Users/qa/Library/Developer/CoreSimulator/Devices"
                CommandOutput(
                    0,
                    "11 1 $sim/9A5DBEC2-9234-4EB9-9434-72C5DF7DEA0B/data/var/run/launchd_bootstrap.plist\n12 11 /usr/libexec/d\n" +
                        "21 1 $sim/4BCC071E-9613-4129-A40F-E5381EDBDB8E/data/var/run/launchd_bootstrap.plist\n22 21 gone\n13 1 /usr/bin/other\n"
                )
            },
            cpuSampler = cpuSampler,
            physicalFootprint = { pid -> mapOf(11 to (1L shl 30), 12 to (3L shl 30), 21 to (2L shl 30), 13 to (9L shl 30))[pid] }
        )
        val heavy = probes.heavy().associateBy { it.name }

        val memory = heavy.getValue("simulators_memory").run().associateBy { it.name }
        assertEquals(6.0, memory.getValue("simulators_memory_gb").value)
        assertEquals(3.0, memory.getValue("simulator_memory_gb_each").value)
        assertEquals(4.0, memory.getValue("simulator_memory_gb_max").value)
        assertEquals("9A5DBEC2-9234-4EB9-9434-72C5DF7DEA0B", memory.getValue("simulator_memory_gb_max").details["udid"])

        assertEquals(CheckStatus.UNKNOWN, heavy.getValue("host_cpu").run().single().status)
        // 60 user, 20 system, 120 idle of 200 ticks
        val cpu = heavy.getValue("host_cpu").run().associateBy { it.name }
        assertEquals(40.0, cpu.getValue("host_cpu_busy_percent").value)
        assertEquals(30.0, cpu.getValue("host_cpu_user_percent").value)
        assertEquals(10.0, cpu.getValue("host_cpu_system_percent").value)
    }

    @Test
    fun bootStatsKeepsOnlyTheLastTenBootsForTheVerdict() {
        val boots = BootStats()
        repeat(4) { boots.recordFailure(Duration.ofSeconds(180)) }
        repeat(10) { boots.recordSuccess(Duration.ofSeconds(20)) }

        val snapshot = boots.snapshot()

        assertEquals(BootStats.Snapshot(succeeded = 10, failed = 4, recentFailures = 0, recentBoots = 10, averageSeconds = 20.0), snapshot)
        assertEquals(null, BootStats().snapshot().averageSeconds)
    }

    @Test
    fun prometheusExposesStatusAndNumericValues() {
        val report = HealthReport(
            1, "now", "now", Health.DEGRADED, emptyList(),
            listOf(
                HealthCheck("disk_free:tmp", "resources", CheckStatus.DEGRADED, 12.5, null, "", unit = "%"),
                HealthCheck("link_type", "network", CheckStatus.OK, "ethernet", null, "")
            )
        )

        val text = PrometheusFormat.render(report)

        assertTrue(text.contains("device_server_health 1\n"))
        assertTrue(text.contains("device_server_health_check_status{name=\"disk_free:tmp\",group=\"resources\"} 1\n"))
        assertTrue(text.contains("device_server_health_check_value{name=\"disk_free:tmp\",group=\"resources\",unit=\"%\"} 12.5\n"))
        assertTrue(!text.contains("device_server_health_check_value{name=\"link_type\""))
    }

    @Test
    fun networkProbeFailsHundredMegabitHalfDuplexLinkOutsideExpectedSubnet() {
        val outputs = mapOf(
            "/sbin/route -n get default" to "gateway: 10.9.0.1\n interface: en0\n",
            "/usr/sbin/networksetup -listallhardwareports" to "Hardware Port: Ethernet\nDevice: en0\n",
            "/sbin/ifconfig en0" to "en0: flags=8863<UP> mtu 1500\n\tinet 10.9.0.5 netmask 0xffffff00 broadcast 10.9.0.255\n" +
                "\tmedia: autoselect (100baseTX <half-duplex>)\n\tstatus: active",
            "/sbin/ping -c 3 -i 0.2 -t 3 10.9.0.1" to "3 packets transmitted, 3 packets received, 0.0% packet loss\n" +
                "round-trip min/avg/max/stddev = 0.3/0.4/0.5/0.1 ms"
        )
        val probes = HostProbes(
            config = HealthConfig(probeUrls = emptyList(), expectedSubnets = listOf("10.20.0.0/16")),
            volumes = mapOf("tmp" to File("/tmp")),
            isDeviceManagerReady = { true },
            simulatorUsage = { 0 to 10 },
            runner = { command, _ -> CommandOutput(0, outputs[command.joinToString(" ")] ?: error("unexpected $command")) }
        )

        val checks = probes.heavy().first { it.name == "network" }.run().associateBy { it.name }

        assertEquals(CheckStatus.OK, checks.getValue("link_type").status)
        assertEquals(CheckStatus.FAILING, checks.getValue("link_speed_mbit").status)
        assertEquals(100, checks.getValue("link_speed_mbit").value)
        assertEquals(CheckStatus.FAILING, checks.getValue("link_duplex").status)
        assertEquals(CheckStatus.FAILING, checks.getValue("network_address").status)
        assertEquals("10.9.0.0/24", checks.getValue("network_address").details["subnet"])
        assertEquals(CheckStatus.OK, checks.getValue("gateway_ping_ms").status)
    }
}
