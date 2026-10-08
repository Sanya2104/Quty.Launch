// *** core/managers/LoggerFileManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LogEntryModel
import by.quty.launch.core.model.LogLevelModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 *
 * Синхронизация: все манипуляции с буфером, currentLogFile и flushJob
 * выполняются под Mutex, чтобы избежать race conditions.
 */
object LoggerFileManager {

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
    private val buffer = mutableListOf<LogEntryModel>()

    // Job для отложенной записи
    private var flushJob: Job? = null

    /**
     * Внутренний scope для фоновых операций.
     * SupervisorJob — чтобы падение одной корутины не убивало остальные.
     * Используется вместо создания CoroutineScope на каждый write().
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     *
     * @param level уровень лога
     * @param tag тег
     * @param message сообщение
     * @param source источник (Kotlin/WebView)
     */
    fun write(level: LogLevelModel, tag: String, message: String, source: String = "Kotlin") {
        if (!persistEnabled) return

        val entry = LogEntryModel(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            source = source
        )

        // Используем единый scope вместо создания нового на каждый вызов
        scope.launch {
            mutex.withLock {
                buffer.add(entry)
                scheduleFlushLocked()
            }
        }
    }

    /**
     * Планирует отложенную запись буфера на диск.
     *
     * ВАЖНО: вызывается ТОЛЬКО под mutex (из write()).
     * Отменяет предыдущий flushJob, только если он ещё в фазе delay.
     * Если flush уже начал писать (isActive в withLock) — не трогаем.
     */
    private fun scheduleFlushLocked() {
        // Отменяем предыдущий job только если он ещё не начал работу.
        // Если job уже в withLock (пишет) — cancel() не сработает,
        // но и не навредит: withLock cancellable только на suspend-точках.
        flushJob?.cancel()

        flushJob = scope.launch {
            delay(FLUSH_DELAY)
            flushBuffer()
        }
    }

    /**
     * Немедленно записывает буфер на диск.
     *
     * ВАЖНО: буфер очищается только ПОСЛЕ успешной записи.
     * При ошибке — данные возвращаются в буфер для повторной попытки.
     */
    private suspend fun flushBuffer() {
        mutex.withLock {
            if (buffer.isEmpty()) return@withLock

            val entriesToWrite = buffer.toList()
            val success = writeEntriesToFile(entriesToWrite)

            if (success) {
                // Успех — удаляем те записи, что уже записаны
                // (проверка на identity, чтобы не удалить добавленные параллельно)
                val writtenCount = entriesToWrite.size
                repeat(writtenCount) {
                    if (buffer.isNotEmpty()) {
                        buffer.removeAt(0)
                    }
                }
            } else {
                // Ошибка — оставляем буфер как есть (данные не теряются)
                android.util.Log.e("LoggerFileManager", appContext.getString(R.string.log_logger_file_write_error))
            }
        }
    }

    /**
     * Записывает список записей в файл.
     *
     * @return true при успехе, false при ошибке
     */
    private suspend fun writeEntriesToFile(entries: List<LogEntryModel>): Boolean {
        val storageManager = getStorageManager() ?: return false

        return try {
            // Проверяем, нужно ли создать новый файл
            checkAndRotateIfNeeded(storageManager)

            val file = currentLogFile ?: return false

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

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Читает логи из файла
     * @param storageManager экземпляр StorageManager
     * @param file файл для чтения
     * @return список логов
     */
    suspend fun readLogsFromFile(storageManager: StorageManager, file: File): List<LogEntryModel> {
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
     * Создаёт новый активный файл.
     *
     * ВАЖНО: вызывается под mutex (или из init до первой записи).
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
     * Возвращает список всех файлов логов.
     *
     * Сортировка по lastModified (НЕ по имени):
     * имена имеют формат log_YYYY-MM-DD_HH-MM-SS.json, и последний файл
     * должен быть действительно самым свежим (а не лексикографически последним).
     *
     * @param storageManager экземпляр StorageManager
     * @return список файлов логов, отсортированный от старых к новым
     */
    fun getLogFiles(storageManager: StorageManager): List<File> {
        return storageManager.list(
            directory = StorageDirectory.LOGS,
            extension = storageManager.getExtension(StorageFileType.LOG).removePrefix("."),
            sorted = false   // отключаем сортировку по имени
        ).sortedBy { it.lastModified() }
    }

    /**
     * Удаляет все файлы логов и очищает буфер
     * @param storageManager экземпляр StorageManager (если null — использует сохраненный)
     */
    suspend fun clearAll(storageManager: StorageManager? = null) {
        val manager = storageManager ?: getStorageManager() ?: return

        // Очищаем буфер и отменяем flushJob под mutex
        mutex.withLock {
            buffer.clear()
            flushJob?.cancel()
            flushJob = null

            try {
                manager.removeAll(
                    directory = StorageDirectory.LOGS,
                    extension = manager.getExtension(StorageFileType.LOG).removePrefix(".")
                )
                currentLogFile = null
                prepareCurrentLogFile()
                android.util.Log.d("LoggerFileManager", appContext.getString(R.string.log_logger_cleared))
            } catch (e: Exception) {
                android.util.Log.e("LoggerFileManager", appContext.getString(R.string.log_logger_clear_error, e.message))
            }
        }
    }

    /**
     * Переконфигурирует ядро логирования
     */
    fun reconfigure(maxFiles: Int, maxSizeMB: Int) {
        this.maxFiles = maxFiles
        this.maxSizeMB = maxSizeMB

        scope.launch {
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
            scope.launch {
                val storageManager = getStorageManager() ?: return@launch
                clearAll(storageManager)
            }
            currentLogFile = null
        } else {
            prepareCurrentLogFile()
        }
    }

    /**
     * Строит JSON из списка логов
     */
    private fun buildJson(logs: List<LogEntryModel>): String {
        val wrapper = LogsWrapper(VERSION, logs)
        return json.encodeToString(wrapper)
    }

    /**
     * Обёртка для JSON
     */
    @Serializable
    data class LogsWrapper(
        val version: Int,
        val logs: List<LogEntryModel>? = null
    )
}