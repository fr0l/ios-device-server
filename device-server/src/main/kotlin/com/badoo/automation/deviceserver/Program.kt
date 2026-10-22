package com.badoo.automation.deviceserver

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        embeddedServer(CIO, port = 4567, host = "0.0.0.0", module = Application::module)
            .start(wait = true)
    } catch (e: Throwable) {
        // Background pools started during setup would otherwise keep a server that never listens alive
        e.printStackTrace()
        exitProcess(1)
    }
}
