package com.badoo.automation.deviceserver.ios.fbsimctl

data class AppInfo(
    val bundleId: String,
    val name: String?,
    val bundlePath: String?,
    val dataContainer: String?,
    val installType: String?
)
