// *** core/managers/StorageManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import by.quty.launch.R
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
 * - logs: .../logs/
 * - updates: .../updates/
 * - cache: .../cache/
 * - temp: .../temp/
 * - exports: .../exports/
 * - backups: .../backups/
 */
class StorageManager(private val context: Context) {

    companion object {
        // Имена директорий
        private const val DIR_SHELLS = "shells"
        private const val DIR_LOGS = "logs"
        private const val DIR_UPDATES = "updates"
        private const val DIR_CACHE = "cache"
        private const val DIR_TEMP = "temp"
        private const val DIR_EXPORTS = "exports"
        private const val DIR_BACKUPS = "backups"

        // Расширения файлов
        private const val EXT_SHELL = ".qutyshell"
        private const val EXT_LOG = ".json"
        private const val EXT_APK = ".apk"
        private const val EXT_TEMP = ".tmp"
        private const val EXT_BACKUP = ".backup"
    }

    // ============================================================
    // ДИРЕКТОРИИ
    // ============================================================

    private val baseDir: File by lazy { context.filesDir }
    private val shellsDir: File by lazy { File(baseDir, DIR_SHELLS) }
    private val logsDir: File by lazy { File(baseDir, DIR_LOGS) }
    private val updatesDir: File by lazy { File(baseDir, DIR_UPDATES) }
    private val cacheDir: File by lazy { File(baseDir, DIR_CACHE) }
    private val tempDir: File by lazy { File(baseDir, DIR_TEMP) }
    private val exportsDir: File by lazy { File(baseDir, DIR_EXPORTS) }
    private val backupsDir: File by lazy { File(baseDir, DIR_BACKUPS) }

