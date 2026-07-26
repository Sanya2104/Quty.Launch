// *** core/ThemeManager.kt *** //
package by.quty.launch.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import by.quty.launch.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Модель темы с расширенной информацией
 */
data class Theme(
    val name: String,
    val isDefault: Boolean,
    val sourcePath: String,
    val isAsset: Boolean = false,
    val displayName: String? = null,
    val isCustom: Boolean = false,
    val version: String? = null,            // версия темы из manifest.json
    val author: String? = null,             // автор темы из manifest.json
    val previewBase64: String? = null,      // превью в base64 для отображения
    val orientation: String? = null,        // ориентация из manifest.json (portrait/landscape/sensor/user)
    val repoUrl: String? = null,            // ссылка на репозиторий из manifest.json
    val minLauncherVersion: String? = null  // минимальная версия лаунчера из manifest.json
)

/**
 * Структура manifest.json темы
 */
@Serializable
data class ThemeManifest(
    val name: String,
    val author: String = "",
    val version: String = "0.0.1",
    val preview: String? = null,            // путь к превью внутри темы
    val orientation: String? = null,        // ориентация темы (portrait/landscape/sensor/user)
    val repoUrl: String? = null,            // ссылка на репозиторий
    val minLauncherVersion: String? = null  // минимальная версия лаунчера
)

class ThemeManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    companion object {
        /** Основное расширение файла темы (без точки) */
        const val THEME_EXTENSION = "qutytheme"
        /** Основное расширение файла темы (с точкой) */
        const val THEME_EXTENSION_WITH_DOT = ".qutytheme"

        /** Дополнительные расширения файлов тем (без точки) */
        val THEME_EXTENSIONS = listOf(
            "qutytheme",    // основное
            "qtp",          // сокращённое
            "qutythemepack" // альтернативное
        )

