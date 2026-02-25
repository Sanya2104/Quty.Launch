// *** api/model/AppInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val name: String,
    val packageName: String,
    val isCustom: Boolean = false
)