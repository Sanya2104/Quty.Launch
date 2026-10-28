// *** api/model/CoreStateInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

/** Полное состояние ядра для оболочки. */
@Serializable
data class CoreStateInfo(
    val theme: String,
    val themeMode: String,
    val language: String,
    val locale: String,
    val orientation: String,
    val fullscreen: Boolean,
    val strictMode: Boolean,
    val animationsEnabled: Boolean,
    val updateBadge: Boolean = false,
    val updateBadgeColor: String? = null,
    val updateBadgeColorHex: String? = null,
    val safeModeActive: Boolean = false,
    val colorScheme: ColorSchemeInfo,
    val shell: ShellInfo?,
    val app: CoreAppInfo
)

@Serializable
data class ColorSchemeInfo(
    val id: String,
    val primary: String,
    val accent: String
)

@Serializable
data class ShellInfo(
    val name: String,
    val displayName: String?,
    val version: String?,
    val author: String?,
    val orientation: String?
)

@Serializable
data class CoreAppInfo(
    val version: String,
    val versionCode: Long
)