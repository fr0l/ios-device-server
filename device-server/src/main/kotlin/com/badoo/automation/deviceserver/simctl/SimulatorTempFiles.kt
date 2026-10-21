package com.badoo.automation.deviceserver.simctl

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.util.deleteRecursivelyIfExist
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.io.File

/**
 * Per-simulator files left in temp dirs by WebDriverAgent runs, OS log capture and `launchd_sim`;
 * `simctl delete` removes none of them.
 */
class SimulatorTempFiles(
    private val tmpDir: File = ApplicationConfiguration().tempFolder,
    private val varTmpDir: File = File("/private/var/tmp")
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MarkerFactory.getMarker(javaClass.simpleName)

    fun delete(udid: UDID) {
        entries().filter { it.second == udid }.forEach { it.first.deleteRecursivelyIfExist(logger, logMarker) }
    }

    /**
     * Deletes files of simulators not in [existing]. It is read after listing the files, so files of a simulator
     * created meanwhile are kept.
     */
    fun deleteOrphans(existing: () -> Set<UDID>): List<File> {
        val found = entries()
        val keep = existing()
        return found.filter { it.second !in keep }.map { it.first }.filter { it.deleteRecursivelyIfExist(logger, logMarker) }
    }

    private fun entries(): List<Pair<File, UDID>> =
        listOf(tmpDir to TMP_DIR_ENTRY, varTmpDir to VAR_TMP_DIR_ENTRY).flatMap { (dir, pattern) ->
            dir.listFiles().orEmpty().mapNotNull { file ->
                pattern.matchEntire(file.name)?.let { file to it.groupValues[1] }
            }
        }

    private companion object {
        // Simulator UDIDs only: physical devices (00008110-001A...) use the same tmp dir and must be left alone.
        private const val SIMULATOR_UDID = "([0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12})"
        // derivedData_<udid>, xctestRunDir_<udid>, web_driver_agent_log_<udid>.txt, iOS_SysLog_<udid>[.err].log
        private val TMP_DIR_ENTRY =
            Regex("(?:derivedData_|xctestRunDir_|web_driver_agent_log_|iOS_SysLog_)$SIMULATOR_UDID(?:\\.txt|\\.log|\\.err\\.log)?")
        private val VAR_TMP_DIR_ENTRY = Regex("com\\.apple\\.CoreSimulator\\.SimDevice\\.$SIMULATOR_UDID")
    }
}
