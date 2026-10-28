// *** core/managers/StorageManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Централизованный менеджер для работы с хранилищем
 * Универсальный API для всех операций с файлами
 *
 * Структура каталогов:
 * - base: Android/data/by.quty.launch/files/
 * - shells: .../shells/
 * - updates: .../updates/
 * - cache: .../cache/
 * - temp: .../temp/
 * - logs: .../logs/
 *
 * ВАЖНО: папки `exports` и `backups` больше не существуют —
 * backup'ы сохраняются во внешнее хранилище через SAF (ActivityResultContracts.CreateDocument).
 */
class StorageManager(private val context: Context) {

    companion object {
        // Имена директорий
        private const val DIR_SHELLS = "shells"
        private const val DIR_UPDATES = "updates"
        private const val DIR_CACHE = "cache"
        private const val DIR_TEMP = "temp"
        private const val DIR_LOGS = "logs"

        private const val EXT_TEMP = ".tmp"
    }

    // ============================================================
    // ДИРЕКТОРИИ
    // ============================================================

    private val baseDir: File by lazy { context.filesDir }
    private val shellsDir: File by lazy { File(baseDir, DIR_SHELLS) }
    private val updatesDir: File by lazy { File(baseDir, DIR_UPDATES) }
    private val cacheDir: File by lazy { File(baseDir, DIR_CACHE) }
    private val tempDir: File by lazy { File(baseDir, DIR_TEMP) }
    private val logsDir: File by lazy { File(baseDir, DIR_LOGS) }

    private val allDirs: List<File> by lazy {
        listOf(shellsDir, updatesDir, cacheDir, tempDir, logsDir)
    }

    init {
        ensureDirectories()
    }

    /**
     * Создаёт все необходимые директории
     */
    private fun ensureDirectories() {
        allDirs.forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    // ============================================================
    // БАЗОВЫЕ ОПЕРАЦИИ
    // ============================================================

    /** Возвращает File по строковому пути (абсолютному или относительно baseDir). */
    fun get(path: String): File {
        return if (path.startsWith("/") || path.contains(":")) {
            File(path)
        } else {
            File(baseDir, path)
        }
    }

    /** Возвращает File в указанной директории по имени. */
    fun get(directory: StorageDirectory, name: String): File {
        return File(getDirectory(directory), name)
    }

    /** Читает файл как строку (null при ошибке). */
    suspend fun getString(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext null
            }
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    /** Читает файл из директории как строку (null при ошибке). */
    suspend fun getString(directory: StorageDirectory, name: String): String? {
        return getString(get(directory, name))
    }

    /** Возвращает список файлов директории с фильтром/расширением/сортировкой. */
    fun list(
        directory: StorageDirectory,
        filter: ((String) -> Boolean)? = null,
        extension: String? = null,
        sorted: Boolean = true
    ): List<File> {
        val dir = getDirectory(directory)
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file ->
            file.isFile && (
                    (filter == null || filter(file.name)) &&
                            (extension == null || file.extension.equals(extension, ignoreCase = true))
                    )
        }?.let { files ->
            if (sorted) {
                files.sortedBy { it.name }
            } else {
                files.toList()
            }
        } ?: emptyList()
    }

    /** Возвращает content:// URI для файла через FileProvider (null при ошибке). */
    fun getUri(file: File): Uri? {
        return try {
            if (!file.exists()) {
                return null
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // ЗАПИСЬ (SET)
    // ============================================================

    /** Пишет строку в файл. */
    suspend fun set(file: File, content: String, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                file.writeText(content)
                true
            } catch (_: Exception) {
                false
            }
        }

    /** Пишет строку в файл в указанной директории. */
    suspend fun set(directory: StorageDirectory, name: String, content: String, overwrite: Boolean = true): Boolean {
        return set(get(directory, name), content, overwrite)
    }

    /** Копирует InputStream в файл. */
    suspend fun set(file: File, inputStream: InputStream, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    inputStream.copyTo(output)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    /** Копирует InputStream в файл в указанной директории. */
    suspend fun set(directory: StorageDirectory, name: String, inputStream: InputStream, overwrite: Boolean = true): Boolean {
        return set(get(directory, name), inputStream, overwrite)
    }

    /** Пишет байты в файл. */
    suspend fun set(file: File, data: ByteArray, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                file.writeBytes(data)
                true
            } catch (_: Exception) {
                false
            }
        }

    // ============================================================
    // ПРОВЕРКА СУЩЕСТВОВАНИЯ (EXISTS)
    // ============================================================

    /** true, если файл существует. */
    fun exists(file: File): Boolean = file.exists()

    /** true, если файл существует в указанной директории. */
    fun exists(directory: StorageDirectory, name: String): Boolean {
        return exists(get(directory, name))
    }

    /** true, если директория существует. */
    fun exists(directory: StorageDirectory): Boolean {
        return getDirectory(directory).exists()
    }

    // ============================================================
    // УДАЛЕНИЕ (REMOVE)
    // ============================================================

    /** Удаляет файл. */
    suspend fun remove(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext false
            }
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** Удаляет файл из директории. */
    suspend fun remove(directory: StorageDirectory, name: String): Boolean {
        return remove(get(directory, name))
    }

    /** Удаляет все файлы директории (с опциональным фильтром). */
    suspend fun removeAll(
        directory: StorageDirectory,
        filter: ((String) -> Boolean)? = null,
        extension: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val files = list(directory, filter, extension)
            var allDeleted = true
            files.forEach { file ->
                if (!file.delete()) {
                    allDeleted = false
                }
            }
            allDeleted
        } catch (_: Exception) {
            false
        }
    }

    // ============================================================
    // ДОПОЛНИТЕЛЬНЫЕ ОПЕРАЦИИ
    // ============================================================

    /** Возвращает суммарный размер директории в байтах. */
    fun getDirectorySize(directory: StorageDirectory): Long {
        val dir = getDirectory(directory)
        return if (dir.exists()) {
            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } else {
            0L
        }
    }

    /** Создаёт временный файл в TEMP. */
    fun createTempFile(prefix: String, extension: String = EXT_TEMP): File {
        val timestamp = System.currentTimeMillis()
        return File(tempDir, "$prefix-$timestamp.$extension")
    }

    /** true, если файл существует, это файл и он больше minSize. */
    fun isValidFile(file: File, minSize: Long = 0): Boolean {
        return file.exists() && file.isFile && file.length() > minSize
    }

    /** Форматирует размер в GB/MB/KB/B. */
    fun formatSize(size: Long): String {
        val locale = Locale.US
        return when {
            size >= 1024 * 1024 * 1024 -> String.format(locale, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            size >= 1024 * 1024 -> String.format(locale, "%.2f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format(locale, "%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    /** Возвращает File для директории по enum. */
    fun getDirectory(directory: StorageDirectory): File {
        return when (directory) {
            StorageDirectory.SHELLS -> shellsDir
            StorageDirectory.UPDATES -> updatesDir
            StorageDirectory.CACHE -> cacheDir
            StorageDirectory.TEMP -> tempDir
            StorageDirectory.LOGS -> logsDir
            StorageDirectory.BASE -> baseDir
        }
    }
}

// ============================================================
// ENUMS
// ============================================================

/**
 * Типы директорий
 */
enum class StorageDirectory {
    BASE,
    SHELLS,
    UPDATES,
    CACHE,
    TEMP,
    LOGS
}