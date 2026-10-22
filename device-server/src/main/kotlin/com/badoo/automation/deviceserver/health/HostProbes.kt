package com.badoo.automation.deviceserver.health

import com.badoo.automation.deviceserver.host.management.XcodeVersion
import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Duration
import kotlin.math.abs

/** Builds the probes of a macOS host running the device server. */
class HostProbes(
    private val config: HealthConfig,
    private val volumes: Map<String, File>,
    private val isDeviceManagerReady: () -> Boolean,
    private val simulatorUsage: () -> Pair<Int, Int>,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    private val bootStats: BootStats = BootStats.simulators,
    private val pid: Long = ProcessHandle.current().pid()
) {
    private val limits = config.thresholds

    fun cheap(): List<Probe> = listOf(
        Probe("process", PROCESS, critical = true, run = ::process)
    )

    fun heavy(): List<Probe> = listOf(
        Probe("load_per_core", RESOURCES, limits.loadPerCore.critical, ::load),
        Probe("memory", RESOURCES, critical = true, run = ::memory),
        Probe("process_resources", PROCESS, limits.processMemoryMb.critical, ::processResources),
        Probe("disk", RESOURCES, critical = true, run = ::disks),
        Probe("thermal_state", RESOURCES, critical = false, run = ::thermal),
        Probe("simctl_list_ms", PROCESS, critical = true, run = ::simctlList),
        Probe("xcode_version", PROCESS, critical = true, run = ::xcode),
        Probe("network", NETWORK, critical = true, run = ::network),
        Probe("ntp_offset_seconds", TIME, critical = true, run = ::ntp)
    ) + config.probeUrls.map { url ->
        Probe("endpoint:${URI(url).host}", NETWORK, critical = true) { endpoint(url) }
    }

    // region: resources
    private fun load(): List<HealthCheck> {
        val load = MacOsParsers.parseLoadAverage(exec("/usr/sbin/sysctl", "-n", "vm.loadavg"))
        val perCore = load.five / cores
        return listOf(
            measured(
                "load_per_core", RESOURCES, perCore, "", limits.loadPerCore, higherIsWorse = true,
                message = "5 min load ${load.five} on $cores cores",
                details = mapOf("load_1m" to load.one, "load_5m" to load.five, "load_15m" to load.fifteen, "cores" to cores)
            )
        )
    }

    private fun memory(): List<HealthCheck> {
        val total = exec("/usr/sbin/sysctl", "-n", "hw.memsize").trim().toLong()
        val vm = MacOsParsers.parseVmStat(exec("/usr/bin/vm_stat"))
        val available = vm.bytes("Pages free") + vm.bytes("Pages inactive") + vm.bytes("Pages speculative")
        val availablePercent = available * 100.0 / total
        val swap = MacOsParsers.parseSwapUsage(exec("/usr/sbin/sysctl", "vm.swapusage"))
        val pressure = exec("/usr/sbin/sysctl", "-n", "kern.memorystatus_vm_pressure_level").trim().toInt()
        val pressureName = when (pressure) { 1 -> "normal"; 2 -> "warning"; 4 -> "critical"; else -> "level $pressure" }
        return listOf(
            measured(
                "memory_available_percent", RESOURCES, availablePercent, "%", limits.memoryAvailablePercent, higherIsWorse = false,
                message = "${gb(available)} GB of ${gb(total)} GB free, inactive or speculative",
                details = mapOf("available_gb" to gb(available), "total_gb" to gb(total))
            ),
            measured(
                "memory_pressure_level", RESOURCES, pressure.toDouble(), "", limits.memoryPressureLevel, higherIsWorse = true,
                message = "memory pressure $pressureName"
            ),
            measured(
                "swap_used_gb", RESOURCES, swap.usedMb / 1024, " GB", limits.swapUsedGb, higherIsWorse = true,
                message = "${round(swap.usedMb / 1024)} GB of ${round(swap.totalMb / 1024)} GB swap used"
            )
        )
    }

    private fun disks(): List<HealthCheck> = volumes.map { (name, path) ->
        val existing = generateSequence(path.absoluteFile) { it.parentFile }.first { it.exists() }
        val usage = MacOsParsers.parseDf(exec("/bin/df", "-k", "-P", existing.path))
        val freePercent = usage.availableBytes * 100.0 / usage.totalBytes
        val freeGb = gb(usage.availableBytes)
        val status = worst(
            limits.diskFreePercent.evaluate(freePercent, higherIsWorse = false),
            limits.diskFreeGb.evaluate(freeGb, higherIsWorse = false)
        )
        HealthCheck(
            name = "disk_free:$name",
            group = RESOURCES,
            status = status,
            value = round(freePercent),
            threshold = listOfNotNull(
                limits.diskFreePercent.describe(higherIsWorse = false, unit = "%"),
                limits.diskFreeGb.describe(higherIsWorse = false, unit = " GB")
            ).joinToString("; "),
            message = "$freeGb GB (${round(freePercent)}%) free on ${usage.mountPoint} for ${path.path}",
            critical = limits.diskFreePercent.critical || limits.diskFreeGb.critical,
            unit = "%",
            details = mapOf("free_gb" to freeGb, "total_gb" to gb(usage.totalBytes), "mount_point" to usage.mountPoint, "path" to path.path)
        )
    }

    private fun thermal(): List<HealthCheck> {
        val thermal = MacOsParsers.parsePmsetTherm(exec("/usr/bin/pmset", "-g", "therm"))
        val throttled = thermal.thermalWarningLevel > 0 || thermal.performanceWarningLevel > 0 ||
            (thermal.cpuSpeedLimit ?: 100) < 100
        val state = if (throttled) "throttled" else "nominal"
        return listOf(
            HealthCheck(
                name = "thermal_state",
                group = RESOURCES,
                status = if (throttled) CheckStatus.DEGRADED else CheckStatus.OK,
                value = state,
                threshold = "degraded on any thermal or performance warning, or CPU speed limit < 100%",
                message = "thermal warning ${thermal.thermalWarningLevel}, performance warning ${thermal.performanceWarningLevel}" +
                    (thermal.cpuSpeedLimit?.let { ", CPU speed limit $it%" } ?: ""),
                details = mapOf(
                    "thermal_warning_level" to thermal.thermalWarningLevel,
                    "performance_warning_level" to thermal.performanceWarningLevel,
                    "cpu_speed_limit" to thermal.cpuSpeedLimit
                )
            )
        )
    }
    // endregion

    // region: device server process
    private fun process(): List<HealthCheck> {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val heapMax = if (heap.max > 0) heap.max else heap.committed
        val heapPercent = heap.used * 100.0 / heapMax
        val threads = ManagementFactory.getThreadMXBean().threadCount
        val (running, capacity) = simulatorUsage()
        val ready = isDeviceManagerReady()
        return listOf(
            info("uptime_seconds", PROCESS, runtime.uptime / 1000, "s", "device server up for ${Duration.ofMillis(runtime.uptime).toMinutes()} min"),
            info(
                "build", PROCESS, BuildInfo.version, null, "version ${BuildInfo.version}, commit ${BuildInfo.gitCommit}",
                details = mapOf("version" to BuildInfo.version, "git_commit" to BuildInfo.gitCommit, "java" to runtime.vmVersion)
            ),
            measured(
                "jvm_heap_used_percent", PROCESS, heapPercent, "%", limits.heapUsedPercent, higherIsWorse = true,
                message = "${mb(heap.used)} MB of ${mb(heapMax)} MB heap used",
                details = mapOf("used_mb" to mb(heap.used), "max_mb" to mb(heapMax))
            ),
            measured("jvm_threads", PROCESS, threads.toDouble(), "", limits.jvmThreads, higherIsWorse = true, message = "$threads live threads"),
            HealthCheck(
                name = "simulators_running",
                group = PROCESS,
                status = if (capacity > 0 && running > capacity) CheckStatus.DEGRADED else CheckStatus.OK,
                value = running,
                threshold = "degraded > capacity ($capacity)",
                message = "$running of $capacity simulators in use",
                details = mapOf("capacity" to capacity)
            ),
            HealthCheck(
                name = "device_manager_ready",
                group = PROCESS,
                status = if (ready) CheckStatus.OK else CheckStatus.FAILING,
                value = ready,
                threshold = "failing when not ready",
                message = if (ready) "device manager is ready" else "device manager is still setting up",
                critical = true
            )
        ) + simulatorBoots()
    }

    private fun processResources(): List<HealthCheck> {
        val stats = MacOsParsers.parseProcessStats(exec("/bin/ps", "-o", "rss=,%cpu=", "-p", pid.toString()))
        val memoryMb = stats.rssKb / 1024.0
        return listOf(
            measured(
                "process_memory_mb", PROCESS, memoryMb, " MB", limits.processMemoryMb, higherIsWorse = true,
                message = "device server uses ${memoryMb.toLong()} MB of memory (resident)"
            ),
            measured(
                "process_cpu_percent", PROCESS, stats.cpuPercent, "%", limits.processCpuPercent, higherIsWorse = true,
                message = "device server uses ${stats.cpuPercent}% CPU, 100% being one core"
            )
        )
    }

    private fun simulatorBoots(): List<HealthCheck> {
        val boots = bootStats.snapshot()
        return listOfNotNull(
            info("simulator_boots_succeeded_total", PROCESS, boots.succeeded, null, "${boots.succeeded} simulator boots succeeded since start"),
            info("simulator_boots_failed_total", PROCESS, boots.failed, null, "${boots.failed} simulator boots failed since start"),
            measured(
                "simulator_boot_failures", PROCESS, boots.recentFailures.toDouble(), "", limits.simulatorBootFailures, higherIsWorse = true,
                message = "${boots.recentFailures} of the last ${boots.recentBoots} simulator boots failed"
            ).copy(value = boots.recentFailures),
            boots.averageSeconds?.let {
                measured(
                    "simulator_boot_seconds", PROCESS, it, " s", limits.simulatorBootSeconds, higherIsWorse = true,
                    message = "successful boots among the last ${boots.recentBoots} took ${round(it)} s on average"
                )
            }
        )
    }

    private fun simctlList(): List<HealthCheck> {
        val started = System.nanoTime()
        val result = runner.run(listOf("/usr/bin/xcrun", "simctl", "list", "devices", "--json"), SIMCTL_TIMEOUT)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        if (result.exitCode != 0) {
            return listOf(
                HealthCheck(
                    "simctl_list_ms", PROCESS, CheckStatus.FAILING, round(elapsedMs),
                    limits.simctlListMs.describe(higherIsWorse = true, unit = " ms"),
                    "simctl list exited with ${result.exitCode}: ${result.output.take(200).trim()}", critical = true, unit = "ms"
                )
            )
        }
        return listOf(
            measured(
                "simctl_list_ms", PROCESS, elapsedMs, " ms", limits.simctlListMs, higherIsWorse = true,
                message = "xcrun simctl list devices took ${elapsedMs.toLong()} ms"
            )
        )
    }

    private fun xcode(): List<HealthCheck> {
        val output = exec("/usr/bin/xcodebuild", "-version")
        val version = XcodeVersion.fromXcodeBuildOutput(output)
        val build = Regex("Build version (\\S+)").find(output)?.groupValues?.get(1)
        val required = XcodeVersion.REQUIRED_XCODE_VERSION
        val expected = config.expectedXcodeVersion
        val status = when {
            version < required -> CheckStatus.FAILING
            expected != null && version.toString() != expected -> CheckStatus.DEGRADED
            else -> CheckStatus.OK
        }
        return listOf(
            HealthCheck(
                name = "xcode_version",
                group = PROCESS,
                status = status,
                value = version.toString(),
                threshold = "failing < $required" + (expected?.let { ", degraded != $it" } ?: ""),
                message = "Xcode $version ($build), required $required or newer" + (expected?.let { ", fleet expects $it" } ?: ""),
                critical = true,
                details = mapOf("build" to build, "required" to required.toString(), "expected" to expected)
            )
        )
    }
    // endregion

    // region: network
    private fun network(): List<HealthCheck> {
        val route = MacOsParsers.parseDefaultRoute(exec("/sbin/route", "-n", "get", "default"))
        val ports = MacOsParsers.parseHardwarePorts(exec("/usr/sbin/networksetup", "-listallhardwareports"))
        val info = MacOsParsers.parseIfconfig(exec("/sbin/ifconfig", route.interfaceName))
        // A VLAN interface reports no media itself; its parent's link is what the cable negotiated.
        val physical = info.vlanParent ?: route.interfaceName
        val link = if (info.vlanParent != null) MacOsParsers.parseIfconfig(exec("/sbin/ifconfig", physical)) else info
        val port = ports[physical] ?: physical
        val wifi = port.contains("Wi-Fi", ignoreCase = true) || port.contains("AirPort", ignoreCase = true)

        val checks = mutableListOf(
            HealthCheck(
                name = "link_type",
                group = NETWORK,
                status = if (wifi) CheckStatus.DEGRADED else CheckStatus.OK,
                value = if (wifi) "wifi" else "ethernet",
                threshold = "degraded on Wi-Fi",
                message = "default route via ${route.interfaceName} ($port)",
                details = mapOf("interface" to route.interfaceName, "hardware_port" to port)
            ),
            linkSpeed(link, wifi),
            HealthCheck(
                name = "link_duplex",
                group = NETWORK,
                status = if (link.duplex == "half") CheckStatus.FAILING else CheckStatus.OK,
                value = link.duplex,
                threshold = "failing on half-duplex",
                message = "media: ${link.media ?: "none"}, status: ${if (link.active == false) "inactive" else "active"}",
                critical = true
            ),
            address(route, info)
        )
        route.gateway?.let { checks += gatewayPing(it) }
        return checks
    }

    private fun linkSpeed(link: MacOsParsers.InterfaceInfo, wifi: Boolean): HealthCheck {
        val speed = link.speedMbit
        if (link.active == false) {
            return HealthCheck(
                "link_speed_mbit", NETWORK, CheckStatus.FAILING, 0, limits.linkSpeedMbit.describe(higherIsWorse = false, unit = " Mbit/s"),
                "link is inactive", critical = true, unit = "Mbit/s"
            )
        }
        if (speed == null) {
            // Wi-Fi reports no negotiated rate in ifconfig; that case is already degraded by link_type.
            return HealthCheck(
                "link_speed_mbit", NETWORK, if (wifi) CheckStatus.OK else CheckStatus.UNKNOWN, null,
                limits.linkSpeedMbit.describe(higherIsWorse = false, unit = " Mbit/s"),
                "no negotiated speed in media \"${link.media}\"", critical = !wifi, unit = "Mbit/s"
            )
        }
        return measured(
            "link_speed_mbit", NETWORK, speed.toDouble(), " Mbit/s", limits.linkSpeedMbit, higherIsWorse = false,
            message = "negotiated $speed Mbit/s" + (link.duplex?.let { " $it-duplex" } ?: "")
        ).copy(value = speed)
    }

    private fun address(route: MacOsParsers.DefaultRoute, info: MacOsParsers.InterfaceInfo): HealthCheck {
        val subnet = info.subnet
        val expected = config.expectedSubnets
        val inExpected = info.ipv4 != null && expected.any { cidrContains(it, info.ipv4) }
        val status = when {
            info.ipv4 == null -> CheckStatus.FAILING
            expected.isNotEmpty() && !inExpected -> CheckStatus.FAILING
            else -> CheckStatus.OK
        }
        return HealthCheck(
            name = "network_address",
            group = NETWORK,
            status = status,
            value = info.ipv4,
            threshold = if (expected.isEmpty()) "failing without IPv4" else "failing outside ${expected.joinToString()}",
            message = "${info.ipv4 ?: "no IPv4"} in ${subnet ?: "unknown subnet"} via gateway ${route.gateway ?: "none"}" +
                (info.vlanTag?.let { ", VLAN $it on ${info.vlanParent}" } ?: ""),
            critical = true,
            details = mapOf("subnet" to subnet, "gateway" to route.gateway, "vlan" to info.vlanTag, "interface" to route.interfaceName)
        )
    }

    private fun gatewayPing(gateway: String): List<HealthCheck> {
        // ping exits non-zero on packet loss, which is a result here, not a failed probe.
        val ping = MacOsParsers.parsePing(runner.run(listOf("/sbin/ping", "-c", "3", "-i", "0.2", "-t", "3", gateway), COMMAND_TIMEOUT).output)
        val loss = measured(
            "gateway_packet_loss_percent", NETWORK, ping.lossPercent, "%", limits.gatewayPacketLossPercent, higherIsWorse = true,
            message = "${ping.received} of ${ping.transmitted} pings to gateway $gateway answered"
        )
        val rtt = ping.avgMs?.let {
            measured("gateway_ping_ms", NETWORK, it, " ms", limits.gatewayPingMs, higherIsWorse = true, message = "average round trip to gateway $gateway")
        }
        return listOfNotNull(loss, rtt)
    }

    private fun endpoint(url: String): List<HealthCheck> {
        val uri = URI(url)
        val host = uri.host
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
        val checks = mutableListOf<HealthCheck>()

        val dig = MacOsParsers.parseDig(exec("/usr/bin/dig", "+tries=1", "+time=2", host))
        checks += if (dig.status != "NOERROR" || dig.queryTimeMs == null) {
            HealthCheck(
                "dns_resolve_ms:$host", NETWORK, CheckStatus.FAILING, dig.queryTimeMs,
                limits.dnsResolveMs.describe(higherIsWorse = true, unit = " ms"), "DNS answered ${dig.status} for $host",
                critical = true, unit = "ms"
            )
        } else {
            measured("dns_resolve_ms:$host", NETWORK, dig.queryTimeMs.toDouble(), " ms", limits.dnsResolveMs, higherIsWorse = true,
                message = "resolved $host in ${dig.queryTimeMs} ms")
        }

        // Network errors here are measurements of the path, so they fail the check instead of making it unknown.
        checks += timed("tcp_connect_ms:$host", limits.tcpConnectMs, "TCP connect to $host:$port") {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getByName(host), port), NETWORK_TIMEOUT.toMillis().toInt()) }
        }
        checks += timed("http_ttfb_ms:$host", limits.httpTtfbMs, "HTTP first byte from $url") {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = NETWORK_TIMEOUT.toMillis().toInt()
                connection.readTimeout = NETWORK_TIMEOUT.toMillis().toInt()
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
        return checks
    }

    private fun timed(name: String, limit: Limit, what: String, action: () -> Unit): HealthCheck {
        val started = System.nanoTime()
        return try {
            action()
            val ms = (System.nanoTime() - started) / 1_000_000.0
            measured(name, NETWORK, ms, " ms", limit, higherIsWorse = true, message = "$what took ${ms.toLong()} ms")
        } catch (e: IOException) {
            HealthCheck(
                name, NETWORK, CheckStatus.FAILING, null, limit.describe(higherIsWorse = true, unit = " ms"),
                "$what failed: ${e.javaClass.simpleName}: ${e.message}", critical = true, unit = "ms"
            )
        }
    }
    // endregion

    // region: time
    private fun ntp(): List<HealthCheck> {
        val offset = MacOsParsers.parseSntpOffset(exec("/usr/bin/sntp", "-t", "2", config.ntpServer))
        return listOf(
            measured(
                "ntp_offset_seconds", TIME, abs(offset), " s", limits.ntpOffsetSeconds, higherIsWorse = true,
                message = "system clock is ${offset} s off ${config.ntpServer}"
            ).copy(value = offset)
        )
    }
    // endregion

    private fun exec(vararg command: String): String {
        val result = runner.run(command.toList(), COMMAND_TIMEOUT)
        check(result.exitCode == 0) { "${command.joinToString(" ")} exited with ${result.exitCode}: ${result.output.take(200).trim()}" }
        return result.output
    }

    private fun measured(
        name: String, group: String, value: Double, unit: String, limit: Limit, higherIsWorse: Boolean,
        message: String, details: Map<String, Any?> = emptyMap()
    ) = HealthCheck(
        name = name,
        group = group,
        status = limit.evaluate(value, higherIsWorse),
        value = round(value),
        threshold = limit.describe(higherIsWorse, unit),
        message = message,
        critical = limit.critical,
        unit = unit.trim().ifEmpty { null },
        details = details
    )

    private fun info(name: String, group: String, value: Any?, unit: String?, message: String, details: Map<String, Any?> = emptyMap()) =
        HealthCheck(name, group, CheckStatus.OK, value, null, message, unit = unit, details = details)

    private fun worst(vararg statuses: CheckStatus) = statuses.maxBy { it.metricValue }

    private fun round(value: Double) = Math.round(value * 100) / 100.0
    private fun gb(bytes: Long) = round(bytes / 1_073_741_824.0)
    private fun mb(bytes: Long) = bytes / 1_048_576

    companion object {
        /** Longest probe (simctl) plus margin; the refresh waits this long before marking a probe unknown. */
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(30)

        const val RESOURCES = "resources"
        const val PROCESS = "process"
        const val NETWORK = "network"
        const val TIME = "time"

        private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val SIMCTL_TIMEOUT: Duration = Duration.ofSeconds(25)
        private val NETWORK_TIMEOUT: Duration = Duration.ofSeconds(5)

        fun cidrContains(cidr: String, ip: String): Boolean {
            val network = cidr.substringBefore("/")
            val mask = MacOsParsers.prefixMask(cidr.substringAfter("/", "32").toInt())
            return (MacOsParsers.ipv4ToLong(network) and mask) == (MacOsParsers.ipv4ToLong(ip) and mask)
        }
    }
}
