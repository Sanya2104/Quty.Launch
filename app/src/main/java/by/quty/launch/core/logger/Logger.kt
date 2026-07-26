// *** core/logger/Logger.kt *** //
package by.quty.launch.core.logger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import by.quty.launch.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ядро логирования
 * Синглтон для сбора, хранения и управления логами
 *
 * Использование:
 * Logger.d("MainActivity", "onCreate вызван")
 * Logger.e("UpdateManager", "Ошибка: ${e.message}")
 */
object Logger {

    // Максимальное количество логов в памяти
    private const val MAX_LOGS = 1000

    // Список всех логов (новые в начале)
    private val logs = mutableListOf<LogEntry>()

    // Флаг паузы (если true — логи не собираются)
    private var isPaused = false

    // Слушатели для обновления UI
    private val listeners = mutableListOf<LogListener>()

    // Контекст для доступа к ресурсам
    private lateinit var appContext: Context

    /**
     * Интерфейс для уведомления об изменениях в логах
     */
    interface LogListener {
        fun onLogAdded(entry: LogEntry)
        fun onLogsCleared()
    }

    /**
     * Инициализация логгера
     * Вызывается при запуске приложения
     * @param context контекст приложения
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        // Загружаем настройки сохранения в файл
        val prefs = context.getSharedPreferences("logger_prefs", Context.MODE_PRIVATE)
        val persistEnabled = prefs.getBoolean("persist_enabled", true)
        val maxFiles = prefs.getInt("max_files", 5)
        val maxSizeMB = prefs.getInt("max_size_mb", 5)

        // Инициализируем файловое ядро
        LoggerFile.init(context, maxFiles, maxSizeMB, persistEnabled)

        // Если сохранение включено — восстанавливаем логи из файла
        if (persistEnabled) {
            restoreLogsFromFile()
        }

        d("Logger", appContext.getString(R.string.logger_initialized))
    }

    /**
     * Восстанавливает логи из файла в память
     */
    private fun restoreLogsFromFile() {
        try {
            val logFiles = LoggerFile.getLogFiles()
            if (logFiles.isEmpty()) return

            // Берём самый свежий файл (первый в списке)
            val latestFile = logFiles.firstOrNull() ?: return
            val restoredLogs = LoggerFile.readLogsFromFile(latestFile)

            // Добавляем в память (новые сверху)
            synchronized(logs) {
                logs.clear()
                logs.addAll(restoredLogs)
                // Ограничиваем количество
                while (logs.size > MAX_LOGS) {
                    logs.removeAt(logs.size - 1)
                }
            }

            // Уведомляем слушателей
            CoroutineScope(Dispatchers.Main).launch {
                listeners.forEach { it.onLogsCleared() }
                restoredLogs.forEach { entry ->
                    listeners.forEach { it.onLogAdded(entry) }
                }
            }

            d("Logger", appContext.getString(R.string.logger_restored_from_file, restoredLogs.size))
        } catch (_: Exception) {
            e("Logger", appContext.getString(R.string.logger_restore_error))
        }
    }

    /**
     * Добавляет слушателя
     */
    fun addListener(listener: LogListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Удаляет слушателя
     */
    fun removeListener(listener: LogListener) {
        listeners.remove(listener)
    }

    /**
     * Уведомляет всех слушателей о добавлении лога
     */
    private fun notifyLogAdded(entry: LogEntry) {
        listeners.forEach { it.onLogAdded(entry) }
    }

    /**
     * Уведомляет всех слушателей об очистке логов
     */
    private fun notifyLogsCleared() {
        listeners.forEach { it.onLogsCleared() }
    }

    /**
     * Добавляет лог с уровнем DEBUG
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
    @Suppress("unused")
    fun d(tag: String, message: String) {
        addLog(LogLevel.DEBUG, tag, message)
    }

    /**
     * Добавляет лог с уровнем INFO
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
    @Suppress("unused")
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
    }

    /**
     * Добавляет лог с уровнем WARN
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
    @Suppress("unused")
    fun w(tag: String, message: String) {
        addLog(LogLevel.WARN, tag, message)
    }

    /**
     * Добавляет лог с уровнем ERROR
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
    fun e(tag: String, message: String) {
        addLog(LogLevel.ERROR, tag, message)
    }

    /**
     * Добавляет лог с уровнем ERROR и исключением
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     * @param throwable исключение
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        addLog(LogLevel.ERROR, tag, "$message: ${throwable.message}")
        // Также пишем в стандартный лог для обратной совместимости
        Log.e(tag, message, throwable)
    }

    /**
     * Добавляет лог из WebView (JavaScript)
     * @param level уровень из JS (log, info, warn, error)
     * @param message сообщение
     */
    fun fromWebView(level: String, message: String) {
        val logLevel = when (level.lowercase()) {
            "log" -> LogLevel.DEBUG
            "info" -> LogLevel.INFO
            "warn" -> LogLevel.WARN
            "error" -> LogLevel.ERROR
            else -> LogLevel.DEBUG
        }
        addLog(logLevel, "WebView", message, source = "WebView")
    }

    /**
     * Внутренний метод добавления лога
     */
    private fun addLog(level: LogLevel, tag: String, message: String, source: String = "Kotlin") {
        // Если пауза — не добавляем логи
        if (isPaused) return

        // Создаём запись
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            source = source
        )

        // 1. Записываем в файл (всегда, если включено)
        LoggerFile.write(level, tag, message, source)

        // 2. Добавляем в память (для UI) — новые в начало списка
        synchronized(logs) {
            logs.add(0, entry)
            // Если превышен лимит — удаляем самые старые (в конце)
            while (logs.size > MAX_LOGS) {
                logs.removeAt(logs.size - 1)
            }
        }

        // Уведомляем слушателей (в UI потоке)
        CoroutineScope(Dispatchers.Main).launch {
            notifyLogAdded(entry)
        }

        // Также пишем в стандартный лог для обратной совместимости
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }

