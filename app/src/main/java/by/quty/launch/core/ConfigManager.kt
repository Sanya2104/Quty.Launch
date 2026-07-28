// *** core/ConfigManager.kt *** //
package by.quty.launch.core

import android.content.Context
import by.quty.launch.R
import by.quty.launch.core.logger.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import androidx.core.content.edit

@Serializable
data class LauncherConfig(
    val defaultShell: String = "default",
    val defaultOrientation: String = "sensor",
    val defaultFullscreen: Boolean = true,
    val defaultStrictMode: Boolean = false
)

class ConfigManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
    private var config: LauncherConfig = loadConfig()

    /**
     * Загружает конфигурацию из launcher.conf
     * Если файл отсутствует или повреждён — использует значения по умолчанию
     */
    private fun loadConfig(): LauncherConfig {
        return try {
            val inputStream = context.assets.open("launcher.conf")
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            try {
                json.decodeFromString<LauncherConfig>(jsonString)
            } catch (e: Exception) {
                // JSON повреждён — используем значения по умолчанию
                Logger.e("ConfigManager", context.getString(R.string.config_parse_error, e.message))
                LauncherConfig()
            }
        } catch (_: IOException) {
            // Файла нет — используем default
            LauncherConfig()
        }
    }

    fun getDefaultShell(): String = config.defaultShell
    fun getDefaultOrientation(): String = config.defaultOrientation
    fun getDefaultFullscreen(): Boolean = config.defaultFullscreen
    fun getDefaultStrictMode(): Boolean = config.defaultStrictMode

    // Получения активной оболочки
    fun getActiveShell(): String {
        return prefs.getString("active_shell", getDefaultShell()) ?: getDefaultShell()
    }

    // Сохранения активной оболочки
    fun setActiveShell(shellId: String) {
        prefs.edit { putString("active_shell", shellId) }
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

    // Получение строгого режима
    fun isStrictModeEnabled(): Boolean {
        // Строгий режим работает только если включен полноэкранный
        if (!isFullscreenEnabled()) return false
        return prefs.getBoolean("strict_mode", getDefaultStrictMode())
    }

    // Сохранение строгого режима
    fun setStrictModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean("strict_mode", enabled) }
    }
}