// *** core/logger/LoggerFile.kt *** //
package by.quty.launch.core.logger

import android.content.Context
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.managers.StorageDirectory
import by.quty.launch.core.managers.StorageFileType
import by.quty.launch.core.managers.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.ref.WeakReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ядро записи логов в файл с ротацией
 *
 * Работает независимо от состояния приложения
 * Пишет логи ВСЕГДА (если включено в настройках)
 * Автоматическая ротация по размеру и количеству файлов
 *
 * Оптимизация: буферизированная запись — логи накапливаются в памяти
 * и записываются на диск пачками с задержкой
 */
object LoggerFile {

    // Техническая версия формата файла (оставляем локально)
    private const val VERSION = 1

    // Максимальное количество логов в одном файле (из конфига)
    private const val MAX_LOGS_IN_FILE = CoreConfig.LOGGER_MAX_IN_FILE

    // Задержка перед записью буфера (из конфига)
    private val FLUSH_DELAY: Duration = CoreConfig.LOGGER_FLUSH_DELAY_MS.milliseconds

    // Настройки по умолчанию (из конфига)
    private var persistEnabled = CoreConfig.LOGGER_PERSIST_ENABLED_BY_DEFAULT
    private var maxFiles = CoreConfig.LOGGER_MAX_FILES_DEFAULT
    private var maxSizeMB = CoreConfig.LOGGER_MAX_FILE_SIZE_MB_DEFAULT

    // StorageManager - хранится в WeakReference для предотвращения утечек памяти
    private var storageManagerRef: WeakReference<StorageManager>? = null

    // Активный файл для записи
    private var currentLogFile: File? = null

    // Mutex для синхронизации записи в файл
    private val mutex = Mutex()

    // Буфер для накопления логов перед записью
    private val buffer = mutableListOf<LogEntry>()

    // Job для отложенной записи
    private var flushJob: Job? = null

    // JSON парсер
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Контекст для доступа к ресурсам
    private lateinit var appContext: Context

    /**
     * Получает StorageManager из WeakReference
     * @return StorageManager или null, если сборщик мусора уже очистил ссылку
     */
    private fun getStorageManager(): StorageManager? {
        return storageManagerRef?.get()
    }

    /**
     * Инициализация ядра логирования
     * @param storageManager экземпляр StorageManager (хранится в WeakReference)
     * @param maxFiles количество файлов
     * @param maxSizeMB максимальный размер файла в МБ
     * @param persistEnabled включено ли сохранение в файл
     * @param context контекст приложения для логирования
     */
    fun init(
        storageManager: StorageManager,
        maxFiles: Int = CoreConfig.LOGGER_MAX_FILES_DEFAULT,
        maxSizeMB: Int = CoreConfig.LOGGER_MAX_FILE_SIZE_MB_DEFAULT,
        persistEnabled: Boolean = CoreConfig.LOGGER_PERSIST_ENABLED_BY_DEFAULT,
        context: Context
    ) {
        this.storageManagerRef = WeakReference(storageManager)
        this.maxFiles = maxFiles
        this.maxSizeMB = maxSizeMB
        this.persistEnabled = persistEnabled
        this.appContext = context.applicationContext

        if (persistEnabled) {
            prepareCurrentLogFile()
        }
    }

    /**
     * Запись лога в файл (с буферизацией)
     * @param level уровень лога
     * @param tag тег
     * @param message сообщение
     * @param source источник (Kotlin/WebView)
     */
    fun write(level: LogLevel, tag: String, message: String, source: String = "Kotlin") {
        if (!persistEnabled) return

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            source = source
        )

