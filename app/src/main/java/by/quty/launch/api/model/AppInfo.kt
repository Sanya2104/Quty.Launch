// *** api/model/AppInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

@Serializable
data class AppInfo(
    val name: String,
    val packageName: String,
    val isCustom: Boolean = false,
    val iconBase64: String? = null,
    /**
     * Флаг наличия маркера на иконке.
     * Если true — оболочка рисует точку в правом верхнем углу,
     * цвет берёт из badgeColorHex (или badgeColor — на своё усмотрение).
     */
    val badge: Boolean = false,
    /**
     * Семантический цвет маркера:
     * - "accent"  — обычное обновление
     * - "warning" — рекомендуемое обновление
     * - "error"   — критическое обновление
     * null — если badge = false.
     */
    val badgeColor: String? = null,
    /**
     * HEX-цвет маркера ("#RRGGBB"), готовый для прямой подстановки
     * в CSS / Paint. Источник — R.color.status_* через BadgeHelper.
     * null — если badge = false.
     */
    val badgeColorHex: String? = null
)