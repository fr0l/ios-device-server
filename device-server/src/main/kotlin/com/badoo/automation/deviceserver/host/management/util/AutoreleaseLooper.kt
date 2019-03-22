package com.badoo.automation.deviceserver.host.management.util

import com.badoo.automation.deviceserver.host.management.IAutoreleaseLooper
import com.badoo.automation.deviceserver.host.management.IDeviceManager
import com.badoo.automation.deviceserver.util.executeWithTimeout
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.max

class AutoreleaseLooper : IAutoreleaseLooper {
    private val autoreleaseThreadPool = newFixedThreadPoolContext(1, "AutoreleaseLoop")
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

    override fun autoreleaseLoop(deviceManager: IDeviceManager) {
        CoroutineScope(autoreleaseThreadPool).launch {
            while (isActive) {
                try {
                    autoRelease(deviceManager)
                } catch (t: Throwable) {
                    logger.warn("Autorelease thread ignored: $t")
                }

                val seconds = max(1, (deviceManager.nextReleaseAtSeconds() - currentTimeSeconds()))
                delay(Duration.ofSeconds(seconds).toMillis())
            }
        }
    }

    private fun currentTimeSeconds() = System.currentTimeMillis() / 1000

    private fun autoRelease(deviceManager: IDeviceManager) {
        val jobs = deviceManager.readyForRelease().map { deviceRef ->
            CoroutineScope(Dispatchers.Default).launch {
                val message = "Failed to release device $deviceRef"
                try {
                    executeWithTimeout(Duration.ofMinutes(2), message) {
                        if (isActive) {
                            deviceManager.deleteReleaseDevice(deviceRef, "autoRelease")
                        }
                    }
                } catch (e: RuntimeException) {
                    logger.error(message, e)
                }
            }
        }

        runBlocking {
            jobs.forEach { it.join() }
        }
    }
}