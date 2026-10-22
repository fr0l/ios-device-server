package com.badoo.automation.deviceserver.health

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

enum class CheckStatus(@get:JsonValue val label: String, val metricValue: Int) {
    OK("ok", 0), DEGRADED("degraded", 1), FAILING("failing", 2), UNKNOWN("unknown", 3)
}

enum class Health(@get:JsonValue val label: String, val metricValue: Int) {
    OK("ok", 0), DEGRADED("degraded", 1), FAILING("failing", 2)
}

/** One measurement; [critical] checks can fail the host, and an unknown critical check degrades it. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class HealthCheck(
    val name: String,
    val group: String,
    val status: CheckStatus,
    val value: Any?,
    val threshold: String?,
    val message: String,
    val critical: Boolean = false,
    val unit: String? = null,
    val details: Map<String, Any?> = emptyMap()
)

data class HealthReport(
    @JsonProperty("schema_version") val schemaVersion: Int,
    @JsonProperty("generated_at") val generatedAt: String,
    @JsonProperty("measured_at") val measuredAt: String?,
    val health: Health,
    val reasons: List<String>,
    val checks: List<HealthCheck>
) {
    companion object {
        const val SCHEMA_VERSION = 1

        fun verdict(checks: List<HealthCheck>): Pair<Health, List<String>> {
            val counted = checks.filter {
                it.status == CheckStatus.FAILING || it.status == CheckStatus.DEGRADED ||
                    (it.status == CheckStatus.UNKNOWN && it.critical)
            }
            val health = when {
                counted.any { it.status == CheckStatus.FAILING } -> Health.FAILING
                counted.isNotEmpty() -> Health.DEGRADED
                else -> Health.OK
            }
            val order = listOf(CheckStatus.FAILING, CheckStatus.DEGRADED, CheckStatus.UNKNOWN)
            val reasons = counted.sortedBy { order.indexOf(it.status) }.map { "${it.name} ${it.status.label}: ${it.message}" }
            return health to reasons
        }
    }
}

/** A threshold pair; a check whose limit has no [failing] value can only degrade the host. */
data class Limit(val degraded: Double? = null, val failing: Double? = null) {
    val critical: Boolean get() = failing != null

    fun evaluate(value: Double, higherIsWorse: Boolean): CheckStatus {
        fun breaches(threshold: Double?) = threshold != null && if (higherIsWorse) value > threshold else value < threshold
        return when {
            breaches(failing) -> CheckStatus.FAILING
            breaches(degraded) -> CheckStatus.DEGRADED
            else -> CheckStatus.OK
        }
    }

    fun describe(higherIsWorse: Boolean, unit: String): String? {
        val op = if (higherIsWorse) ">" else "<"
        return listOfNotNull(
            degraded?.let { "degraded $op ${format(it)}$unit" },
            failing?.let { "failing $op ${format(it)}$unit" }
        ).joinToString(", ").ifEmpty { null }
    }

    private fun format(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthConfig(
    @JsonProperty("refresh_seconds") val refreshSeconds: Long = 20,
    /** Endpoints probed for DNS, TCP connect and HTTP time to first byte, e.g. the CI server and artifact storage. */
    @JsonProperty("probe_urls") val probeUrls: List<String> = listOf("https://storage.googleapis.com/"),
    @JsonProperty("ntp_server") val ntpServer: String = "time.apple.com",
    /** CIDRs the primary interface must be in, e.g. the CI VLAN; empty disables the check. */
    @JsonProperty("expected_subnets") val expectedSubnets: List<String> = emptyList(),
    /** Exact Xcode version (e.g. "26.5") the fleet should run; null only enforces the minimum. */
    @JsonProperty("expected_xcode_version") val expectedXcodeVersion: String? = null,
    val thresholds: HealthThresholds = HealthThresholds()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HealthThresholds(
    @JsonProperty("load_per_core") val loadPerCore: Limit = Limit(degraded = 1.0, failing = 2.0),
    @JsonProperty("memory_available_percent") val memoryAvailablePercent: Limit = Limit(degraded = 10.0),
    // kern.memorystatus_vm_pressure_level: 1 normal, 2 warning, 4 critical
    @JsonProperty("memory_pressure_level") val memoryPressureLevel: Limit = Limit(degraded = 1.0, failing = 2.0),
    @JsonProperty("swap_used_gb") val swapUsedGb: Limit = Limit(degraded = 8.0),
    @JsonProperty("disk_free_percent") val diskFreePercent: Limit = Limit(degraded = 15.0, failing = 5.0),
    @JsonProperty("disk_free_gb") val diskFreeGb: Limit = Limit(degraded = 50.0, failing = 15.0),
    @JsonProperty("heap_used_percent") val heapUsedPercent: Limit = Limit(degraded = 85.0, failing = 95.0),
    // Resident memory of the whole process (heap, code, thread stacks), not just the heap
    @JsonProperty("process_memory_mb") val processMemoryMb: Limit = Limit(degraded = 1024.0, failing = 2048.0),
    // ps %cpu, a decaying average; 100 is one core fully busy
    @JsonProperty("process_cpu_percent") val processCpuPercent: Limit = Limit(degraded = 100.0),
    // Of the last BootStats.WINDOW simulator boots
    @JsonProperty("simulator_boot_failures") val simulatorBootFailures: Limit = Limit(degraded = 0.0, failing = 4.0),
    @JsonProperty("simulator_boot_seconds") val simulatorBootSeconds: Limit = Limit(degraded = 90.0),
    @JsonProperty("jvm_threads") val jvmThreads: Limit = Limit(degraded = 2000.0, failing = 5000.0),
    @JsonProperty("simctl_list_ms") val simctlListMs: Limit = Limit(degraded = 5000.0, failing = 20000.0),
    @JsonProperty("link_speed_mbit") val linkSpeedMbit: Limit = Limit(failing = 1000.0),
    @JsonProperty("gateway_ping_ms") val gatewayPingMs: Limit = Limit(degraded = 5.0, failing = 50.0),
    @JsonProperty("gateway_packet_loss_percent") val gatewayPacketLossPercent: Limit = Limit(degraded = 0.0, failing = 50.0),
    @JsonProperty("dns_resolve_ms") val dnsResolveMs: Limit = Limit(degraded = 500.0, failing = 3000.0),
    @JsonProperty("tcp_connect_ms") val tcpConnectMs: Limit = Limit(degraded = 300.0, failing = 3000.0),
    @JsonProperty("http_ttfb_ms") val httpTtfbMs: Limit = Limit(degraded = 1500.0, failing = 10000.0),
    @JsonProperty("ntp_offset_seconds") val ntpOffsetSeconds: Limit = Limit(degraded = 1.0, failing = 30.0)
)
