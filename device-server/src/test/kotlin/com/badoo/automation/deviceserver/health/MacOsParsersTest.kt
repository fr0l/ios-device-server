package com.badoo.automation.deviceserver.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacOsParsersTest {
    @Test
    fun loadAverage() {
        assertEquals(MacOsParsers.LoadAverage(5.77, 6.19, 6.85), MacOsParsers.parseLoadAverage("{ 5.77 6.19 6.85 }\n"))
    }

    @Test
    fun simulatorProcessesAreTheLaunchdSimTreesByUdid() {
        val a = "9A5DBEC2-9234-4EB9-9434-72C5DF7DEA0B"
        val b = "4BCC071E-9613-4129-A40F-E5381EDBDB8E"
        val ps = """
              2048     1 /Library/Developer/PrivateFrameworks/CoreSimulator.framework/Versions/A/Resources/bin/launchd_sim /Users/qa/Library/Developer/CoreSimulator/Devices/$a/data/var/run/launchd_bootstrap.plist
              2051  2048 /Library/Developer/CoreSimulator/Volumes/iOS_23F77/RuntimeRoot/System/Library/CoreServices/SpringBoard.app/SpringBoard
              2060  2048 /usr/libexec/triald
              2061  2060 assetsd
              3000     1 /Library/Developer/PrivateFrameworks/CoreSimulator.framework/Versions/A/Resources/bin/launchd_sim /Users/qa/Library/Developer/CoreSimulator/Devices/$b/data/var/run/launchd_bootstrap.plist
              3001  3000 /usr/libexec/pkd
               999   500 /Applications/Xcode.app/Contents/Developer/usr/bin/xcodebuild -destination id=$a -xctestrun /Users/qa/Library/Developer/CoreSimulator/Devices/$a/x.xctestrun
               409     1 /usr/sbin/cfprefsd agent
        """.trimIndent()

        assertEquals(mapOf(a to listOf(2048, 2051, 2060, 2061), b to listOf(3000, 3001)), MacOsParsers.parseSimulatorProcesses(ps))
    }

    @Test
    fun processStats() {
        assertEquals(MacOsParsers.ProcessStats(98304, 0.3), MacOsParsers.parseProcessStats(" 98304   0.3\n"))
    }

    @Test
    fun vmStat() {
        val vm = MacOsParsers.parseVmStat(
            """
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                                     4230.
            Pages active:                                 976982.
            Pages inactive:                               974616.
            Pages speculative:                              1602.
            Pages wired down:                             391272.
            "Translation faults":                     4333005654.
            Swapouts:                                     183304.
            """.trimIndent()
        )

        assertEquals(16384L, vm.pageSize)
        assertEquals(4230L * 16384, vm.bytes("Pages free"))
        assertEquals(974616L, vm.pages["Pages inactive"])
        assertEquals(4333005654L, vm.pages["Translation faults"])
    }

    @Test
    fun swapUsage() {
        val swap = MacOsParsers.parseSwapUsage("vm.swapusage: total = 2048.00M  used = 866.81M  free = 1181.19M  (encrypted)")
        assertEquals(2048.0, swap.totalMb, 0.001)
        assertEquals(866.81, swap.usedMb, 0.001)

        assertEquals(3072.0, MacOsParsers.parseSwapUsage("total = 3.00G  used = 0.00M  free = 3.00G").totalMb, 0.001)
    }

    @Test
    fun df() {
        val usage = MacOsParsers.parseDf(
            """
            Filesystem   1024-blocks       Used Available Capacity  Mounted on
            /dev/disk3s5  1948455240 1208375008 682853476    64%    /System/Volumes/Data
            """.trimIndent()
        )

        assertEquals("/System/Volumes/Data", usage.mountPoint)
        assertEquals(1948455240L * 1024, usage.totalBytes)
        assertEquals(682853476L * 1024, usage.availableBytes)
    }

    @Test
    fun dfWithSpacesInMountPoint() {
        val usage = MacOsParsers.parseDf(
            """
            Filesystem 1024-blocks Used Available Capacity Mounted on
            /dev/disk5s1 976490576 10 976490566 1% /Volumes/CI Cache
            """.trimIndent()
        )

        assertEquals("/Volumes/CI Cache", usage.mountPoint)
    }

    @Test
    fun pmsetAppleSiliconNominal() {
        val thermal = MacOsParsers.parsePmsetTherm(
            """
            Note: No thermal warning level has been recorded
            Note: No performance warning level has been recorded
            Note: No CPU power status has been recorded
            """.trimIndent()
        )

        assertEquals(MacOsParsers.Thermal(0, 0, null), thermal)
    }

    @Test
    fun pmsetWarningsAndIntelSpeedLimit() {
        val thermal = MacOsParsers.parsePmsetTherm(
            """
            Thermal warning level set to 2.
            Performance warning level set to 1.
            CPU Power notify
            	CPU_Scheduler_Limit 	= 100
            	CPU_Available_CPUs 	= 12
            	CPU_Speed_Limit 	= 70
            """.trimIndent()
        )

        assertEquals(MacOsParsers.Thermal(2, 1, 70), thermal)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pmsetRejectsUnknownOutput() {
        MacOsParsers.parsePmsetTherm("pmset: invalid option")
    }

    @Test
    fun defaultRoute() {
        val route = MacOsParsers.parseDefaultRoute(
            """
               route to: default
            destination: default
                   mask: default
                gateway: 192.168.154.1
              interface: en0
                  flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
            """.trimIndent()
        )

        assertEquals(MacOsParsers.DefaultRoute("192.168.154.1", "en0"), route)
    }

    @Test
    fun networksetupHardwarePorts() {
        val ports = MacOsParsers.parseHardwarePorts(
            """

            Hardware Port: Ethernet
            Device: en0
            Ethernet Address: 3c:a6:f6:00:00:01

            Hardware Port: Wi-Fi
            Device: en1
            Ethernet Address: 3c:a6:f6:00:00:02

            Hardware Port: Thunderbolt Bridge
            Device: bridge0
            Ethernet Address: 36:ef:b2:23:b1:c0

            VLAN Configurations
            ===================
            """.trimIndent()
        )

        assertEquals(mapOf("en0" to "Ethernet", "en1" to "Wi-Fi", "bridge0" to "Thunderbolt Bridge"), ports)
    }

    @Test
    fun ifconfigGigabitEthernet() {
        val info = MacOsParsers.parseIfconfig(
            """
            en0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST> mtu 1500
            	options=50b<RXCSUM,TXCSUM,VLAN_HWTAGGING,AV,CHANNEL_IO>
            	ether 3c:a6:f6:00:00:01
            	inet 10.20.30.41 netmask 0xfffffe00 broadcast 10.20.31.255
            	media: autoselect (1000baseT <full-duplex,flow-control,energy-efficient-ethernet>)
            	status: active
            """.trimIndent()
        )

        assertEquals("10.20.30.41", info.ipv4)
        assertEquals(23, info.prefixLength)
        assertEquals("10.20.30.0/23", info.subnet)
        assertEquals(1000, info.speedMbit)
        assertEquals("full", info.duplex)
        assertEquals(true, info.active)
    }

    @Test
    fun ifconfigTenGigabitAndFastEthernet() {
        val tenGig = "en0: flags=8863<UP> mtu 1500\n\tmedia: autoselect (10Gbase-T <full-duplex>)\n\tstatus: active"
        val fast = "en0: flags=8863<UP> mtu 1500\n\tmedia: autoselect (100baseTX <half-duplex>)\n\tstatus: active"

        assertEquals(10000, MacOsParsers.parseIfconfig(tenGig).speedMbit)
        assertEquals(100, MacOsParsers.parseIfconfig(fast).speedMbit)
        assertEquals("half", MacOsParsers.parseIfconfig(fast).duplex)
    }

    @Test
    fun ifconfigWifiHasNoSpeed() {
        val info = MacOsParsers.parseIfconfig(
            """
            en0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST> mtu 1500
            	options=6460<TSO4,TSO6,CHANNEL_IO,PARTIAL_CSUM,ZEROINVERT_CSUM>
            	ether c6:d5:b9:9b:c6:62
            	inet 192.168.154.62 netmask 0xffffff00 broadcast 192.168.154.255
            	media: autoselect
            	status: active
            """.trimIndent()
        )

        assertNull(info.speedMbit)
        assertEquals("192.168.154.0/24", info.subnet)
    }

    @Test
    fun ifconfigInactiveAndVlan() {
        val inactive = "en4: flags=8863<UP> mtu 1500\n\tmedia: none\n\tstatus: inactive"
        val vlan = "vlan0: flags=8843<UP> mtu 1500\n\tinet 10.1.2.3 netmask 0xffffff00 broadcast 10.1.2.255\n\tvlan: 42 parent interface: en0\n\tstatus: active"

        assertEquals(false, MacOsParsers.parseIfconfig(inactive).active)
        assertEquals(42, MacOsParsers.parseIfconfig(vlan).vlanTag)
        assertEquals("en0", MacOsParsers.parseIfconfig(vlan).vlanParent)
    }

    @Test
    fun ping() {
        val ok = MacOsParsers.parsePing(
            """
            --- 192.168.154.1 ping statistics ---
            3 packets transmitted, 3 packets received, 0.0% packet loss
            round-trip min/avg/max/stddev = 3.413/3.925/4.388/0.400 ms
            """.trimIndent()
        )
        val lost = MacOsParsers.parsePing("--- 10.0.0.1 ping statistics ---\n3 packets transmitted, 0 packets received, 100.0% packet loss")

        assertEquals(MacOsParsers.PingResult(3, 3, 0.0, 3.925), ok)
        assertEquals(MacOsParsers.PingResult(3, 0, 100.0, null), lost)
    }

    @Test
    fun dig() {
        val result = MacOsParsers.parseDig(
            """
            ;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 14430
            mobile-ci.example.com.	0	IN	CNAME	teamcity.example.com.
            ;; Query time: 203 msec
            """.trimIndent()
        )

        assertEquals(MacOsParsers.DigResult("NOERROR", 203), result)
        assertEquals("NXDOMAIN", MacOsParsers.parseDig(";; ->>HEADER<<- opcode: QUERY, status: NXDOMAIN, id: 1").status)
    }

    @Test
    fun sntp() {
        assertEquals(0.048756, MacOsParsers.parseSntpOffset("+0.048756 +/- 0.021472 time.apple.com 17.253.28.45\n"), 1e-9)
        assertEquals(-42.5, MacOsParsers.parseSntpOffset("-42.500000 +/- 0.010000 time.apple.com 17.253.28.45"), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sntpRejectsTimeout() {
        MacOsParsers.parseSntpOffset("sntp: Exchange failed: Timeout")
    }

    @Test
    fun cidr() {
        assertEquals(true, HostProbes.cidrContains("10.20.30.0/23", "10.20.31.200"))
        assertEquals(false, HostProbes.cidrContains("10.20.30.0/23", "10.20.32.1"))
    }
}
