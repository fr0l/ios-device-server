package com.badoo.automation.deviceserver.health

import com.fasterxml.jackson.annotation.JsonProperty

/** `GET /status/resources`: host memory, swap, load and CPU, measured on every request for the hub's charts. */
data class HostResources(
    @JsonProperty("memory_used_percent") val memoryUsedPercent: Double,
    @JsonProperty("memory_pressure") val memoryPressure: String,
    @JsonProperty("swap_used_gb") val swapUsedGb: Double,
    @JsonProperty("load_1m") val load1m: Double,
    @JsonProperty("load_5m") val load5m: Double,
    @JsonProperty("load_15m") val load15m: Double,
    val cores: Int,
    /** CPU since the previous request (user includes nice); null on the first request. */
    @JsonProperty("cpu_user_percent") val cpuUserPercent: Double?,
    @JsonProperty("cpu_system_percent") val cpuSystemPercent: Double?,
    @JsonProperty("cpu_idle_percent") val cpuIdlePercent: Double?
)

/** Turns [MacHostStats] samples into [HostResources]; CPU is the share of ticks between two consecutive calls. */
class HostResourcesSampler(
    private val sample: () -> MacHostStats.Sample = MacHostStats::sample,
    private val cores: Int = Runtime.getRuntime().availableProcessors()
) {
    private var previousTicks: MacHostStats.CpuTicks? = null

    fun read(): HostResources {
        val current = sample()
        val ticks = cpuDelta(current.cpuTicks)
        val total = (ticks?.run { user + nice + system + idle } ?: 0L).toDouble()
        fun cpuPercent(part: (MacHostStats.CpuTicks) -> Long) = ticks?.takeIf { total > 0 }?.let { round(part(it) * 100.0 / total) }
        // Same split as vm_stat and the memory_available_percent health check: free and inactive pages are available
        val available = (current.freePages + current.inactivePages) * current.pageSizeBytes
        return HostResources(
            memoryUsedPercent = round(100.0 - available * 100.0 / current.memoryBytes),
            memoryPressure = when (current.pressureLevel) { 1 -> "normal"; 2 -> "warn"; 4 -> "critical"; else -> "unknown" },
            swapUsedGb = round(current.swapUsedBytes / 1_073_741_824.0),
            load1m = round(current.load[0]),
            load5m = round(current.load[1]),
            load15m = round(current.load[2]),
            cores = cores,
            cpuUserPercent = cpuPercent { it.user + it.nice },
            cpuSystemPercent = cpuPercent { it.system },
            cpuIdlePercent = cpuPercent { it.idle }
        )
    }

    @Synchronized
    private fun cpuDelta(current: MacHostStats.CpuTicks): MacHostStats.CpuTicks? {
        val previous = previousTicks
        if (previous == null) {
            previousTicks = current
            return null
        }
        // Each counter is a 32-bit natural_t that wraps
        fun delta(now: Long, before: Long) = (now - before).mod(1L shl 32)
        val ticks = MacHostStats.CpuTicks(
            user = delta(current.user, previous.user),
            system = delta(current.system, previous.system),
            idle = delta(current.idle, previous.idle),
            nice = delta(current.nice, previous.nice)
        )
        // Calls within one clock tick (10 ms) see no ticks; keep the older baseline so the next call measures more
        if (ticks.run { user + system + idle + nice } == 0L) return null
        previousTicks = current
        return ticks
    }

    private fun round(value: Double) = Math.round(value * 10) / 10.0
}