        // Добавляем в буфер и планируем запись
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                buffer.add(entry)
                scheduleFlush()
            }
        }
    }

    /**
     * Планирует отложенную запись буфера на диск
     */
    private fun scheduleFlush() {
        // Если уже есть запланированная запись — отменяем
        flushJob?.cancel()

        // Планируем новую запись с задержкой
        flushJob = CoroutineScope(Dispatchers.IO).launch {
            delay(FLUSH_DELAY)
            flushBuffer()
        }
    }

    /**
     * Немедленно записывает буфер на диск
     */
    private suspend fun flushBuffer() {
        mutex.withLock {
            if (buffer.isEmpty()) return@withLock

            val entriesToWrite = buffer.toList()
            buffer.clear()

            writeEntriesToFile(entriesToWrite)
        }
    }

    /**
     * Записывает список записей в файл
     */
    private suspend fun writeEntriesToFile(entries: List<LogEntry>) {
        val storageManager = getStorageManager() ?: return

        try {
            // Проверяем, нужно ли создать новый файл
            checkAndRotateIfNeeded(storageManager)

            val file = currentLogFile ?: return

            // Читаем существующие логи из файла
            val existingLogs = readLogsFromFile(storageManager, file)

            // Добавляем новые логи в начало списка (новые сверху)
            val updatedLogs = entries + existingLogs

            // Ограничиваем количество логов в файле
            val limitedLogs = if (updatedLogs.size > MAX_LOGS_IN_FILE) {
                updatedLogs.take(MAX_LOGS_IN_FILE)
            } else {
                updatedLogs
            }

            // Формируем JSON
            val jsonContent = buildJson(limitedLogs)

            // Сохраняем через StorageManager
            withContext(Dispatchers.IO) {
                storageManager.set(file, jsonContent, overwrite = true)
            }

        } catch (_: Exception) {
            android.util.Log.e("LoggerFile", appContext.getString(R.string.log_logger_file_write_error))
        }
    }

    /**
     * Читает логи из файла
     * @param storageManager экземпляр StorageManager
     * @param file файл для чтения
     * @return список логов
     */
    suspend fun readLogsFromFile(storageManager: StorageManager, file: File): List<LogEntry> {
        return try {
            if (!file.exists()) return emptyList()

            val content = storageManager.getString(file)
            if (content.isNullOrEmpty()) return emptyList()

            val wrapper = json.decodeFromString<LogsWrapper>(content)
            wrapper.logs ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Проверяет, нужно ли создать новый файл (ротация)
     */
    private suspend fun checkAndRotateIfNeeded(storageManager: StorageManager) {
        val file = currentLogFile ?: run {
            prepareCurrentLogFile()
            return
        }

        // Проверяем размер файла
        if (file.exists() && file.length() >= maxSizeMB * 1024 * 1024L) {
            rotateLogFile(storageManager)
        }

        cleanupOldFiles(storageManager)
    }

    /**
     * Создаёт новый активный файл
     */
    private fun prepareCurrentLogFile() {
        val storageManager = getStorageManager() ?: return
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
        val fileName = "log_${dateFormat.format(java.util.Date())}"
        currentLogFile = storageManager.getFile(fileName, StorageFileType.LOG)
    }

    /**
     * Ротирует файлы (создаёт новый активный файл)
     */
    private suspend fun rotateLogFile(storageManager: StorageManager) {
        // Принудительно сбрасываем буфер перед ротацией
        flushBuffer()
        prepareCurrentLogFile()
        cleanupOldFiles(storageManager)
    }

    /**
     * Удаляет старые файлы, если их больше maxFiles
     */
    private suspend fun cleanupOldFiles(storageManager: StorageManager) {
        val files = getLogFiles(storageManager)
        if (files.size <= maxFiles) return

        val sortedFiles = files.sortedBy { it.lastModified() }
        val filesToDelete = sortedFiles.take(files.size - maxFiles)

        filesToDelete.forEach { file ->
            storageManager.remove(file)
        }
    }

    /**
     * Возвращает список всех файлов логов
     * @param storageManager экземпляр StorageManager
     * @return список файлов логов
     */
    fun getLogFiles(storageManager: StorageManager): List<File> {
        return storageManager.list(
            directory = StorageDirectory.LOGS,
            extension = storageManager.getExtension(StorageFileType.LOG).removePrefix(".")
        )
    }

    /**
     * Удаляет все файлы логов и очищает буфер
     */
    suspend fun clearAll() {
        // Очищаем буфер
        mutex.withLock {
            buffer.clear()
            flushJob?.cancel()
        }

        val storageManager = getStorageManager() ?: return
        try {
            storageManager.removeAll(
                directory = StorageDirectory.LOGS,
                extension = storageManager.getExtension(StorageFileType.LOG).removePrefix(".")
            )
            currentLogFile = null
            prepareCurrentLogFile()
        } catch (_: Exception) {
            android.util.Log.e("LoggerFile", appContext.getString(R.string.log_logger_file_clear_error))
        }
    }

    /**
     * Переконфигурирует ядро логирования
     */
    fun reconfigure(maxFiles: Int, maxSizeMB: Int) {
        this.maxFiles = maxFiles
        this.maxSizeMB = maxSizeMB

        CoroutineScope(Dispatchers.IO).launch {
            val storageManager = getStorageManager() ?: return@launch
            cleanupOldFiles(storageManager)
        }
    }

    /**
     * Включает/выключает сохранение логов в файл
     */
    fun setPersistEnabled(enabled: Boolean) {
        persistEnabled = enabled

        if (!enabled) {
            CoroutineScope(Dispatchers.IO).launch {
                clearAll()
            }
            currentLogFile = null
        } else {
            prepareCurrentLogFile()
        }
    }

    /**
     * Строит JSON из списка логов
     */
    private fun buildJson(logs: List<LogEntry>): String {
        val wrapper = LogsWrapper(VERSION, logs)
        return json.encodeToString(wrapper)
    }

    /**
     * Обёртка для JSON
     */
    @Serializable
    data class LogsWrapper(
        val version: Int,
        val logs: List<LogEntry>? = null
    )
}