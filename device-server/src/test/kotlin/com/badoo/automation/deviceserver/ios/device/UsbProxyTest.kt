package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.command.SubProcess
import com.badoo.automation.deviceserver.host.IRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class UsbProxyTest {
    private val remote: IRemote = mock { on { hostName } doReturn "localhost" }
    private val startedCommands = mutableListOf<List<String>>()
    private var iproxyAlive = true
    private val iproxy: SubProcess = mock { on { isAlive() } doAnswer { iproxyAlive } }

    private val usbProxy = UsbProxy(
        "00008110-0000000000000001",
        remote,
        41800,
        { _, cmd, _, _, _ -> startedCommands += cmd; iproxy },
        iproxyBinary = "/usr/local/bin/iproxy"
    )

    @Test
    fun startsOnlyIproxyListeningOnAllInterfaces() {
        usbProxy.start(27753)

        assertEquals(
            listOf(listOf("/usr/local/bin/iproxy", "41800:27753", "--udid", "00008110-0000000000000001", "--source", "0.0.0.0")),
            startedCommands
        )
    }

    @Test
    fun healthFollowsIproxy() {
        usbProxy.start(27753)
        assertTrue(usbProxy.isHealthy())

        iproxyAlive = false
        assertFalse(usbProxy.isHealthy())
    }

    @Test
    fun stopKillsIproxy() {
        usbProxy.start(27753)
        usbProxy.stop()

        verify(iproxy).kill()
        assertFalse(usbProxy.isHealthy())
    }
}
