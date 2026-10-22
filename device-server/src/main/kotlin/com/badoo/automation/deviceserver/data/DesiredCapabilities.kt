package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.serialization.Serializable

enum class DeviceType {
    @JsonProperty("simulator") Simulator,
    @JsonProperty("device")    Device
}

// Older clients still send arch (and headless); device_type now chooses a simulator or a real device
@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class DesiredCapabilities(
        val udid: String?,
        val model: String?,
        val os: String?,

        @JsonProperty("use_wda")
        val useWda: Boolean = false,

        @JsonProperty("simulator_clone")
        val isSimulatorClone: Boolean = true,

        @JsonProperty("device_type")
        val deviceType: DeviceType? = null
) {
        val osVersion: String? get() = os?.substringAfter("iOS")?.trim()
        val osMajorVersion: Int? get() = os?.substringAfter("iOS")?.trim()?.split(".")?.first()?.toInt()

        /** [deviceType], or what [udid] looks like when it is missing; a simulator otherwise. */
        @get:JsonIgnore
        val requestedDeviceType: DeviceType
                get() = deviceType ?: if (udid != null && !SIMULATOR_UDID.matches(udid)) DeviceType.Device else DeviceType.Simulator

        override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DesiredCapabilities

                if (udid != other.udid) return false
                if (model != other.model) return false
                if (os != other.os) return false
                if (useWda != other.useWda) return false
                if (isSimulatorClone != other.isSimulatorClone) return false
                if (deviceType != other.deviceType) return false

                return true
        }

        override fun hashCode(): Int {
                var result = udid?.hashCode() ?: 0
                result = 31 * result + (model?.hashCode() ?: 0)
                result = 31 * result + (os?.hashCode() ?: 0)
                result = 31 * result + useWda.hashCode()
                result = 31 * result + isSimulatorClone.hashCode()
                result = 31 * result + (deviceType?.hashCode() ?: 0)
                return result
        }

        private companion object {
                // Simulator UDIDs are UUIDs; real devices have 40 hex digits, or 8 and 16 joined by a dash
                private val SIMULATOR_UDID = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
        }
}
