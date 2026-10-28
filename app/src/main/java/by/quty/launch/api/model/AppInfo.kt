// *** api/model/AppInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

/**
 * Информация о приложении для оболочки (иконка Base64, опциональный маркер).
 */
@Serializable
data class AppInfo(
    val name: String,
    val packageName: String,
    val isCustom: Boolean = false,
    val iconBase64: String? = null,
    val badge: Boolean = false,
    val badgeColor: String? = null,
    val badgeColorHex: String? = null
)