// *** core/logger/Logger.kt *** //
package by.quty.launch.core.logger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // Список всех логов
    private val logs = mutableListOf<LogEntry>()

    // Флаг паузы (если true — логи не собираются)
    private var isPaused = false

    // Слушатели для обновления UI
    private val listeners = mutableListOf<LogListener>()

    /**
     * Интерфейс для уведомления об изменениях в логах
     */
    interface LogListener {
        fun onLogAdded(entry: LogEntry)
        fun onLogsCleared()
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
    fun d(tag: String, message: String) {
        addLog(LogLevel.DEBUG, tag, message)
    }

    /**
     * Добавляет лог с уровнем INFO
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
    fun i(tag: String, message: String) {
        addLog(LogLevel.INFO, tag, message)
    }

    /**
     * Добавляет лог с уровнем WARN
     * @param tag тег (обычно имя класса)
     * @param message сообщение
     */
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

        // Добавляем в список
        synchronized(logs) {
            logs.add(entry)
            // Если превышен лимит — удаляем самые старые
            while (logs.size > MAX_LOGS) {
                logs.removeAt(0)
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
     * Возвращает копию списка всех логов
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
     * Форматирует все логи в строку для копирования
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
     * Сохраняет все логи в файл
     * @return путь к сохранённому файлу или null в случае ошибки
     */
    fun saveLogsToFile(): String? {
        return try {
            // Создаём директорию Quty.Launch/Logs/
            val appDir = File(android.os.Environment.getExternalStorageDirectory(), "Quty.Launch")
            val logsDir = File(appDir, "Logs")

            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            // Формируем имя файла: log_YYYY-MM-DD_HH-MM-SS.txt
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val fileName = "log_${dateFormat.format(Date())}.txt"
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