    /**
     * Возвращает копию списка всех логов (новые сверху)
     */
    fun getLogs(): List<LogEntry> {
        return synchronized(logs) {
            logs.toList()
        }
    }

    /**
     * Возвращает количество логов
     */
    fun getLogCount(): Int {
        return synchronized(logs) {
            logs.size
        }
    }

    /**
     * Очищает все логи
     */
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }

        // Очищаем файлы
        LoggerFile.clearAll()

        CoroutineScope(Dispatchers.Main).launch {
            notifyLogsCleared()
        }
    }

    /**
     * Приостанавливает сбор логов
     */
    fun pause() {
        isPaused = true
    }

    /**
     * Возобновляет сбор логов
     */
    fun resume() {
        isPaused = false
    }

    /**
     * Проверяет, приостановлен ли сбор логов
     */
    fun isPaused(): Boolean = isPaused

    /**
     * Форматирует все логи в строку для копирования (новые сверху)
     * @return строку с логами
     */
    fun formatLogsForCopy(): String {
        val sb = StringBuilder()
        getLogs().forEach { entry ->
            sb.append("[${entry.getFormattedTime()}] ")
            sb.append("${entry.level.name}/${entry.tag}: ")
            sb.append(entry.message)
            sb.append(" (${entry.source})")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Форматирует все логи в строку для отправки (новые сверху)
     * @return строку с логами
     */
    fun formatLogsForShare(): String {
        val sb = StringBuilder()
        getLogs().forEach { entry ->
            sb.append("[${entry.getFormattedTime()}] ")
            sb.append("${entry.level.name}/${entry.tag}: ")
            sb.append(entry.message)
            sb.append(" (${entry.source})")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Сохраняет все логи в файл
     * @return путь к сохранённому файлу или null в случае ошибки
     */
    fun saveLogsToFile(): String? {
        return try {
            // Формируем имя файла: log_YYYY-MM-DD_HH-MM-SS.json
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val fileName = "log_${dateFormat.format(Date())}.json"

            val appDir = File(android.os.Environment.getExternalStorageDirectory(), "Quty.Launch")
            val logsDir = File(appDir, "Logs")

            if (!appDir.exists()) appDir.mkdirs()
            if (!logsDir.exists()) logsDir.mkdirs()

            val logFile = File(logsDir, fileName)

            // Форматируем логи для записи
            val content = formatLogsForFile()

            // Записываем в файл
            logFile.writeText(content)

            // Возвращаем путь к файлу
            logFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Форматирует все логи в строку для записи в файл
     * @return строку с логами
     */
    fun formatLogsForFile(): String {
        val sb = StringBuilder()
        sb.append("═══════════════════════════════════════════════════════════\n")
        sb.append("  Quty.Launch Log File\n")
        sb.append("  Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        sb.append("  Total logs: ${getLogCount()}\n")
        sb.append("═══════════════════════════════════════════════════════════\n\n")

        getLogs().forEach { entry ->
            sb.append("[${entry.getFormattedTime()}] ")
            sb.append("${entry.level.name}/${entry.tag}: ")
            sb.append(entry.message)
            sb.append(" (${entry.source})")
            sb.append("\n")
        }

        sb.append("\n═══════════════════════════════════════════════════════════\n")
        sb.append("  End of log file\n")
        sb.append("═══════════════════════════════════════════════════════════\n")

        return sb.toString()
    }
}