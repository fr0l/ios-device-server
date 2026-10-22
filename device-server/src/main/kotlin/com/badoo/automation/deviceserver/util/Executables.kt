package com.badoo.automation.deviceserver.util

import java.io.File

/** Finds command-line tools in PATH, then in the Homebrew prefixes, which launchd and IDEs often leave out of PATH. */
object Executables {
    private val HOMEBREW_BIN_DIRS = listOf("/opt/homebrew/bin", "/usr/local/bin")

    const val IPROXY_INSTALL = "brew install libusbmuxd"
    const val SOCAT_INSTALL = "brew install socat"
    const val LIBIMOBILEDEVICE_INSTALL = "brew install libimobiledevice"
    const val FFMPEG_INSTALL = "brew install ffmpeg"

    fun find(name: String, path: String? = System.getenv("PATH"), fallbackDirs: List<String> = HOMEBREW_BIN_DIRS): File? =
        (path.orEmpty().split(File.pathSeparator) + fallbackDirs)
            .filter { it.isNotEmpty() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }

    /** The absolute path of [name]; throws with [installHint] when it is not installed. */
    fun require(name: String, installHint: String): String =
        find(name)?.absolutePath
            ?: throw IllegalStateException("$name is not installed: not found in PATH, /opt/homebrew/bin or /usr/local/bin. Install it with: $installHint")
}
