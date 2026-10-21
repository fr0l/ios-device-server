package com.badoo.automation.deviceserver.health

/** Parsers for macOS system utility output; each throws [IllegalArgumentException] on output it does not recognise. */
object MacOsParsers {
    data class LoadAverage(val one: Double, val five: Double, val fifteen: Double)
    data class VmStat(val pageSize: Long, val pages: Map<String, Long>) {
        fun bytes(key: String): Long = (pages[key] ?: 0L) * pageSize
    }
    data class SwapUsage(val totalMb: Double, val usedMb: Double)
    data class DiskUsage(val mountPoint: String, val totalBytes: Long, val availableBytes: Long)
    data class Thermal(val thermalWarningLevel: Int, val performanceWarningLevel: Int, val cpuSpeedLimit: Int?)
    data class DefaultRoute(val gateway: String?, val interfaceName: String)
    data class InterfaceInfo(
        val ipv4: String?,
        val prefixLength: Int?,
        val active: Boolean?,
        val media: String?,
        val speedMbit: Int?,
        val duplex: String?,
        val vlanTag: Int?,
        val vlanParent: String?
    ) {
        val subnet: String?
            get() {
                if (ipv4 == null || prefixLength == null) return null
                val network = ipv4ToLong(ipv4) and prefixMask(prefixLength)
                return (3 downTo 0).joinToString(".") { ((network shr (it * 8)) and 0xFF).toString() } + "/$prefixLength"
            }
    }

