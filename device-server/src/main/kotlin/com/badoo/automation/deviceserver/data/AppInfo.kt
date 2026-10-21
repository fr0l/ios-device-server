package com.badoo.automation.deviceserver.data

data class AppInfo(
    val bundleId: String,
    val name: String?,
    val bundlePath: String?,
    val dataContainer: String?,
    val installType: String?
)
