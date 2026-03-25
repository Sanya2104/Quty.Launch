// *** api/model/StatusBarInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

@Serializable
data class StatusBarInfo(
    val cpuTemp: String? = null,
    val internetSpeed: String? = null,
    val volume: String? = null,
    val gsmSignal: Int? = null,
    val gsmNetworkType: String? = null,
    val wifiSignalLevel: Int? = null,
    val bluetooth: Boolean = false,
    val wifi: Boolean = false,
    val gps: Boolean = false,
    val usbConnected: Boolean = false
)