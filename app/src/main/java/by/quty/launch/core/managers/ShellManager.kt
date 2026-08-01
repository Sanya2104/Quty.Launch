// *** core/managers/ShellManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import by.quty.launch.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Модель оболочки с расширенной информацией
 */
data class Shell(
    val name: String,
    val isDefault: Boolean,
    val sourcePath: String,
    val isAsset: Boolean = false,
    val displayName: String? = null,
    val isCustom: Boolean = false,
    val version: String? = null,            // версия оболочки из manifest.json
    val author: String? = null,             // автор оболочки из manifest.json
    val previewBase64: String? = null,      // превью в base64 для отображения
    val orientation: String? = null,        // ориентация из manifest.json (portrait/landscape/sensor/user)
    val repoUrl: String? = null,            // ссылка на репозиторий из manifest.json
    val minQutyLaunchVersion: String? = null  // минимальная версия Quty.Launch из manifest.json
)

/**
 * Структура manifest.json оболочки
 */
@Serializable
data class ShellManifest(
    val name: String,
    val author: String = "",
    val version: String = "0.0.1",
    val preview: String? = null,            // путь к превью внутри оболочки
    val orientation: String? = null,        // ориентация оболочки (portrait/landscape/sensor/user)
    val repoUrl: String? = null,            // ссылка на репозиторий
    val minQutyLaunchVersion: String? = null  // минимальная версия Quty.Launch
)

class ShellManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    companion object {
        /** Основное расширение файла оболочки (без точки) */
        // const val SHELL_EXTENSION = "qutyshell"

        /** Основное расширение файла оболочки (с точкой) */
        const val SHELL_EXTENSION_WITH_DOT = ".qutyshell"

        /** Дополнительные расширения файлов оболочки (без точки) */
        val SHELL_EXTENSIONS = listOf(
            "qutyshell",    // основное
            "qsp",          // сокращённое
            "qutyshellpack" // альтернативное
        )

