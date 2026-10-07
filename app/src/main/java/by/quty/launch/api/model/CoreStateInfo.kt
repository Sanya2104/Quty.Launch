// *** api/model/CoreStateInfo.kt *** //
package by.quty.launch.api.model

import kotlinx.serialization.Serializable

/**
 * Полное состояние ядра Quty.Launch для передачи в оболочку
 * через API-метод GetCoreState.
 */
@Serializable
data class CoreStateInfo(
    val theme: String,              // "dark" | "light" — фактическая тема
    val themeMode: String,          // "dark" | "light" | "system" — выбор пользователя
    val language: String,           // "ru" | "en"
    val locale: String,             // "ru-RU" | "en-US"
    val orientation: String,        // "portrait" | "landscape" | "sensor" | "user"
    val fullscreen: Boolean,
    val strictMode: Boolean,
    val colorScheme: ColorSchemeInfo,
    val shell: ShellInfo?,
    val app: CoreAppInfo
)

@Serializable
data class ColorSchemeInfo(
    val id: String,                 // "teal", "purple", ...
    val primary: String,            // "#009688"
    val accent: String              // "#4CAF50"
)

@Serializable
data class ShellInfo(
    val name: String,               // "QutyAuto"
    val displayName: String?,       // "QutyAuto"
    val version: String?,           // "0.0.1"
    val author: String?,            // "QutyTeam"
    val orientation: String?        // "portrait" | "landscape" | "sensor" | null
)

@Serializable
data class CoreAppInfo(
    val version: String,            // "0.0.150-Alpha"
    val versionCode: Long           // 600
)