    fun ipv4ToLong(address: String): Long = address.split(".").fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }

    fun prefixMask(prefixLength: Int): Long = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
    data class PingResult(val transmitted: Int, val received: Int, val lossPercent: Double, val avgMs: Double?)
    data class DigResult(val status: String, val queryTimeMs: Long?)

    // sysctl -n vm.loadavg: "{ 5.77 6.19 6.85 }"
    fun parseLoadAverage(output: String): LoadAverage {
        val values = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(output).map { it.value.replace(',', '.').toDouble() }.toList()
        require(values.size >= 3) { "Unexpected load average: $output" }
        return LoadAverage(values[0], values[1], values[2])
    }

    fun parseVmStat(output: String): VmStat {
        val pageSize = Regex("page size of (\\d+) bytes").find(output)?.groupValues?.get(1)?.toLong()
            ?: throw IllegalArgumentException("vm_stat output has no page size")
        val pages = output.lines().mapNotNull { line ->
            Regex("^\"?([^:\"]+)\"?:\\s+(\\d+)\\.?$").find(line.trim())?.let { it.groupValues[1].trim() to it.groupValues[2].toLong() }
        }.toMap()
        require(pages.containsKey("Pages free")) { "vm_stat output has no \"Pages free\"" }
        return VmStat(pageSize, pages)
    }

    // sysctl vm.swapusage: "total = 2048.00M  used = 866.81M  free = 1181.19M  (encrypted)"
    fun parseSwapUsage(output: String): SwapUsage {
        val values = Regex("(total|used) = ([0-9.]+)([KMG])").findAll(output).associate { match ->
            val (key, amount, unit) = match.destructured
            key to amount.toDouble() * when (unit) { "K" -> 1.0 / 1024; "G" -> 1024.0; else -> 1.0 }
        }
        return SwapUsage(
            totalMb = values["total"] ?: throw IllegalArgumentException("Unexpected swap usage: $output"),
            usedMb = values["used"] ?: throw IllegalArgumentException("Unexpected swap usage: $output")
        )
    }

    // df -k -P <path>
    fun parseDf(output: String): DiskUsage {
        val line = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("Filesystem") }
            ?: throw IllegalArgumentException("Unexpected df output: $output")
        val fields = line.split(Regex("\\s+"))
        require(fields.size >= 6 && fields[5].startsWith("/")) { "Unexpected df line: $line" }
        return DiskUsage(
            mountPoint = fields.drop(5).joinToString(" "),
            totalBytes = fields[1].toLong() * 1024,
            availableBytes = fields[3].toLong() * 1024
        )
    }

    // pmset -g therm: Apple silicon prints "Note: No ... level has been recorded" until a warning happens,
    // Intel Macs also print "CPU_Speed_Limit = 100".
    fun parsePmsetTherm(output: String): Thermal {
        var thermal = 0
        var performance = 0
        var speedLimit: Int? = null
        var recognised = false
        output.lines().map { it.trim() }.forEach { line ->
            val level = Regex("level set to (\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toInt()
            when {
                line.contains("thermal warning level", ignoreCase = true) -> {
                    recognised = true
                    thermal = level ?: 0
                }
                line.contains("performance warning level", ignoreCase = true) -> {
                    recognised = true
                    performance = level ?: 0
                }
                line.startsWith("CPU_Speed_Limit") -> {
                    recognised = true
                    speedLimit = line.substringAfter("=").trim().toIntOrNull()
                }
                line.contains("CPU power status", ignoreCase = true) -> recognised = true
            }
        }
        require(recognised) { "Unexpected pmset output: $output" }
        return Thermal(thermal, performance, speedLimit)
    }

    // route -n get default
    fun parseDefaultRoute(output: String): DefaultRoute {
        fun field(name: String) = Regex("^\\s*$name:\\s*(\\S+)", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
        val interfaceName = field("interface") ?: throw IllegalArgumentException("No default route: $output")
        return DefaultRoute(field("gateway"), interfaceName)
    }

    // networksetup -listallhardwareports: device -> hardware port, e.g. "en0" -> "Wi-Fi"
    fun parseHardwarePorts(output: String): Map<String, String> {
        val ports = mutableMapOf<String, String>()
        var port: String? = null
        output.lines().forEach { line ->
            when {
                line.startsWith("Hardware Port:") -> port = line.substringAfter(":").trim()
                line.startsWith("Device:") -> port?.let { ports[line.substringAfter(":").trim()] = it }
            }
        }
        return ports
    }

    fun parseIfconfig(output: String): InterfaceInfo {
        require(output.contains("flags=")) { "Unexpected ifconfig output: $output" }
        val inet = Regex("^\\s*inet (\\d+\\.\\d+\\.\\d+\\.\\d+) netmask (0x[0-9a-fA-F]+)", RegexOption.MULTILINE).find(output)
        val media = Regex("^\\s*media: (.+)$", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)?.trim()
        val activeMedia = media?.let { Regex("\\((.+)\\)").find(it)?.groupValues?.get(1) } ?: media
        val speed = activeMedia?.let { Regex("(\\d+(?:\\.\\d+)?)\\s*(G)?base", RegexOption.IGNORE_CASE).find(it) }?.let {
            val amount = it.groupValues[1].toDouble()
            (if (it.groupValues[2].isNotEmpty()) amount * 1000 else amount).toInt()
        }
        val duplex = activeMedia?.let { Regex("(full|half)-duplex").find(it)?.groupValues?.get(1) }
        val status = Regex("^\\s*status: (\\S+)", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
        val vlan = Regex("vlan: (\\d+) parent interface: (\\S+)").find(output)
        return InterfaceInfo(
            ipv4 = inet?.groupValues?.get(1),
            prefixLength = inet?.groupValues?.get(2)?.removePrefix("0x")?.toLong(16)?.let { java.lang.Long.bitCount(it) },
            active = status?.let { it == "active" },
            media = media,
            speedMbit = speed,
            duplex = duplex,
            vlanTag = vlan?.groupValues?.get(1)?.toInt(),
            vlanParent = vlan?.groupValues?.get(2)
        )
    }

    fun parsePing(output: String): PingResult {
        val counts = Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received, ([0-9.]+)% packet loss").find(output)
            ?: throw IllegalArgumentException("Unexpected ping output: $output")
        val avg = Regex("= [0-9.]+/([0-9.]+)/[0-9.]+/[0-9.]+ ms").find(output)?.groupValues?.get(1)?.toDouble()
        return PingResult(counts.groupValues[1].toInt(), counts.groupValues[2].toInt(), counts.groupValues[3].toDouble(), avg)
    }

    fun parseDig(output: String): DigResult {
        val status = Regex("status: ([A-Z]+)").find(output)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Unexpected dig output: $output")
        val time = Regex("Query time: (\\d+) msec").find(output)?.groupValues?.get(1)?.toLong()
        return DigResult(status, time)
    }

    // sntp <server>: "+0.048756 +/- 0.021472 time.apple.com 17.253.28.45"; returns the offset in seconds
    fun parseSntpOffset(output: String): Double =
        Regex("^([+-]?\\d+(?:\\.\\d+)?) \\+/- ", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)?.toDouble()
            ?: throw IllegalArgumentException("Unexpected sntp output: $output")
}
