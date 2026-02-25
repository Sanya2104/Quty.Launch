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
    val isAsset: Boolean = false
)

class ThemeManager(
    private val context: Context,
    private val configManager: ConfigManager  // добавляем параметр
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
                        isAsset = false
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
                            isAsset = true
                        )
                    )
                } catch (_: Exception) {
                    // В папке нет index.html - пропускаем
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return themes
    }

    // НОВЫЙ МЕТОД: получаем тему для активации согласно конфигу
    fun getThemeToActivate(): Theme {
        val themes = getAvailableThemes()
        val defaultThemeId = configManager.getDefaultTheme()

        // 1. Ищем тему из конфига
        themes.find { it.name == defaultThemeId }?.let {
            return it
        }

        // 2. Если нет - ищем тему с именем "default"
        themes.find { it.name == "default" }?.let {
            return it
        }

        // 3. Если ничего нет - берем первую попавшуюся
        return themes.firstOrNull() ?: Theme("default", true, "themes/default", true)
    }

    fun setActiveTheme(theme: Theme) {
        activeTheme = theme

        clearActiveDir()

        if (theme.isAsset) {
            // Тема из assets - ничего не распаковываем
            // Она будет загружаться напрямую из assets
        } else {
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