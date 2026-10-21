package com.badoo.automation.deviceserver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PosixTest {
    @Test
    fun killDeliversSigintToChild() {
        val child = ProcessBuilder("/bin/sh", "-c", "trap 'exit 42' INT; echo ready; while :; do sleep 0.1; done")
            .redirectErrorStream(true)
            .start()
        try {
            child.inputStream.bufferedReader().readLine()

            assertEquals(0, Posix.kill(child.pid().toInt(), Posix.SIGINT))

            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals(42, child.exitValue())
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun killReportsFailureForMissingProcess() {
        assertEquals(-1, Posix.kill(Int.MAX_VALUE, 0))
    }
}
