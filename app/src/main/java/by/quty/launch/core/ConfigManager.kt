// *** core/ConfigManager.kt *** //
package by.quty.launch.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import androidx.core.content.edit

@Serializable
data class LauncherConfig(
    val defaultTheme: String = "default",
    val defaultOrientation: String = "sensor",
    val defaultFullscreen: Boolean = true
)

class ConfigManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    private var config: LauncherConfig = loadConfig()

    private fun loadConfig(): LauncherConfig {
        return try {
            val inputStream = context.assets.open("launcher.conf")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<LauncherConfig>(jsonString)
        } catch (_: IOException) {
            // Если файла нет - используем default
            LauncherConfig()
        }
    }

    fun getDefaultTheme(): String = config.defaultTheme
    fun getDefaultOrientation(): String = config.defaultOrientation
    fun getDefaultFullscreen(): Boolean = config.defaultFullscreen

    // Получения активной темы
    fun getActiveTheme(): String {
        return prefs.getString("active_theme", getDefaultTheme()) ?: getDefaultTheme()
    }

    // Сохранения активной темы
    fun setActiveTheme(themeId: String) {
        prefs.edit { putString("active_theme", themeId) }
    }

    // Получение ориентации
    fun getOrientation(): String {
        return prefs.getString("orientation", getDefaultOrientation()) ?: getDefaultOrientation()
    }

    // Сохранение ориентации
    fun setOrientation(orientation: String) {
        prefs.edit { putString("orientation", orientation) }
    }

    // Получение полноэкранного режима
    fun isFullscreenEnabled(): Boolean {
        return prefs.getBoolean("fullscreen", getDefaultFullscreen())
    }

    // Сохранение полноэкранного режима
    fun setFullscreenEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("fullscreen", enabled) }
    }
}