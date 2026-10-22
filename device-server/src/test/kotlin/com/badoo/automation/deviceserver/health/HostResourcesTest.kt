package com.badoo.automation.deviceserver.health

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostResourcesTest {
    private fun sample(ticks: MacHostStats.CpuTicks, free: Long = 100, inactive: Long = 150) = MacHostStats.Sample(
        memoryBytes = 1000L * 16384,
        pageSizeBytes = 16384,
        freePages = free,
        inactivePages = inactive,
        pressureLevel = 2,
        swapUsedBytes = 3L * 1_073_741_824,
        load = listOf(4.256, 3.5, 2.0),
        cpuTicks = ticks
    )

    @Test
    fun cpuIsTheShareOfTicksSinceThePreviousCallAndNullOnTheFirst() {
        val samples = ArrayDeque(
            listOf(
                sample(MacHostStats.CpuTicks(user = 1000, system = 500, idle = 8000, nice = 0)),
                sample(MacHostStats.CpuTicks(user = 1150, system = 550, idle = 8250, nice = 50))
            )
        )
        val sampler = HostResourcesSampler(sample = { samples.removeFirst() }, cores = 10)

        val first = sampler.read()
        assertNull(first.cpuUserPercent)
        assertNull(first.cpuIdlePercent)
        assertEquals(75.0, first.memoryUsedPercent, 0.0)
        assertEquals("warn", first.memoryPressure)
        assertEquals(3.0, first.swapUsedGb, 0.0)
        assertEquals(4.3, first.load1m, 0.0)
        assertEquals(10, first.cores)

        val second = sampler.read()
        // 150 user + 50 nice, 50 system, 250 idle of 500 ticks
        assertEquals(40.0, second.cpuUserPercent!!, 0.0)
        assertEquals(10.0, second.cpuSystemPercent!!, 0.0)
        assertEquals(50.0, second.cpuIdlePercent!!, 0.0)
    }

    @Test
    fun callsWithinOneTickReturnNullAndKeepTheOlderBaseline() {
        val samples = ArrayDeque(
            listOf(
                sample(MacHostStats.CpuTicks(user = 100, system = 0, idle = 100, nice = 0)),
                sample(MacHostStats.CpuTicks(user = 100, system = 0, idle = 100, nice = 0)),
                sample(MacHostStats.CpuTicks(user = 130, system = 0, idle = 170, nice = 0))
            )
        )
        val sampler = HostResourcesSampler(sample = { samples.removeFirst() }, cores = 1)
        sampler.read()

        assertNull(sampler.read().cpuUserPercent)
        assertEquals(30.0, sampler.read().cpuUserPercent!!, 0.0)
    }

    @Test
    fun cpuDeltasSurviveTheThirtyTwoBitWrap() {
        val beforeWrap = (1L shl 32) - 100
        val samples = ArrayDeque(
            listOf(
                sample(MacHostStats.CpuTicks(user = beforeWrap, system = 0, idle = 0, nice = 0)),
                sample(MacHostStats.CpuTicks(user = 100, system = 0, idle = 200, nice = 0))
            )
        )
        val sampler = HostResourcesSampler(sample = { samples.removeFirst() }, cores = 1)
        sampler.read()

        val resources = sampler.read()

        assertEquals(50.0, resources.cpuUserPercent!!, 0.0)
        assertEquals(50.0, resources.cpuIdlePercent!!, 0.0)
    }

    @Test
    fun jsonUsesTheSnakeCaseNamesAndKeepsNullCpu() {
        val json = jacksonObjectMapper().writeValueAsString(
            HostResources(75.0, "normal", 0.5, 1.0, 2.0, 3.0, 10, null, null, null)
        )

        assertEquals(
            """{"memory_used_percent":75.0,"memory_pressure":"normal","swap_used_gb":0.5,"load_1m":1.0,"load_5m":2.0,""" +
                """"load_15m":3.0,"cores":10,"cpu_user_percent":null,"cpu_system_percent":null,"cpu_idle_percent":null}""",
            json
        )
    }

    @Test
    fun nativeReadingsMatchTheSystemToolsAndTakeMicroseconds() {
        val sample = MacHostStats.sample()

        assertEquals(command("/usr/sbin/sysctl", "-n", "hw.memsize").toLong(), sample.memoryBytes)
        assertEquals(command("/usr/bin/pagesize").toLong(), sample.pageSizeBytes)
        assertTrue(sample.freePages + sample.inactivePages in 1 until sample.memoryBytes / sample.pageSizeBytes)
        assertTrue(sample.pressureLevel in listOf(1, 2, 4))
        assertEquals(3, sample.load.size)
        assertTrue(sample.load.all { it >= 0 })
        assertTrue(sample.cpuTicks.run { user + system + idle } > 0)

        val sampler = HostResourcesSampler()
        sampler.read()
        val started = System.nanoTime()
        repeat(200) { sampler.read() }
        val averageMs = (System.nanoTime() - started) / 200 / 1_000_000.0
        assertTrue("average $averageMs ms", averageMs < 5)

        // The kernel does not always account CPU ticks in real time, so give it up to 3 s
        val resources = generateSequence { Thread.sleep(100); sampler.read() }.take(30).firstOrNull { it.cpuUserPercent != null }
            ?: error("CPU ticks did not advance in 3 s")
        assertTrue(resources.memoryUsedPercent in 0.0..100.0)
        val cpu = listOf(resources.cpuUserPercent!!, resources.cpuSystemPercent!!, resources.cpuIdlePercent!!)
        assertTrue("$cpu", cpu.sum() in 99.0..101.0 || cpu.sum() == 0.0)
    }

    private fun command(vararg command: String): String =
        ProcessBuilder(*command).start().inputStream.bufferedReader().readText().trim()
}