    private val allDirs: List<File> by lazy {
        listOf(shellsDir, logsDir, updatesDir, cacheDir, tempDir, exportsDir, backupsDir)
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
                val created = dir.mkdirs()
                if (created) {
                    LoggerManager.d("StorageManager", context.getString(R.string.log_storage_dir_created, dir.absolutePath))
                } else {
                    LoggerManager.e("StorageManager", context.getString(R.string.log_storage_dir_create_failed, dir.absolutePath))
                }
            }
        }
    }

    // ============================================================
    // БАЗОВЫЕ ОПЕРАЦИИ
    // ============================================================

    /**
     * Получить файл по пути
     * @param path путь к файлу (абсолютный или относительно baseDir)
     * @return File объект (существование не проверяется)
     */
    fun get(path: String): File {
        return if (path.startsWith("/") || path.contains(":")) {
            File(path)
        } else {
            File(baseDir, path)
        }
    }

    /**
     * Получить файл в указанной директории
     * @param directory директория (shells, logs, updates, cache, temp, exports, backups)
     * @param name имя файла
     * @return File объект
     */
    fun get(directory: StorageDirectory, name: String): File {
        return File(getDirectory(directory), name)
    }

    /**
     * Получить содержимое файла как строку
     * @param file файл для чтения
     * @return содержимое файла или null при ошибке
     */
    suspend fun getString(file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                return@withContext null
            }
            file.readText()
        } catch (e: Exception) {
            LoggerManager.e("StorageManager", context.getString(R.string.log_storage_file_read_error, e.message))
            null
        }
    }

    /**
     * Получить содержимое файла как строку по имени в директории
     */
    suspend fun getString(directory: StorageDirectory, name: String): String? {
        return getString(get(directory, name))
    }

    /**
     * Получить список файлов в директории с фильтром
     * @param directory директория
     * @param filter фильтр для имен файлов (опционально)
     * @param extension фильтр по расширению (опционально)
     * @param sorted сортировать по имени
     * @return список файлов
     */
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

    /**
     * Получить URI для файла (для FileProvider)
     * @param file файл
     * @return Uri или null при ошибке
     */
    fun getUri(file: File): Uri? {
        return try {
            if (!file.exists()) {
                LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                return null
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            LoggerManager.e("StorageManager", context.getString(R.string.log_storage_uri_error, e.message))
            null
        }
    }

    // ============================================================
    // ЗАПИСЬ (SET)
    // ============================================================

    /**
     * Сохранить строку в файл
     * @param file файл для сохранения
     * @param content содержимое
     * @param overwrite перезаписывать существующий файл
     * @return true при успехе
     */
    suspend fun set(file: File, content: String, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                file.writeText(content)
                LoggerManager.d("StorageManager", context.getString(R.string.log_storage_file_saved, file.absolutePath))
                true
            } catch (e: Exception) {
                LoggerManager.e("StorageManager", context.getString(R.string.log_storage_file_save_error, e.message))
                false
            }
        }

    /**
     * Сохранить строку в файл по имени в директории
     */
    suspend fun set(directory: StorageDirectory, name: String, content: String, overwrite: Boolean = true): Boolean {
        return set(get(directory, name), content, overwrite)
    }

    /**
     * Сохранить InputStream в файл
     * @param file файл для сохранения
     * @param inputStream поток с данными
     * @param overwrite перезаписывать существующий файл
     * @return true при успехе
     */
    suspend fun set(file: File, inputStream: InputStream, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    inputStream.copyTo(output)
                }
                LoggerManager.d("StorageManager", context.getString(R.string.log_storage_file_stream_saved, file.absolutePath))
                true
            } catch (e: Exception) {
                LoggerManager.e("StorageManager", context.getString(R.string.log_storage_file_stream_save_error, e.message))
                false
            }
        }

    /**
     * Сохранить InputStream в файл по имени в директории
     */
    suspend fun set(directory: StorageDirectory, name: String, inputStream: InputStream, overwrite: Boolean = true): Boolean {
        return set(get(directory, name), inputStream, overwrite)
    }

    /**
     * Сохранить байты в файл
     * @param file файл для сохранения
     * @param data байтовый массив
     * @param overwrite перезаписывать существующий файл
     * @return true при успехе
     */
    suspend fun set(file: File, data: ByteArray, overwrite: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (file.exists() && !overwrite) {
                    LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                    return@withContext false
                }
                file.parentFile?.mkdirs()
                file.writeBytes(data)
                LoggerManager.d("StorageManager", context.getString(R.string.log_storage_file_bytes_saved, file.absolutePath))
                true
            } catch (e: Exception) {
                LoggerManager.e("StorageManager", context.getString(R.string.log_storage_file_bytes_save_error, e.message))
                false
            }
        }

    // ============================================================
    // ПРОВЕРКА СУЩЕСТВОВАНИЯ (EXISTS)
    // ============================================================

    /**
     * Проверить, существует ли файл
     * @param file файл для проверки
     * @return true если существует
     */
    fun exists(file: File): Boolean = file.exists()

    /**
     * Проверить, существует ли файл по имени в директории
     */
    fun exists(directory: StorageDirectory, name: String): Boolean {
        return exists(get(directory, name))
    }

    /**
     * Проверить, существует ли директория
     */
    fun exists(directory: StorageDirectory): Boolean {
        return getDirectory(directory).exists()
    }

    // ============================================================
    // УДАЛЕНИЕ (REMOVE)
    // ============================================================

    /**
     * Удалить файл
     * @param file файл для удаления
     * @return true при успехе
     */
    suspend fun remove(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, file.absolutePath))
                return@withContext false
            }
            val deleted = file.delete()
            if (deleted) {
                LoggerManager.d("StorageManager", context.getString(R.string.log_storage_file_deleted, file.absolutePath))
            }
            deleted
        } catch (e: Exception) {
            LoggerManager.e("StorageManager", context.getString(R.string.log_storage_file_delete_error, e.message))
            false
        }
    }

    /**
     * Удалить файл по имени в директории
     */
    suspend fun remove(directory: StorageDirectory, name: String): Boolean {
        return remove(get(directory, name))
    }

    /**
     * Удалить директорию со всем содержимым
     * @param directory директория
     * @param recursive удалять рекурсивно
     * @return true при успехе
     */
    suspend fun remove(directory: StorageDirectory, recursive: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val dir = getDirectory(directory)
                if (!dir.exists()) {
                    LoggerManager.w("StorageManager", context.getString(R.string.log_storage_file_not_exists, dir.absolutePath))
                    return@withContext false
                }
                val deleted = if (recursive) {
                    dir.deleteRecursively()
                } else {
                    dir.delete()
                }
                if (deleted) {
                    LoggerManager.d("StorageManager", context.getString(R.string.log_storage_dir_deleted, dir.absolutePath))
                    // Пересоздаём директорию
                    dir.mkdirs()
                }
                deleted
            } catch (e: Exception) {
                LoggerManager.e("StorageManager", context.getString(R.string.log_storage_dir_delete_error, e.message))
                false
            }
        }

    /**
     * Удалить все файлы в директории с фильтром
     */
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
            if (allDeleted) {
                LoggerManager.d("StorageManager", context.getString(R.string.log_storage_files_deleted, directory.name))
            }
            allDeleted
        } catch (e: Exception) {
            LoggerManager.e("StorageManager", context.getString(R.string.log_storage_files_delete_error, e.message))
            false
        }
    }

    // ============================================================
    // ДОПОЛНИТЕЛЬНЫЕ ОПЕРАЦИИ
    // ============================================================

    /**
     * Получить размер директории
     * @param directory директория для подсчёта размера
     * @return размер в байтах
     */
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

    /**
     * Создать временный файл
     * @param prefix префикс имени
     * @param extension расширение (без точки)
     * @return файл
     */
    fun createTempFile(prefix: String, extension: String = EXT_TEMP): File {
        val timestamp = System.currentTimeMillis()
        return File(tempDir, "$prefix-$timestamp.$extension")
    }

    /**
     * Проверить, является ли файл действительным
     * @param file файл для проверки
     * @param minSize минимальный размер в байтах
     * @return true если файл существует и не пустой
     */
    fun isValidFile(file: File, minSize: Long = 0): Boolean {
        return file.exists() && file.isFile && file.length() > minSize
    }

    /**
     * Форматирует размер в читаемый вид
     */
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

    /**
     * Получить директорию по enum
     */
    fun getDirectory(directory: StorageDirectory): File {
        return when (directory) {
            StorageDirectory.SHELLS -> shellsDir
            StorageDirectory.LOGS -> logsDir
            StorageDirectory.UPDATES -> updatesDir
            StorageDirectory.CACHE -> cacheDir
            StorageDirectory.TEMP -> tempDir
            StorageDirectory.EXPORTS -> exportsDir
            StorageDirectory.BACKUPS -> backupsDir
            StorageDirectory.BASE -> baseDir
        }
    }

    /**
     * Получить расширение по типу файла
     */
    fun getExtension(fileType: StorageFileType): String {
        return when (fileType) {
            StorageFileType.SHELL -> EXT_SHELL
            StorageFileType.LOG -> EXT_LOG
            StorageFileType.APK -> EXT_APK
            StorageFileType.TEMP -> EXT_TEMP
            StorageFileType.BACKUP -> EXT_BACKUP
        }
    }

    /**
     * Получить полное имя файла с расширением
     */
    fun getFullName(name: String, fileType: StorageFileType): String {
        return "$name${getExtension(fileType)}"
    }

    /**
     * Получить файл по имени и типу в соответствующей директории
     */
    fun getFile(name: String, fileType: StorageFileType): File {
        val dir = when (fileType) {
            StorageFileType.SHELL -> StorageDirectory.SHELLS
            StorageFileType.LOG -> StorageDirectory.LOGS
            StorageFileType.APK -> StorageDirectory.UPDATES
            StorageFileType.TEMP -> StorageDirectory.TEMP
            StorageFileType.BACKUP -> StorageDirectory.BACKUPS
        }
        return get(dir, getFullName(name, fileType))
    }

    /**
     * Удаляет файл по имени и типу
     */
    suspend fun remove(name: String, fileType: StorageFileType): Boolean {
        val file = getFile(name, fileType)
        return remove(file)
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
    LOGS,
    UPDATES,
    CACHE,
    TEMP,
    EXPORTS,
    BACKUPS
}

/**
 * Типы файлов
 */
enum class StorageFileType {
    SHELL,      // .qutyshell
    LOG,        // .json
    APK,        // .apk
    TEMP,       // .tmp
    BACKUP      // .backup
}