package com.badoo.automation.deviceserver.ios.device

import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.host.IRemote

class DeviceInfoProvider(private val remote: IRemote) {
    fun list(): List<DeviceInfo> {
        return remote.goIos.listDevices().map { device ->
            DeviceInfo(
                udid = device.udid,
                model = device.model,
                os = "iOS ${device.osVersion}",
                arch = device.arch,
                name = device.name
            )
        }
    }
}