        /** Дополнительные расширения файлов тем (с точкой) */
        val THEME_EXTENSIONS_WITH_DOT = listOf(
            ".qutytheme",
            ".qtp",
            ".qutythemepack"
        )
    }

    private val themesDir = File(context.filesDir, "themes")
    private val activeThemeDir = File(themesDir, "active")

    // Новая структура: Quty.Launch/Themes/
    private val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
    private val customThemesDir = File(appDir, "Themes")

    private var activeTheme: Theme? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Поддерживаемые расширения тем
    private val supportedExtensions = THEME_EXTENSIONS

    init {
        if (!themesDir.exists()) themesDir.mkdirs()
        if (!activeThemeDir.exists()) activeThemeDir.mkdirs()
        if (!appDir.exists()) appDir.mkdirs()
        if (!customThemesDir.exists()) customThemesDir.mkdirs()

        // При инициализации загружаем активную тему из настроек
        loadActiveThemeFromConfig()
    }

    /**
     * Загружает активную тему из конфига в память
     */
    private fun loadActiveThemeFromConfig() {
        val themes = getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        // Проверяем, существует ли тема в списке доступных
        val foundTheme = themes.find { it.name == activeThemeId }

        if (foundTheme != null) {
            activeTheme = foundTheme
        } else {
            // Тема не найдена — восстанавливаем Default
            restoreDefaultTheme()
        }

        // Сохраняем принудительную ориентацию в SharedPreferences, если она есть
        saveForcedOrientation()
    }

    /**
     * Восстанавливает тему по умолчанию, если активная тема не найдена
     */
    private fun restoreDefaultTheme() {
        val themes = getAvailableThemes()

        // Ищем тему по умолчанию
        var defaultTheme = themes.find { it.isDefault }

        // Если тема по умолчанию не найдена, ищем по имени "default"
        if (defaultTheme == null) {
            defaultTheme = themes.find { it.name == "default" }
        }

        // Если всё ещё не найдена, берём первую доступную тему
        if (defaultTheme == null) {
            defaultTheme = themes.firstOrNull()
        }

        // Если совсем нет тем — создаём базовую тему
        if (defaultTheme == null) {
            defaultTheme = Theme(
                name = "default",
                isDefault = true,
                sourcePath = "themes/default",
                isAsset = true,
                displayName = context.getString(R.string.theme_default_name)
            )
        }

        // Сохраняем тему по умолчанию как активную
        activeTheme = defaultTheme
        configManager.setActiveTheme(defaultTheme.name)

        // Показываем уведомление о восстановлении
        val message = context.getString(R.string.theme_active_not_found) + "\n" +
                context.getString(R.string.theme_restored_default, defaultTheme.displayName ?: defaultTheme.name)

        // Используем Handler для показа Toast в UI потоке
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Сохраняет принудительную ориентацию текущей темы в SharedPreferences
     * Если тема не задаёт ориентацию - удаляем ключ forced_orientation
     */
    private fun saveForcedOrientation() {
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val forcedOrientation = getForcedOrientationFromActiveTheme()

        prefs.edit {
            if (forcedOrientation != null) {
                putString("forced_orientation", forcedOrientation)
            } else {
                remove("forced_orientation")
            }
        }
    }

    /**
     * Получить принудительную ориентацию из активной темы
     * @return String? - "portrait", "landscape", "sensor", "user" или null
     */
    fun getForcedOrientationFromActiveTheme(): String? {
        val theme = getActiveTheme() ?: return null

        // Проверяем валидность значения
        return when (val orientation = theme.orientation) {
            "portrait", "landscape", "sensor", "user" -> orientation
            else -> null // игнорируем некорректные значения
        }
    }

    /**
     * Проверяет, задаёт ли текущая тема принудительную ориентацию
     * @return true если тема задаёт portrait или landscape
     */
    fun hasForcedOrientation(): Boolean {
        val forced = getForcedOrientationFromActiveTheme()
        return forced == "portrait" || forced == "landscape"
    }

    /**
     * Получить список всех доступных тем
     */
    fun getAvailableThemes(): List<Theme> {
        val themes = mutableListOf<Theme>()

        // Встроенные темы из assets
        themes.addAll(getBuiltInThemes())

        // Кастомные темы из внешней папки (Quty.Launch/Themes/)
        if (customThemesDir.exists()) {
            // Ищем файлы с поддерживаемыми расширениями
            customThemesDir.listFiles { file ->
                supportedExtensions.any { ext ->
                    file.extension.equals(ext, ignoreCase = true)
                }
            }?.forEach { file ->
                val manifest = readManifestFromZip(file)
                val previewBase64 = loadPreviewFromZip(file, manifest?.preview)

                themes.add(
                    Theme(
                        name = file.nameWithoutExtension,
                        isDefault = false,
                        sourcePath = file.absolutePath,
                        isAsset = false,
                        displayName = manifest?.name ?: file.nameWithoutExtension,
                        isCustom = true,
                        version = manifest?.version,
                        author = manifest?.author,
                        previewBase64 = previewBase64,
                        orientation = manifest?.orientation,
                        repoUrl = manifest?.repoUrl,
                        minLauncherVersion = manifest?.minLauncherVersion
                    )
                )
            }
        }

        return themes
    }

    /**
     * Получить встроенные темы из assets
     */
    private fun getBuiltInThemes(): List<Theme> {
        val themes = mutableListOf<Theme>()

        try {
            val assetPaths = context.assets.list("themes") ?: return themes

            assetPaths.forEach { themeFolder ->
                try {
                    context.assets.open("themes/$themeFolder/index.html").close()

                    val manifest = try {
                        context.assets.open("themes/$themeFolder/manifest.json").bufferedReader().use {
                            json.decodeFromString<ThemeManifest>(it.readText())
                        }
                    } catch (_: Exception) {
                        null
                    }

                    val previewBase64 = loadPreviewFromAssets(themeFolder, manifest?.preview)

                    themes.add(
                        Theme(
                            name = themeFolder,
                            isDefault = themeFolder == "default",
                            sourcePath = "themes/$themeFolder",
                            isAsset = true,
                            displayName = manifest?.name ?: when (themeFolder) {
                                "default" -> context.getString(R.string.theme_default_name)
                                else -> themeFolder.replaceFirstChar { it.uppercase() }
                            },
                            isCustom = false,
                            version = manifest?.version,
                            author = manifest?.author,
                            previewBase64 = previewBase64,
                            orientation = manifest?.orientation,
                            repoUrl = manifest?.repoUrl,
                            minLauncherVersion = manifest?.minLauncherVersion
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

    private fun readManifestFromZip(zipFile: File): ThemeManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).bufferedReader().use {
                    json.decodeFromString<ThemeManifest>(it.readText())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadPreviewFromZip(zipFile: File, previewPath: String?): String? {
        if (previewPath.isNullOrEmpty()) return null

        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(previewPath) ?: return null
                zip.getInputStream(entry).use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    bitmap?.let { bitmapToBase64(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadPreviewFromAssets(themeFolder: String, previewPath: String?): String? {
        if (previewPath.isNullOrEmpty()) return null

        return try {
            val inputStream = context.assets.open("themes/$themeFolder/$previewPath")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            bitmap?.let { bitmapToBase64(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    fun getThemeToActivate(): Theme {
        val themes = getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()

        themes.find { it.name == activeThemeId }?.let { return it }

        val defaultThemeId = configManager.getDefaultTheme()
        themes.find { it.name == defaultThemeId }?.let { return it }

        themes.find { it.name == "default" }?.let { return it }

        return themes.firstOrNull() ?: Theme(
            name = "Default",
            isDefault = true,
            sourcePath = "themes/default",
            isAsset = true,
            displayName = context.getString(R.string.theme_default_name)
        )
    }

    /**
     * Принудительно перезагружает активную тему из файла
     * Используется после обновления темы
     */
    fun reloadActiveTheme() {
        val themes = getAvailableThemes()
        val activeThemeId = configManager.getActiveTheme()
        activeTheme = themes.find { it.name == activeThemeId }

        // Сохраняем принудительную ориентацию в SharedPreferences, если она есть
        saveForcedOrientation()
    }

    fun getActiveTheme(): Theme? {
        // Если в памяти нет, пробуем загрузить из конфига
        if (activeTheme == null) {
            loadActiveThemeFromConfig()
        }
        return activeTheme
    }

    fun setActiveTheme(theme: Theme) {
        // Сохраняем в конфиг
        configManager.setActiveTheme(theme.name)

        // Обновляем в памяти
        activeTheme = theme

        // Сохраняем принудительную ориентацию в SharedPreferences
        saveForcedOrientation()

        // Очищаем директорию активной темы
        clearActiveDir()

        // Если это не asset тема - распаковываем в папку с именем темы
        if (!theme.isAsset) {
            val extractDir = File(activeThemeDir, theme.name)
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }
            unzipTheme(theme.sourcePath, extractDir)
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

        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}