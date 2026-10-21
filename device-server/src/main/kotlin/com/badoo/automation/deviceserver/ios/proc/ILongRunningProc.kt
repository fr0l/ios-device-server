package com.badoo.automation.deviceserver.ios.proc

/**
 * Long running process.
 */
interface ILongRunningProc {
    val isProcessAlive: Boolean
    fun start()
    fun isHealthy(): Boolean
    fun kill()
}