package com.badoo.automation.deviceserver.metrics

import com.badoo.automation.deviceserver.health.MacHostStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class DeviceServerMetricsTest {
    private fun metrics(running: Int = 3) =
        DeviceServerMetrics(hostInfo = { linkedMapOf("chip" to "Apple M1 Max", "memory_gb" to "32") }, runningSimulators = { running })

    @Test
    fun histogramRendersCumulativeBucketsSumAndCountSortedByNumericLabels() {
        val histogram = Histogram("boot_seconds", "Boots.", listOf(10.0, 30.0), listOf("running"))
        histogram.observe(5.0, "10")
        histogram.observe(20.0, "2")
        histogram.observe(25.0, "2")
        histogram.observe(100.0, "2")

        val text = StringBuilder().also { histogram.render(it) }.toString()

        assertEquals(
            """
            # HELP boot_seconds Boots.
            # TYPE boot_seconds histogram
            boot_seconds_bucket{running="2",le="10"} 0
            boot_seconds_bucket{running="2",le="30"} 2
            boot_seconds_bucket{running="2",le="+Inf"} 3
            boot_seconds_sum{running="2"} 145.0
            boot_seconds_count{running="2"} 3
            boot_seconds_bucket{running="10",le="10"} 1
            boot_seconds_bucket{running="10",le="30"} 1
            boot_seconds_bucket{running="10",le="+Inf"} 1
            boot_seconds_sum{running="10"} 5.0
            boot_seconds_count{running="10"} 1
            """.trimIndent() + "\n",
            text
        )
    }

    @Test
    fun bootsAreRecordedAsOkFailedOrTimeoutWithBootsInFlight() {
        val metrics = metrics(running = 4)

        metrics.recordSimulatorBoot(Duration.ofMinutes(3)) {}
        runCatching { metrics.recordSimulatorBoot(Duration.ofMinutes(3)) { error("bootstatus failed") } }
        runCatching { metrics.recordSimulatorBoot(Duration.ZERO) { error("bootstatus timed out") } }

        val text = metrics.render()
        assertTrue(text, text.contains("""device_server_simulator_boots_total{result="ok",boots_in_flight="1"} 1"""))
        assertTrue(text, text.contains("""device_server_simulator_boots_total{result="failed",boots_in_flight="1"} 1"""))
        assertTrue(text, text.contains("""device_server_simulator_boots_total{result="timeout",boots_in_flight="1"} 1"""))
        assertTrue(text, text.contains("""device_server_simulator_boot_seconds_count{boots_in_flight="1",running="4"} 1"""))
    }

    @Test
    fun bootsInFlightCountsConcurrentBoots() {
        val metrics = metrics()
        val bothStarted = CountDownLatch(2)
        val boots = List(2) {
            thread {
                metrics.recordSimulatorBoot(Duration.ofMinutes(3)) {
                    bothStarted.countDown()
                    bothStarted.await()
                }
            }
        }
        boots.forEach { it.join() }

        val text = metrics.render()
        assertTrue(text, text.contains("""device_server_simulator_boots_total{result="ok",boots_in_flight="2"}"""))
    }

    @Test
    fun agentStatusIsTimedByResultAndHostInfoIsAGauge() {
        val metrics = metrics(running = 7)

        assertEquals(true, metrics.recordAgentStatus { true })
        assertEquals(false, metrics.recordAgentStatus { false })

        val text = metrics.render()
        assertTrue(text, text.contains("""device_server_host_info{chip="Apple M1 Max",memory_gb="32"} 1"""))
        assertTrue(text, text.contains("""device_server_agent_status_seconds_count{result="ok",running="7"} 1"""))
        assertTrue(text, text.contains("""device_server_agent_status_seconds_count{result="failed",running="7"} 1"""))
    }

    @Test
    fun hardwareIsReadFromThisMac() {
        val hardware = MacHostStats.hardware()
        val sysctl = { name: String -> ProcessBuilder("/usr/sbin/sysctl", "-n", name).start().inputStream.bufferedReader().readText().trim() }

        assertEquals(sysctl("machdep.cpu.brand_string"), hardware.chip)
        assertEquals(sysctl("hw.memsize").toLong(), hardware.memoryBytes)
        assertEquals(sysctl("kern.osproductversion"), hardware.macOs)
        assertTrue(hardware.performanceCores > 0)

        val ownFootprint = MacHostStats.physicalFootprint(ProcessHandle.current().pid().toInt())
        assertTrue("$ownFootprint", ownFootprint != null && ownFootprint > 10L shl 20)
        assertEquals(null, MacHostStats.physicalFootprint(Int.MAX_VALUE))
    }
}