        /** Дополнительные расширения файлов оболочки (с точкой) */
        val SHELL_EXTENSIONS_WITH_DOT = listOf(
            ".qutyshell",
            ".qsp",
            ".qutyshellpack"
        )
    }

    private val shellsDir = File(context.filesDir, "shells")
    private val activeShellDir = File(shellsDir, "active")

    // Новая структура: Quty.Launch/Shells/
    private val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
    private val customShellsDir = File(appDir, "Shells")

    private var activeShell: Shell? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Поддерживаемые расширения оболочек
    private val supportedExtensions = SHELL_EXTENSIONS

    init {
        if (!shellsDir.exists()) shellsDir.mkdirs()
        if (!activeShellDir.exists()) activeShellDir.mkdirs()
        if (!appDir.exists()) appDir.mkdirs()
        if (!customShellsDir.exists()) customShellsDir.mkdirs()

        // При инициализации загружаем активную оболочку из настроек
        loadActiveShellFromConfig()
    }

    /**
     * Загружает активную оболочку из конфига в память
     */
    private fun loadActiveShellFromConfig() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()

        // Проверяем, существует ли оболочка в списке доступных
        val foundShell = shells.find { it.name == activeShellId }

        if (foundShell != null) {
            activeShell = foundShell
        } else {
            // Оболочка не найдена — восстанавливаем Default
            restoreDefaultShell()
        }

        // Сохраняем принудительную ориентацию в SharedPreferences, если она есть
        saveForcedOrientation()
    }

    /**
     * Восстанавливает оболочку по умолчанию, если активная оболочка не найдена
     */
    private fun restoreDefaultShell() {
        val shells = getAvailableShells()

        // Ищем оболочку по умолчанию
        var defaultShell = shells.find { it.isDefault }

        // Если оболочка по умолчанию не найдена, ищем по имени "default"
        if (defaultShell == null) {
            defaultShell = shells.find { it.name == "default" }
        }

        // Если всё ещё не найдена, берём первую доступную оболочку
        if (defaultShell == null) {
            defaultShell = shells.firstOrNull()
        }

        // Если совсем нет оболочек — создаём базовую оболочку
        if (defaultShell == null) {
            defaultShell = Shell(
                name = "default",
                isDefault = true,
                sourcePath = "shells/default",
                isAsset = true,
                displayName = context.getString(R.string.shell_default_name)
            )
        }

        // Сохраняем оболочку по умолчанию как активную
        activeShell = defaultShell
        configManager.setActiveShell(defaultShell.name)

        // Показываем уведомление о восстановлении
        val message = context.getString(R.string.shell_active_not_found) + "\n" +
                context.getString(R.string.shell_restored_default, defaultShell.displayName ?: defaultShell.name)

        // Используем Handler для показа Toast в UI потоке
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Сохраняет принудительную ориентацию текущей оболочки в SharedPreferences
     * Если оболочка не задаёт ориентацию - удаляем ключ forced_orientation
     */
    private fun saveForcedOrientation() {
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val forcedOrientation = getForcedOrientationFromActiveShell()

        prefs.edit {
            if (forcedOrientation != null) {
                putString("forced_orientation", forcedOrientation)
            } else {
                remove("forced_orientation")
            }
        }
    }

    /**
     * Получить принудительную ориентацию из активной оболочки
     * @return String? - "portrait", "landscape", "sensor", "user" или null
     */
    fun getForcedOrientationFromActiveShell(): String? {
        val shell = getActiveShell() ?: return null

        // Проверяем валидность значения
        return when (val orientation = shell.orientation) {
            "portrait", "landscape", "sensor", "user" -> orientation
            else -> null // игнорируем некорректные значения
        }
    }

    /**
     * Проверяет, задаёт ли текущая оболочка принудительную ориентацию
     * @return true если оболочка задаёт portrait или landscape
     */
    fun hasForcedOrientation(): Boolean {
        val forced = getForcedOrientationFromActiveShell()
        return forced == "portrait" || forced == "landscape"
    }

    /**
     * Получить список всех доступных оболочек
     * Встроенные оболочки заменяются кастомными версиями с тем же именем
     */
    fun getAvailableShells(): List<Shell> {
        val builtInShells = getBuiltInShells()
        val customShells = getCustomShells()

        val result = mutableListOf<Shell>()

        // Для каждой встроенной оболочки проверяем, есть ли кастомная версия с тем же именем
        builtInShells.forEach { builtIn ->
            val customVersion = customShells.find { it.name == builtIn.name }
            if (customVersion != null) {
                // Если есть кастомная версия — показываем её вместо встроенной
                result.add(customVersion)
            } else {
                // Если нет — показываем встроенную
                result.add(builtIn)
            }
        }

        // Добавляем остальные кастомные оболочки (которые не перезаписывают встроенные)
        customShells.forEach { custom ->
            if (result.none { it.name == custom.name }) {
                result.add(custom)
            }
        }

        return result
    }

    /**
     * Получить кастомные оболочки из внешней папки (Quty.Launch/Shells/)
     */
    private fun getCustomShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        // Кастомные оболочки из внешней папки (Quty.Launch/Shells/)
        if (customShellsDir.exists()) {
            // Ищем файлы с поддерживаемыми расширениями
            customShellsDir.listFiles { file ->
                supportedExtensions.any { ext ->
                    file.extension.equals(ext, ignoreCase = true)
                }
            }?.forEach { file ->
                val manifest = readManifestFromZip(file)
                val previewBase64 = loadPreviewFromZip(file, manifest?.preview)

                shells.add(
                    Shell(
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
                        minQutyLaunchVersion = manifest?.minQutyLaunchVersion
                    )
                )
            }
        }

        return shells
    }

    /**
     * Получить встроенные оболочки из assets
     */
    private fun getBuiltInShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        try {
            val assetPaths = context.assets.list("shells") ?: return shells

            assetPaths.forEach { shellFolder ->
                // Проверяем наличие index.html
                try {
                    context.assets.open("shells/$shellFolder/index.html").close()
                } catch (_: Exception) {
                    // В папке нет index.html - пропускаем
                    return@forEach
                }

                val manifest = try {
                    context.assets.open("shells/$shellFolder/manifest.json").bufferedReader().use {
                        json.decodeFromString<ShellManifest>(it.readText())
                    }
                } catch (_: Exception) {
                    null
                }

                val previewBase64 = loadPreviewFromAssets(shellFolder, manifest?.preview)

                shells.add(
                    Shell(
                        name = shellFolder,
                        isDefault = shellFolder == "default",
                        sourcePath = "shells/$shellFolder",
                        isAsset = true,
                        displayName = manifest?.name ?: when (shellFolder) {
                            "default" -> context.getString(R.string.shell_default_name)
                            else -> shellFolder.replaceFirstChar { it.uppercase() }
                        },
                        isCustom = false,
                        version = manifest?.version,
                        author = manifest?.author,
                        previewBase64 = previewBase64,
                        orientation = manifest?.orientation,
                        repoUrl = manifest?.repoUrl,
                        minQutyLaunchVersion = manifest?.minQutyLaunchVersion
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return shells
    }

    /**
     * Проверяет, является ли кастомная оболочка обновлением встроенной
     * @param shell оболочка для проверки
     * @return true если это кастомная оболочка с именем, совпадающим с встроенной
     */
    fun isBuiltInShellUpdate(shell: Shell): Boolean {
        if (!shell.isCustom) return false
        val builtInShells = getBuiltInShells()
        return builtInShells.any { it.name == shell.name }
    }

    private fun readManifestFromZip(zipFile: File): ShellManifest? {
        return try {
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: return null
                zip.getInputStream(entry).bufferedReader().use {
                    json.decodeFromString<ShellManifest>(it.readText())
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

    private fun loadPreviewFromAssets(shellFolder: String, previewPath: String?): String? {
        if (previewPath.isNullOrEmpty()) return null

        return try {
            val inputStream = context.assets.open("shells/$shellFolder/$previewPath")
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

    fun getShellToActivate(): Shell {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()

        shells.find { it.name == activeShellId }?.let { return it }

        val defaultShellId = configManager.getDefaultShell()
        shells.find { it.name == defaultShellId }?.let { return it }

        shells.find { it.name == "default" }?.let { return it }

        return shells.firstOrNull() ?: Shell(
            name = "Default",
            isDefault = true,
            sourcePath = "shells/default",
            isAsset = true,
            displayName = context.getString(R.string.shell_default_name)
        )
    }

    /**
     * Принудительно перезагружает активную оболочку из файла
     * Используется после обновления оболочки
     */
    fun reloadActiveShell() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()
        activeShell = shells.find { it.name == activeShellId }

        // Сохраняем принудительную ориентацию в SharedPreferences, если она есть
        saveForcedOrientation()
    }

    fun getActiveShell(): Shell? {
        // Если в памяти нет, пробуем загрузить из конфига
        if (activeShell == null) {
            loadActiveShellFromConfig()
        }
        return activeShell
    }

    /**
     * Устанавливает активную оболочку
     * @param shell оболочка для активации
     */
    suspend fun setActiveShell(shell: Shell) = withContext(Dispatchers.IO) {
        // Сохраняем в конфиг
        configManager.setActiveShell(shell.name)

        // Обновляем в памяти
        activeShell = shell

        // Сохраняем принудительную ориентацию в SharedPreferences
        // withContext(Dispatchers.Main) для доступа к SharedPreferences
        withContext(Dispatchers.Main) {
            saveForcedOrientation()
        }

        // Очищаем директорию активной оболочки
        clearActiveDir()

        // Если это не asset оболочка - распаковываем в папку с именем оболочки
        if (!shell.isAsset) {
            val extractDir = File(activeShellDir, shell.name)
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }
            // Распаковка в фоновом потоке (уже в Dispatchers.IO)
            unzipShell(shell.sourcePath, extractDir)
        }
    }

    private fun clearActiveDir() {
        if (activeShellDir.exists()) {
            activeShellDir.deleteRecursively()
            activeShellDir.mkdirs()
        }
    }

    /**
     * Распаковывает ZIP-архив оболочки
     * Теперь выполняется в фоновом потоке (вызывается только из setActiveShell)
     */
    private fun unzipShell(zipPath: String, outputDir: File) {
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

    /**
     * Удаляет кастомную оболочку по имени
     * @param name имя оболочки (без расширения)
     * @return true если удаление успешно, false если файл не найден или ошибка
     */
    fun deleteShellByName(name: String): Boolean {
        return try {
            val fileName = "$name${SHELL_EXTENSION_WITH_DOT}"
            val file = File(customShellsDir, fileName)

            if (!file.exists()) {
                return false
            }

            val deleted = file.delete()

            // Если оболочка была активной — сбрасываем активную оболочку
            if (deleted) {
                val currentActive = getActiveShell()
                if (currentActive?.name == name && currentActive.isCustom) {
                    // Перезагружаем активную оболочку из конфига
                    loadActiveShellFromConfig()
                }
            }

            deleted
        } catch (_: Exception) {
            false
        }
    }
}