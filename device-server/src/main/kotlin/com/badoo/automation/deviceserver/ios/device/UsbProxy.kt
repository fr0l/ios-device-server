package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.SubProcess
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.Executables
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

class UsbProxy(
    private val udid: UDID,
    private val remote: IRemote,
    val localPort: Int,
    private val childFactory: (
        remoteHost: String,
        cmd: List<String>,
        commandEnvironment: Map<String, String>,
        out_reader: (line: String) -> Unit,
        err_reader: (line: String) -> Unit
    ) -> SubProcess = SubProcess.Companion::fromCommand,
    private val iproxyBinary: String = Executables.require("iproxy", Executables.IPROXY_INSTALL)
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.hostName,
            LogMarkers.UDID to udid
        )
    )

    override fun toString(): String = "<iproxy $localPort $udid>"

    private var iproxy: SubProcess? = null

    fun start(devicePort: Int) {
        // Clients connect from other hosts, and the default listen address differs between iproxy versions.
        val iProxyCommand = listOf(iproxyBinary, "$localPort:$devicePort", "--udid", udid, "--source", "0.0.0.0")
        iproxy = childFactory(
            remote.hostName,
            iProxyCommand,
            mapOf(),
            { message -> }, // { message -> logger.trace(logMarker, "${this}: iproxy <o>: ${message.trim()}") },
            { message -> logger.debug(logMarker, "${this}: iproxy <e>: ${message.trim()}") }
        )
    }

    fun isHealthy(): Boolean {
        return iproxy?.isAlive() ?: false
    }

    fun stop() {
        iproxy?.let {
            logger.debug(logMarker, "$this — Killing child process $it")
            it.kill()
            iproxy = null
        }
    }
}
