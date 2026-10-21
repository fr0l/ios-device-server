package com.badoo.automation.deviceserver.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

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
            assertEquals(listOf("cheap", "works", "throws", "hangs"), report.checks.map { c -> c.name })
            assertEquals(CheckStatus.UNKNOWN, byName.getValue("throws").status)
            assertTrue(byName.getValue("throws").message.contains("no sntp"))
            assertTrue(byName.getValue("hangs").message.contains("timed out"))
            assertEquals(Health.DEGRADED, report.health)
            assertEquals(1, report.reasons.size)
        }
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
