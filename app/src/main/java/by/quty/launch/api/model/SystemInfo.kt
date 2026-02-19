// *** api/model/SystemInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

@Serializable
data class SystemInfo(
    val osName: String,
    val osVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val batteryLevel: Int,
    val isCharging: Boolean
)
