package com.badoo.automation.deviceserver.health

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A named group of checks; if [run] throws or times out, its checks are reported as one unknown check. */
class Probe(val name: String, val group: String, val critical: Boolean, val run: () -> List<HealthCheck>)

/**
 * Serves `GET /status/health`: [cheapProbes] run on every request, [heavyProbes] in the background every
 * [refreshInterval], so a request never waits on a system utility.
 */
class HealthService(
    private val heavyProbes: List<Probe>,
    private val cheapProbes: List<Probe>,
    private val refreshInterval: Duration,
    private val probeTimeout: Duration = Duration.ofSeconds(15),
    private val clock: Clock = Clock.systemUTC()
) : AutoCloseable {
    private class Snapshot(val measuredAt: Instant?, val checks: List<HealthCheck>)

    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreads("health-refresh"))
    private val probePool: ExecutorService = Executors.newCachedThreadPool(daemonThreads("health-probe"))

    @Volatile
    private var snapshot = Snapshot(null, heavyProbes.map { unknown(it, "not measured yet") })

    fun start() {
        scheduler.scheduleWithFixedDelay(::refreshSafely, 0, refreshInterval.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun refresh() {
        val measuredAt = clock.instant()
        val futures = heavyProbes.map { probe -> probe to probePool.submit(Callable { probe.run() }) }
        val deadline = System.nanoTime() + probeTimeout.toNanos()
        val checks = futures.flatMap { (probe, future) ->
            try {
                future.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                listOf(unknown(probe, "timed out after ${probeTimeout.toSeconds()} s"))
            } catch (e: ExecutionException) {
                listOf(unknown(probe, describe(e.cause ?: e)))
            }
        }
        snapshot = Snapshot(measuredAt, checks)
    }

    fun report(): HealthReport {
        val cached = snapshot
        val checks = cheapProbes.flatMap { probe ->
            try {
                probe.run()
            } catch (e: Exception) {
                listOf(unknown(probe, describe(e)))
            }
        } + cached.checks
        val (health, reasons) = HealthReport.verdict(checks)
        return HealthReport(
            schemaVersion = HealthReport.SCHEMA_VERSION,
            generatedAt = clock.instant().toString(),
            measuredAt = cached.measuredAt?.toString(),
            health = health,
            reasons = reasons,
            checks = checks
        )
    }

    /** The report in Prometheus text exposition format. */
    fun prometheus(): String = PrometheusFormat.render(report())

    override fun close() {
        scheduler.shutdownNow()
        probePool.shutdownNow()
    }

    private fun refreshSafely() {
        // A throwing task would silently stop the periodic refresh.
        try {
            refresh()
        } catch (e: Exception) {
            logger.error("Failed to refresh health checks", e)
        }
    }

    private fun unknown(probe: Probe, message: String) =
        HealthCheck(probe.name, probe.group, CheckStatus.UNKNOWN, null, null, message, critical = probe.critical)

    private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

    private fun daemonThreads(name: String) = ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
}

internal object PrometheusFormat {
    fun render(report: HealthReport): String = buildString {
        appendLine("# HELP device_server_health Overall host health: 0 ok, 1 degraded, 2 failing.")
        appendLine("# TYPE device_server_health gauge")
        appendLine("device_server_health ${report.health.metricValue}")
        appendLine("# HELP device_server_health_check_status Check status: 0 ok, 1 degraded, 2 failing, 3 unknown.")
        appendLine("# TYPE device_server_health_check_status gauge")
        report.checks.forEach { appendLine("device_server_health_check_status${labels(it)} ${it.status.metricValue}") }
        appendLine("# HELP device_server_health_check_value Numeric value of a check, in its unit.")
        appendLine("# TYPE device_server_health_check_value gauge")
        report.checks.forEach { check ->
            (check.value as? Number)?.let { appendLine("device_server_health_check_value${labels(check, withUnit = true)} ${it.toDouble()}") }
        }
    }

    private fun labels(check: HealthCheck, withUnit: Boolean = false): String {
        val labels = mutableListOf("name" to check.name, "group" to check.group)
        if (withUnit && check.unit != null) labels += "unit" to check.unit
        return labels.joinToString(",", "{", "}") { (key, value) -> "$key=\"${escape(value)}\"" }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/** Runs a system utility; throws if it cannot be started or does not finish within the timeout. */
fun interface CommandRunner {
    fun run(command: List<String>, timeout: Duration): CommandOutput
}

data class CommandOutput(val exitCode: Int, val output: String)

class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeout: Duration): CommandOutput {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw TimeoutException("${command.first()} did not finish in ${timeout.toMillis()} ms")
            }
            return CommandOutput(process.exitValue(), output.get(1, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }
}

/** Version and git commit written into `device-server-build.properties` by the Gradle build. */
object BuildInfo {
    private val properties: Properties by lazy {
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/device-server-build.properties")?.use { load(it) }
        }
    }

    val version: String get() = properties.getProperty("version") ?: BuildInfo::class.java.`package`?.implementationVersion ?: "unknown"
    val gitCommit: String get() = properties.getProperty("git_commit") ?: "unknown"
}
