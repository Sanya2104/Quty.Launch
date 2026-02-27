// *** core/ConfigManager.kt *** //
package by.quty.launch.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class LauncherConfig(
    val defaultTheme: String = "default",
    val defaultOrientation: String = "sensor"
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

    // Метод для получения активной темы
    fun getActiveTheme(): String {
        val theme = prefs.getString("active_theme", getDefaultTheme()) ?: getDefaultTheme()
        android.util.Log.d("ConfigManager", "Getting active theme: $theme")
        return theme
    }

    // Метод для сохранения активной темы
    fun setActiveTheme(themeId: String) {
        android.util.Log.d("ConfigManager", "Saving active theme: $themeId")
        prefs.edit().putString("active_theme", themeId).apply()
    }

    // Ориентация
    fun getOrientation(): String {
        val orientation = prefs.getString("orientation", getDefaultOrientation()) ?: getDefaultOrientation()
        android.util.Log.d("ConfigManager", "Getting orientation: $orientation")
        return orientation
    }

    fun setOrientation(orientation: String) {
        android.util.Log.d("ConfigManager", "Saving orientation: $orientation")
        prefs.edit().putString("orientation", orientation).apply()
    }
}