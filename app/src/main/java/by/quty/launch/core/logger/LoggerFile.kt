// *** core/logger/LoggerFile.kt *** //
package by.quty.launch.core.logger

import android.content.Context
import android.os.Environment
import by.quty.launch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ядро записи логов в файл с ротацией
 *
 * Работает независимо от состояния приложения
 * Пишет логи ВСЕГДА (если включено в настройках)
 * Автоматическая ротация по размеру и количеству файлов
 */
object LoggerFile {

    private const val LOGS_DIR_NAME = "Logs"
    private const val FILE_PREFIX = "log_"
    private const val FILE_EXTENSION = ".json"
    private const val VERSION = 1

    // Настройки по умолчанию
    private var persistEnabled = true
    private var maxFiles = 5
    private var maxSizeMB = 5

    // Контекст (инициализируется при запуске)
    private lateinit var appContext: Context

    // Директория логов
    private lateinit var logsDir: File

    // Активный файл для записи
    private var currentLogFile: File? = null

    // Mutex для синхронизации записи в файл (предотвращает повреждение)
    private val mutex = Mutex()

    // JSON парсер
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Инициализация ядра логирования
     * @param context контекст приложения
     * @param maxFiles количество файлов
     * @param maxSizeMB максимальный размер файла в МБ
     * @param persistEnabled включено ли сохранение в файл
     */
    fun init(context: Context, maxFiles: Int = 5, maxSizeMB: Int = 5, persistEnabled: Boolean = true) {
        appContext = context.applicationContext
        this.maxFiles = maxFiles
        this.maxSizeMB = maxSizeMB
        this.persistEnabled = persistEnabled

        // Создаём директорию для логов
        val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
        logsDir = File(appDir, LOGS_DIR_NAME)

        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }

        // Если сохранение включено — подготавливаем активный файл
        if (persistEnabled) {
            prepareCurrentLogFile()
        }
    }

    /**
     * Запись лога в файл
     * @param level уровень лога
     * @param tag тег
     * @param message сообщение
     * @param source источник (Kotlin/WebView)
     */
    fun write(level: LogLevel, tag: String, message: String, source: String = "Kotlin") {
        // Если сохранение выключено — пропускаем
        if (!persistEnabled) return

        // Создаём запись
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            source = source
        )

        // Записываем в файл асинхронно
        CoroutineScope(Dispatchers.IO).launch {
            writeToFile(entry)
        }
    }

    /**
     * Записывает одну запись в файл с синхронизацией
     */
    private suspend fun writeToFile(entry: LogEntry) {
        // Используем Mutex для синхронизации доступа к файлу
        mutex.withLock {
            try {
                // Проверяем, нужно ли создать новый файл
                checkAndRotateIfNeeded()

                val file = currentLogFile ?: return@withLock

                // Читаем существующие логи из файла
                val existingLogs = readLogsFromFile(file)

                // Добавляем новый лог в начало списка (новые сверху)
                val updatedLogs = listOf(entry) + existingLogs

                // Ограничиваем количество логов в файле (1000 записей)
                val limitedLogs = if (updatedLogs.size > 1000) {
                    updatedLogs.take(1000)
                } else {
                    updatedLogs
                }

                // Формируем JSON
                val jsonContent = buildJson(limitedLogs)

                // Записываем в файл
                withContext(Dispatchers.IO) {
                    file.writeText(jsonContent)
                }

            } catch (_: Exception) {
                // Ошибки записи не должны крашить приложение
                // Используем android.util.Log, чтобы избежать бесконечного цикла
                android.util.Log.e("LoggerFile", appContext.getString(R.string.logger_file_write_error))
            }
        }
    }

    /**
     * Читает логи из файла
     * @param file файл для чтения
     * @return список логов
     */
    fun readLogsFromFile(file: File): List<LogEntry> {
        return try {
            if (!file.exists()) return emptyList()

            val content = file.readText()
            if (content.isEmpty()) return emptyList()

            // Парсим JSON
            val wrapper = json.decodeFromString<LogsWrapper>(content)
            wrapper.logs ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Проверяет, нужно ли создать новый файл (ротация)
     */
    private fun checkAndRotateIfNeeded() {
        val file = currentLogFile ?: run {
            prepareCurrentLogFile()
            return
        }

        // Проверяем размер файла
        if (file.exists() && file.length() >= maxSizeMB * 1024 * 1024L) {
            // Создаём новый файл
            rotateLogFile()
        }

        // Проверяем количество файлов
        cleanupOldFiles()
    }

    /**
     * Создаёт новый активный файл
     */
    private fun prepareCurrentLogFile() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val fileName = "$FILE_PREFIX${dateFormat.format(Date())}$FILE_EXTENSION"
        currentLogFile = File(logsDir, fileName)
    }

    /**
     * Ротирует файлы (создаёт новый активный файл)
     */
    private fun rotateLogFile() {
        prepareCurrentLogFile()
        cleanupOldFiles()
    }

    /**
     * Удаляет старые файлы, если их больше maxFiles
     */
    private fun cleanupOldFiles() {
        val files = getLogFiles()
        if (files.size <= maxFiles) return

        // Сортируем по дате создания (старые первыми)
        val sortedFiles = files.sortedBy { it.lastModified() }

        // Удаляем лишние файлы (самые старые)
        val filesToDelete = sortedFiles.take(files.size - maxFiles)
        filesToDelete.forEach { file ->
            file.delete()
        }
    }

    /**
     * Возвращает список всех файлов логов
     */
    fun getLogFiles(): List<File> {
        return logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Удаляет все файлы логов
     */
    fun clearAll() {
        try {
            getLogFiles().forEach { it.delete() }
            currentLogFile = null
            // Создаём новый активный файл
            prepareCurrentLogFile()
        } catch (_: Exception) {
            // Ошибка очистки — игнорируем
            android.util.Log.e("LoggerFile", appContext.getString(R.string.logger_file_clear_error))
        }
    }

    /**
     * Переконфигурирует ядро логирования
     */
    fun reconfigure(maxFiles: Int, maxSizeMB: Int) {
        this.maxFiles = maxFiles
        this.maxSizeMB = maxSizeMB

        // Применяем новые настройки ротации
        CoroutineScope(Dispatchers.IO).launch {
            cleanupOldFiles()
        }
    }

    /**
     * Включает/выключает сохранение логов в файл
     */
    fun setPersistEnabled(enabled: Boolean) {
        persistEnabled = enabled

        if (!enabled) {
            // Если выключаем — удаляем все файлы
            clearAll()
            currentLogFile = null
        } else {
            // Если включаем — создаём новый файл
            prepareCurrentLogFile()
        }
    }

    /**
     * Проверяет, включено ли сохранение логов в файл
     */
    fun isPersistEnabled(): Boolean = persistEnabled

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