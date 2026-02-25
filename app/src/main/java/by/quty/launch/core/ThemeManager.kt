// *** core/ThemeManager.kt *** /
package by.quty.launch.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class Theme(
    val name: String,
    val isDefault: Boolean,
    val sourcePath: String,
    val isAsset: Boolean = false,
    val displayName: String? = null,  // добавляем displayName
    val isCustom: Boolean = false      // добавляем isCustom
)

class ThemeManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    private val themesDir = File(context.filesDir, "themes")
    private val activeThemeDir = File(themesDir, "active")
    private val customThemesDir = File(Environment.getExternalStorageDirectory(), "QutyThemes")

    private var activeTheme: Theme? = null

    init {
        if (!themesDir.exists()) themesDir.mkdirs()
        if (!activeThemeDir.exists()) activeThemeDir.mkdirs()
        if (!customThemesDir.exists()) customThemesDir.mkdirs()
    }

    fun getAvailableThemes(): List<Theme> {
        val themes = mutableListOf<Theme>()

        // Встроенные темы из assets
        themes.addAll(getBuiltInThemes())

        // Кастомные темы из внешней папки
        if (customThemesDir.exists()) {
            customThemesDir.listFiles { file -> file.extension == "qutytheme" }?.forEach { file ->
                themes.add(
                    Theme(
                        name = file.nameWithoutExtension,
                        isDefault = false,
                        sourcePath = file.absolutePath,
                        isAsset = false,
                        displayName = file.nameWithoutExtension,
                        isCustom = true  // помечаем как кастомные
                    )
                )
            }
        }

        return themes
    }

    private fun getBuiltInThemes(): List<Theme> {
        val themes = mutableListOf<Theme>()

        try {
            val assetPaths = context.assets.list("themes") ?: return themes

            assetPaths.forEach { themeFolder ->
                try {
                    context.assets.open("themes/$themeFolder/index.html").close()

                    themes.add(
                        Theme(
                            name = themeFolder,
                            isDefault = themeFolder == "default",
                            sourcePath = "themes/$themeFolder",
                            isAsset = true,
                            displayName = when (themeFolder) {
                                "default" -> "Стандартная"
                                else -> themeFolder.replaceFirstChar { it.uppercase() }
                            },
                            isCustom = false
                        )
                    )
                } catch (e: Exception) {
                    // В папке нет index.html - пропускаем
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return themes
    }

    fun getThemeToActivate(): Theme {
        val themes = getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        android.util.Log.d("ThemeManager", "Looking for theme: $activeThemeId")

        // 1. Ищем сохраненную тему
        themes.find { it.name == activeThemeId }?.let {
            android.util.Log.d("ThemeManager", "Found saved theme: ${it.name}")
            return it
        }

        // 2. Если нет - ищем тему из конфига
        val defaultThemeId = configManager.getDefaultTheme()
        android.util.Log.d("ThemeManager", "Saved theme not found, looking for default: $defaultThemeId")

        themes.find { it.name == defaultThemeId }?.let {
            android.util.Log.d("ThemeManager", "Found default theme: ${it.name}")
            return it
        }

        // 3. Если нет - ищем тему с именем "default"
        android.util.Log.d("ThemeManager", "Default theme not found, looking for 'default'")
        themes.find { it.name == "default" }?.let {
            android.util.Log.d("ThemeManager", "Found 'default' theme: ${it.name}")
            return it
        }

        // 4. Если ничего нет - берем первую попавшуюся
        android.util.Log.d("ThemeManager", "No theme found, taking first")
        return themes.firstOrNull() ?: Theme("Default", true, "themes/default", true, "Стандартная")
    }

    fun getActiveTheme(): Theme? = activeTheme

    fun setActiveTheme(theme: Theme) {
        android.util.Log.d("ThemeManager", "Setting active theme: ${theme.name}")
        activeTheme = theme
        configManager.setActiveTheme(theme.name)  // сохраняем в конфиг

        clearActiveDir()

        if (theme.isAsset) {
            android.util.Log.d("ThemeManager", "Theme is asset, no unpack needed")
            // Тема из assets - ничего не распаковываем
        } else {
            android.util.Log.d("ThemeManager", "Unpacking custom theme")
            // Кастомная тема - распаковываем архив
            unzipTheme(theme.sourcePath, activeThemeDir)
        }
    }

    fun getActiveThemeIndexHtml(): String {
        return if (activeTheme == null || activeTheme!!.isAsset) {
            "file:///android_asset/${activeTheme?.sourcePath ?: "themes/default"}/index.html"
        } else {
            File(activeThemeDir, "index.html").absolutePath.let { "file://$it" }
        }
    }

    private fun clearActiveDir() {
        if (activeThemeDir.exists()) {
            activeThemeDir.deleteRecursively()
            activeThemeDir.mkdirs()
        }
    }

    private fun unzipTheme(zipPath: String, outputDir: File) {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return

        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val outFile = File(outputDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}