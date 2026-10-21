package com.badoo.automation.deviceserver.util

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/** libc calls made in-process, so signalling a child does not spawn `/bin/kill` on a loaded host. */
object Posix {
    const val SIGINT = 2

    private val killHandle: MethodHandle by lazy {
        val linker = Linker.nativeLinker()
        linker.downcallHandle(
            linker.defaultLookup().find("kill").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)
        )
    }

    /** `kill(2)`: returns 0 on success, -1 on failure. */
    fun kill(pid: Int, signal: Int): Int = killHandle.invokeExact(pid, signal) as Int
}
