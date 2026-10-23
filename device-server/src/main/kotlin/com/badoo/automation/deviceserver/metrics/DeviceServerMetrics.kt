package com.badoo.automation.deviceserver.metrics

import com.badoo.automation.deviceserver.health.BootStats
import com.badoo.automation.deviceserver.health.MacHostStats
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Metrics for sizing hosts: boot time and DeviceAgent response time against how many simulators boot or run at once,
 * per hardware class (`device_server_host_info`). Appended to `GET /status/health/metrics`.
 */
class DeviceServerMetrics(
    private val hostInfo: () -> Map<String, String>,
    @Volatile var runningSimulators: () -> Int = { 0 }
) {
    private val bootsInFlight = AtomicInteger()

    val simulatorBootSeconds = Histogram(
        "device_server_simulator_boot_seconds",
        "Successful simulator boots (simctl boot to bootstatus) by boots in flight, including this one, and simulators held by the server when it started.",
        listOf(10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0),
        listOf("boots_in_flight", "running")
    )

    val simulatorBoots = Counter(
        "device_server_simulator_boots_total",
        "Simulator boots by result (ok, timeout after the 180 s bootstatus limit, failed) and boots in flight.",
        listOf("result", "boots_in_flight")
    )

    val agentStatusSeconds = Histogram(
        "device_server_agent_status_seconds",
        "DeviceAgent/WebDriverAgent health check response time by result and simulators held by the server.",
        listOf(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0),
        listOf("result", "running")
    )

    private val hostInfoLabels by lazy { hostInfo() }

    /** Runs a simulator boot, recording it here and in [BootStats]; returns the boot time in nanoseconds. */
    fun recordSimulatorBoot(timeout: Duration, boot: () -> Unit): Long {
        val inFlight = bootsInFlight.incrementAndGet().toString()
        val running = runningSimulators().toString()
        val started = System.nanoTime()
        try {
            boot()
            val nanos = System.nanoTime() - started
            simulatorBootSeconds.observe(nanos / 1e9, inFlight, running)
            simulatorBoots.inc("ok", inFlight)
            BootStats.simulators.recordSuccess(Duration.ofNanos(nanos))
            return nanos
        } catch (e: Exception) {
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            simulatorBoots.inc(if (elapsed >= timeout) "timeout" else "failed", inFlight)
            BootStats.simulators.recordFailure(elapsed)
            throw e
        } finally {
            bootsInFlight.decrementAndGet()
        }
    }

    /** Times a DeviceAgent/WebDriverAgent health check and returns its result. */
    fun recordAgentStatus(check: () -> Boolean): Boolean {
        val running = runningSimulators().toString()
        val started = System.nanoTime()
        val healthy = check()
        agentStatusSeconds.observe((System.nanoTime() - started) / 1e9, if (healthy) "ok" else "failed", running)
        return healthy
    }

    fun render(): String = buildString {
        appendLine("# HELP device_server_host_info Hardware and software of the host; always 1.")
        appendLine("# TYPE device_server_host_info gauge")
        appendLine("device_server_host_info${labels(hostInfoLabels.keys.toList(), hostInfoLabels.values.toList())} 1")
        simulatorBootSeconds.render(this)
        simulatorBoots.render(this)
        agentStatusSeconds.render(this)
    }

    companion object {
        /** Recorded by every simulator of this server. */
        val shared = DeviceServerMetrics(hostInfo = ::readHostInfo)

        private fun readHostInfo(): Map<String, String> {
            val hardware = MacHostStats.hardware()
            return linkedMapOf(
                "chip" to hardware.chip,
                "performance_cores" to hardware.performanceCores.toString(),
                "efficiency_cores" to hardware.efficiencyCores.toString(),
                "memory_gb" to (hardware.memoryBytes / 1_073_741_824).toString(),
                "macos" to hardware.macOs,
                "xcode" to xcodeVersion()
            )
        }

        private fun xcodeVersion(): String = try {
            val process = ProcessBuilder("/usr/bin/xcodebuild", "-version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Regex("Xcode (\\S+)").find(output)?.groupValues?.get(1) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
