// *** core/managers/ShellManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.core.content.edit
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val version: String? = null,
    val author: String? = null,
    val previewBase64: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    val minQutyLaunchVersion: String? = null
)

/**
 * Структура manifest.json оболочки
 */
@Serializable
data class ShellManifest(
    val name: String,
    val author: String = "",
    val version: String = "0.0.1",
    val preview: String? = null,
    val orientation: String? = null,
    val repoUrl: String? = null,
    val minQutyLaunchVersion: String? = null
)

class ShellManager(
    private val context: Context,
    private val configManager: ConfigManager
) {

    companion object {
        /** Расширение файла оболочки (с точкой) - из конфига */
        const val SHELL_EXTENSION_WITH_DOT = CoreConfig.SHELL_EXTENSION_WITH_DOT

        /** Поддерживаемые расширения файлов оболочки (без точки) - из конфига */
        val SHELL_EXTENSIONS = CoreConfig.SHELL_EXTENSIONS

        /** Поддерживаемые расширения файлов оболочки (с точкой) - из конфига */
        val SHELL_EXTENSIONS_WITH_DOT = CoreConfig.SHELL_EXTENSIONS_WITH_DOT
    }

    // Хранилище и менеджеры
    private val storageManager = StorageManager(context)

    // Активная оболочка в памяти
    private var activeShell: Shell? = null

    // JSON парсер
    private val json = Json { ignoreUnknownKeys = true }

    // Директория активной оболочки (для распакованных файлов)
    private val activeShellDir: File by lazy {
        File(context.filesDir, "shells/active")
    }

    init {
        // Создаём директорию для активной оболочки
        if (!activeShellDir.exists()) {
            activeShellDir.mkdirs()
        }

        // Загружаем активную оболочку из конфига
        loadActiveShellFromConfig()
    }

    // ============================================================
    // ЗАГРУЗКА АКТИВНОЙ ОБОЛОЧКИ
    // ============================================================

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

        // Сохраняем принудительную ориентацию
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

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Сохраняет принудительную ориентацию текущей оболочки в SharedPreferences
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

    // ============================================================
    // ПОЛУЧЕНИЕ ОБОЛОЧЕК
    // ============================================================

    /**
     * Получить список всех доступных оболочек
     * Встроенные оболочки заменяются кастомными версиями с тем же именем
     */
    fun getAvailableShells(): List<Shell> {
        val builtInShells = getBuiltInShells()
        val customShells = getCustomShells()

        val result = mutableListOf<Shell>()

        // Для каждой встроенной оболочки проверяем, есть ли кастомная версия
        builtInShells.forEach { builtIn ->
            val customVersion = customShells.find { it.name == builtIn.name }
            if (customVersion != null) {
                result.add(customVersion)
            } else {
                result.add(builtIn)
            }
        }

        // Добавляем остальные кастомные оболочки
        customShells.forEach { custom ->
            if (result.none { it.name == custom.name }) {
                result.add(custom)
            }
        }

        return result
    }

    /**
     * Получить кастомные оболочки из хранилища
     */
    private fun getCustomShells(): List<Shell> {
        val shells = mutableListOf<Shell>()

        // Получаем все файлы оболочек из директории SHELLS
        val shellFiles = storageManager.list(
            directory = StorageDirectory.SHELLS,
            filter = { fileName ->
                SHELL_EXTENSIONS.any { ext ->
                    fileName.endsWith(".$ext", ignoreCase = true)
                }
            }
        )

        shellFiles.forEach { file ->
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

    // ============================================================
    // ЧТЕНИЕ MANIFEST И ПРЕВЬЮ
    // ============================================================

    /**
     * Читает manifest.json из ZIP-файла оболочки
     */
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

    /**
     * Загружает превью из ZIP-файла оболочки
     */
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

    /**
     * Загружает превью из assets
     */
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

    /**
     * Конвертирует Bitmap в Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    // ============================================================
    // АКТИВНАЯ ОБОЛОЧКА
    // ============================================================

    /**
     * Получить оболочку для активации
     */
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
     * Получить активную оболочку
     */
    fun getActiveShell(): Shell? {
        if (activeShell == null) {
            loadActiveShellFromConfig()
        }
        return activeShell
    }

    /**
     * Принудительно перезагружает активную оболочку из файла
     */
    fun reloadActiveShell() {
        val shells = getAvailableShells()
        val activeShellId = configManager.getActiveShell()
        activeShell = shells.find { it.name == activeShellId }
        saveForcedOrientation()
    }

    /**
     * Устанавливает активную оболочку
     */
    suspend fun setActiveShell(shell: Shell) = withContext(Dispatchers.IO) {
        // Сохраняем в конфиг
        configManager.setActiveShell(shell.name)

        // Обновляем в памяти
        activeShell = shell

        // Сохраняем принудительную ориентацию
        withContext(Dispatchers.Main) {
            saveForcedOrientation()
        }

        // Очищаем директорию активной оболочки
        clearActiveDir()

        // Если это не asset оболочка — распаковываем
        if (!shell.isAsset) {
            val extractDir = File(activeShellDir, shell.name)
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }
            unzipShell(shell.sourcePath, extractDir)
        }
    }

    // ============================================================
    // РАСПАКОВКА ОБОЛОЧКИ
    // ============================================================

    /**
     * Очищает директорию активной оболочки
     */
    private suspend fun clearActiveDir() {
        if (activeShellDir.exists()) {
            // Удаляем содержимое директории через StorageManager
            // Получаем File объект и удаляем его
            val activeDir = storageManager.get("shells/active")
            if (activeDir.exists()) {
                storageManager.remove(activeDir)
                activeShellDir.mkdirs()
            }
        }
    }

    /**
     * Распаковывает ZIP-архив оболочки
     */
    private suspend fun unzipShell(zipPath: String, outputDir: File) {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return

        try {
            withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            LoggerManager.e("ShellManager", context.getString(R.string.log_shell_manager_unzip_error, e.message))
        }
    }

    // ============================================================
    // ПРИНУДИТЕЛЬНАЯ ОРИЕНТАЦИЯ
    // ============================================================

    /**
     * Получить принудительную ориентацию из активной оболочки
     */
    fun getForcedOrientationFromActiveShell(): String? {
        val shell = getActiveShell() ?: return null
        return when (val orientation = shell.orientation) {
            "portrait", "landscape", "sensor", "user" -> orientation
            else -> null
        }
    }

    /**
     * Проверяет, задаёт ли текущая оболочка принудительную ориентацию
     */
    fun hasForcedOrientation(): Boolean {
        val forced = getForcedOrientationFromActiveShell()
        return forced == "portrait" || forced == "landscape"
    }

    // ============================================================
    // УПРАВЛЕНИЕ ОБОЛОЧКАМИ
    // ============================================================

    /**
     * Проверяет, является ли кастомная оболочка обновлением встроенной
     */
    fun isBuiltInShellUpdate(shell: Shell): Boolean {
        if (!shell.isCustom) return false
        val builtInShells = getBuiltInShells()
        return builtInShells.any { it.name == shell.name }
    }

    /**
     * Удаляет кастомную оболочку по имени
     */
    suspend fun deleteShellByName(name: String): Boolean {
        return try {
            val fileName = "$name${SHELL_EXTENSION_WITH_DOT}"
            val deleted = storageManager.remove(
                directory = StorageDirectory.SHELLS,
                name = fileName
            )

            // Если оболочка была активной — перезагружаем
            if (deleted) {
                val currentActive = getActiveShell()
                if (currentActive?.name == name && currentActive.isCustom) {
                    loadActiveShellFromConfig()
                }
            }

            deleted
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Получает URI для оболочки (для шаринга)
     */
    fun getShellUri(shell: Shell): android.net.Uri? {
        val file = File(shell.sourcePath)
        return if (file.exists()) {
            storageManager.getUri(file)
        } else {
            null
        }
    }
}