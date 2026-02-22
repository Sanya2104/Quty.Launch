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
    val sourcePath: String
)

class ThemeManager(context: Context) {

    private val themesDir = File(context.filesDir, "themes")
    private val activeThemeDir = File(themesDir, "active")
    private val customThemesDir = File(Environment.getExternalStorageDirectory(), "QutyThemes")

    private var activeTheme: Theme? = null

    init {
        if (!themesDir.exists()) themesDir.mkdirs()
        if (!activeThemeDir.exists()) activeThemeDir.mkdirs()
    }

    /** Получаем список доступных тем */
    fun getAvailableThemes(): List<Theme> {
        val themes = mutableListOf<Theme>()

        // Дефолтная тема
        themes.add(
            Theme(
                name = "Default",
                isDefault = true,
                sourcePath = "file:///android_asset/themes/default/"
            )
        )

        // Кастомные темы из внешней папки
        if (customThemesDir.exists()) {
            customThemesDir.listFiles { file -> file.extension == "qutytheme" }?.forEach { file ->
                themes.add(
                    Theme(
                        name = file.nameWithoutExtension,
                        isDefault = false,
                        sourcePath = file.absolutePath
                    )
                )
            }
        }

        return themes
    }

    /** Устанавливаем активную тему */
    fun setActiveTheme(theme: Theme) {
        activeTheme = theme
        if (theme.isDefault) {
            // Для дефолтной темы просто указываем assets
            clearActiveDir()
        } else {
            // Для кастомной темы распаковываем архив
            unzipTheme(theme.sourcePath, activeThemeDir)
        }
    }

    /** Получаем путь к index.html для WebView */
    fun getActiveThemeIndexHtml(): String {
        return if (activeTheme == null || activeTheme!!.isDefault) {
            "file:///android_asset/themes/default/index.html"
        } else {
            File(activeThemeDir, "index.html").absolutePath.let { "file://$it" }
        }
    }

    /** Очищаем активную папку */
    private fun clearActiveDir() {
        if (activeThemeDir.exists()) {
            activeThemeDir.deleteRecursively()
            activeThemeDir.mkdirs()
        }
    }

    /** Распаковка zip архива */
    private fun unzipTheme(sourcePath: String, outputDir: File) {
        val zipFile = File(sourcePath)
        if (!zipFile.exists()) {
            return
        }

        clearActiveDir()
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
