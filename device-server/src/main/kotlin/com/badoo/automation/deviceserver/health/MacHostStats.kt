package com.badoo.automation.deviceserver.health

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * Host memory, swap, load and CPU ticks read in-process through `sysctlbyname(3)` and Mach host statistics, so a
 * sample takes microseconds instead of spawning `vm_stat`, `sysctl` or `top`.
 */
object MacHostStats {
    data class Sample(
        val memoryBytes: Long,
        val pageSizeBytes: Long,
        val freePages: Long,
        val inactivePages: Long,
        val pressureLevel: Int,
        val swapUsedBytes: Long,
        val load: List<Double>,
        val cpuTicks: CpuTicks
    )

    /** Cumulative ticks of all cores since boot; they wrap at 2^32. */
    data class CpuTicks(val user: Long, val system: Long, val idle: Long, val nice: Long)

    private const val HOST_CPU_LOAD_INFO = 3
    private const val HOST_CPU_LOAD_INFO_COUNT = 4
    private const val HOST_VM_INFO64 = 4
    private const val HOST_VM_INFO64_COUNT = 38

    private val linker = Linker.nativeLinker()

    private val sysctlbyname = downcall("sysctlbyname", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
    private val hostStatistics = downcall("host_statistics", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
    private val hostStatistics64 = downcall("host_statistics64", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))

    // Each mach_host_self() call adds a send right to the port, so it is taken once
    private val host: Int by lazy { downcall("mach_host_self", FunctionDescriptor.of(JAVA_INT)).value.invokeExact() as Int }

    fun sample(): Sample = Arena.ofConfined().use { arena ->
        val vm = arena.hostStatistics(hostStatistics64.value, HOST_VM_INFO64, HOST_VM_INFO64_COUNT)
        val cpu = arena.hostStatistics(hostStatistics.value, HOST_CPU_LOAD_INFO, HOST_CPU_LOAD_INFO_COUNT)
        val loadavg = arena.sysctl("vm.loadavg", 24)
        val swap = arena.sysctl("vm.swapusage", 32)
        val fixedPointScale = loadavg.get(JAVA_LONG, 16).toDouble()
        Sample(
            memoryBytes = arena.sysctlNumber("hw.memsize"),
            pageSizeBytes = arena.sysctlNumber("hw.pagesize"),
            // vm_statistics64: free_count at 0, inactive_count at 8 (natural_t)
            freePages = vm.unsignedInt(0),
            inactivePages = vm.unsignedInt(8),
            pressureLevel = arena.sysctlNumber("kern.memorystatus_vm_pressure_level").toInt(),
            // xsw_usage: xsu_total, xsu_avail, xsu_used (u_int64)
            swapUsedBytes = swap.get(JAVA_LONG, 16),
            // struct loadavg: fixpt_t ldavg[3], long fscale
            load = (0 until 3).map { loadavg.unsignedInt(it * 4L) / fixedPointScale },
            // host_cpu_load_info: cpu_ticks[CPU_STATE_USER, SYSTEM, IDLE, NICE]
            cpuTicks = CpuTicks(cpu.unsignedInt(0), cpu.unsignedInt(4), cpu.unsignedInt(8), cpu.unsignedInt(12))
        )
    }

    private fun downcall(name: String, descriptor: FunctionDescriptor): Lazy<MethodHandle> = lazy {
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor)
    }

    private fun Arena.hostStatistics(handle: MethodHandle, flavor: Int, count: Int): MemorySegment {
        val info = allocate(count * 4L, 8)
        val infoCount = allocate(JAVA_INT).apply { set(JAVA_INT, 0, count) }
        val result = handle.invokeExact(host, flavor, info, infoCount) as Int
        check(result == 0) { "host_statistics flavor $flavor failed with kern_return_t $result" }
        return info
    }

    private fun Arena.sysctl(name: String, size: Long): MemorySegment {
        val value = allocate(size, 8)
        val length = allocate(JAVA_LONG).apply { set(JAVA_LONG, 0, size) }
        val result = sysctlbyname.value.invokeExact(allocateFrom(name), value, length, MemorySegment.NULL, 0L) as Int
        check(result == 0) { "sysctlbyname($name) failed" }
        return value.asSlice(0, length.get(JAVA_LONG, 0))
    }

    /** An integer sysctl that may be 32 or 64 bits wide. */
    private fun Arena.sysctlNumber(name: String): Long {
        val value = sysctl(name, 8)
        return if (value.byteSize() == 4L) value.get(JAVA_INT, 0).toLong() else value.get(JAVA_LONG, 0)
    }

    private fun MemorySegment.unsignedInt(offset: Long): Long = Integer.toUnsignedLong(get(JAVA_INT, offset))
}
