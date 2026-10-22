package com.badoo.automation.deviceserver.health

import java.time.Duration

/** Simulator boot outcomes since the server started, plus the last [WINDOW] boots for the health verdict. */
class BootStats {
    data class Snapshot(
        val succeeded: Long,
        val failed: Long,
        val recentFailures: Int,
        val recentBoots: Int,
        val averageSeconds: Double?
    )

    private class Boot(val succeeded: Boolean, val duration: Duration)

    private val recent = ArrayDeque<Boot>()
    private var succeeded = 0L
    private var failed = 0L

    @Synchronized
    fun recordSuccess(duration: Duration) {
        succeeded++
        record(Boot(succeeded = true, duration = duration))
    }

    @Synchronized
    fun recordFailure(duration: Duration) {
        failed++
        record(Boot(succeeded = false, duration = duration))
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val successful = recent.filter { it.succeeded }
        return Snapshot(
            succeeded = succeeded,
            failed = failed,
            recentFailures = recent.count { !it.succeeded },
            recentBoots = recent.size,
            averageSeconds = successful.takeIf { it.isNotEmpty() }?.map { it.duration.toMillis() / 1000.0 }?.average()
        )
    }

    private fun record(boot: Boot) {
        recent.addLast(boot)
        if (recent.size > WINDOW) recent.removeFirst()
    }

    companion object {
        const val WINDOW = 10

        /** Recorded by every simulator of this server. */
        val simulators = BootStats()
    }
}
