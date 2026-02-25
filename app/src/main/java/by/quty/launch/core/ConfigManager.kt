// *** core/ConfigManager.kt *** //
package by.quty.launch.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class LauncherConfig(
    val defaultTheme: String = "default"
)

class ConfigManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
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
}