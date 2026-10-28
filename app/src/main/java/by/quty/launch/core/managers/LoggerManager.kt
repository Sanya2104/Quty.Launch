// *** core/managers/LoggerManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import androidx.core.content.edit
import by.quty.launch.core.model.LoggerEntryModel
import by.quty.launch.core.model.LoggerLevelModel
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Менеджер логгера: in-memory буфер, фильтр, файлы логов.
 */
object LoggerManager {

    /** Максимум записей в буфере. */
    private const val MAX_ENTRIES = 500

    /** Имя prefs для настроек логгера. */
    private const val PREFS_NAME = "terminal_prefs"

    private const val KEY_ENABLED = "logger_enabled"
    private const val KEY_AUTO_SAVE = "logger_auto_save"
    private const val KEY_DETAILS_LIMIT = "logger_details_limit"
    private const val KEY_FILTER_LEVELS = "logger_filter_levels"
    private const val KEY_FILTER_TAGS = "logger_filter_tags"

    /** Лимит длины details по умолчанию. */
    const val DEFAULT_DETAILS_LIMIT = 3000

    /** Кольцевой буфер записей. */
    private val buffer = ConcurrentLinkedDeque<LoggerEntryModel>()

    /** Подписчики (фрагменты, адаптеры). */
    private val listeners = CopyOnWriteArrayList<LoggerListener>()

    @Volatile
    private var enabled = true

    @Volatile
    private var autoSave = false

    @Volatile
    private var detailsLimit = DEFAULT_DETAILS_LIMIT

    /** Кэш дефолтных наборов фильтров — чтобы не строить entries.toSet() на каждый вызов. */
    private val defaultLevels: Set<LoggerLevelModel> by lazy { LoggerLevelModel.entries.toSet() }
    private val defaultTags: Set<LoggerTagModel> by lazy { LoggerTagModel.entries.toSet() }

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    /** Инициализирует настройки из prefs. */
    fun init(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        autoSave = prefs.getBoolean(KEY_AUTO_SAVE, false)
        detailsLimit = prefs.getInt(KEY_DETAILS_LIMIT, DEFAULT_DETAILS_LIMIT)
    }

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    /** Записывает DEBUG-сообщение. */
    fun debug(tag: LoggerTagModel, message: String, details: String? = null) {
        log(LoggerLevelModel.DEBUG, tag, message, details)
    }

    /** Записывает INFO-сообщение. */
    fun info(tag: LoggerTagModel, message: String, details: String? = null) {
        log(LoggerLevelModel.INFO, tag, message, details)
    }

    /** Записывает WARN-сообщение (опционально с throwable). */
    fun warn(
        tag: LoggerTagModel,
        message: String,
        throwable: Throwable? = null,
        details: String? = null
    ) {
        val fullMessage = if (throwable != null) {
            "$message  ${throwable.message ?: throwable.javaClass.simpleName}"
        } else {
            message
        }
        log(LoggerLevelModel.WARN, tag, fullMessage, details)
    }

    /** Записывает ERROR-сообщение (опционально с throwable). */
    fun error(
        tag: LoggerTagModel,
        message: String,
        throwable: Throwable? = null,
        details: String? = null
    ) {
        val fullMessage = if (throwable != null) {
            "$message  ${throwable.message ?: throwable.javaClass.simpleName}"
        } else {
            message
        }
        log(LoggerLevelModel.ERROR, tag, fullMessage, details)
    }

    /** Внутренняя запись в буфер. */
    private fun log(
        level: LoggerLevelModel,
        tag: LoggerTagModel,
        message: String,
        details: String?
    ) {
        if (!enabled) return

        val entry = LoggerEntryModel(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            details = trimDetails(details)
        )

        buffer.addLast(entry)

        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst() ?: break
        }

        notifyListeners(entry)
    }

    /** Обрезает details до лимита. */
    private fun trimDetails(details: String?): String? {
        if (details.isNullOrEmpty()) return null

        return if (details.length > detailsLimit) {
            details.take(detailsLimit) + "\n…[truncated]"
        } else {
            details
        }
    }

    // ============================================================
    // ЧТЕНИЕ
    // ============================================================

    /** Возвращает количество записей в буфере. */
    fun size(): Int = buffer.size

    // ============================================================
    // ОЧИСТКА
    // ============================================================

    /** Полностью очищает буфер и уведомляет подписчиков. */
    fun clear() {
        buffer.clear()
        notifyCleared()
    }

    // ============================================================
    // ВКЛЮЧЕНИЕ / ВЫКЛЮЧЕНИЕ
    // ============================================================

    /** true, если логирование включено. */
    fun isEnabled(): Boolean = enabled

    /** Включает/выключает логирование и сохраняет флаг. */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_ENABLED, value) }
    }

    /** true, если включено автосохранение. */
    fun isAutoSaveEnabled(): Boolean = autoSave

    /** Включает/выключает автосохранение и сохраняет флаг. */
    fun setAutoSaveEnabled(context: Context, value: Boolean) {
        autoSave = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_AUTO_SAVE, value) }
    }

    /** Возвращает текущий лимит длины details. */
    fun getDetailsLimit(): Int = detailsLimit

    /** Сохраняет лимит длины details. */
    fun setDetailsLimit(context: Context, value: Int) {
        detailsLimit = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_DETAILS_LIMIT, value) }
    }

    // ============================================================
    // ФИЛЬТР: УРОВНИ
    // ============================================================

    /** Возвращает включённые уровни (по умолчанию — все). */
    fun getFilterLevels(context: Context): Set<LoggerLevelModel> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FILTER_LEVELS, null) ?: return defaultLevels

        if (raw.isEmpty()) return emptySet()

        return raw.split(",")
            .mapNotNull { name ->
                try {
                    LoggerLevelModel.valueOf(name)
                } catch (_: Exception) {
                    null
                }
            }
            .toSet()
    }

    /** Сохраняет набор включённых уровней. */
    fun setFilterLevels(context: Context, levels: Set<LoggerLevelModel>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = levels.joinToString(",") { it.name }
        prefs.edit { putString(KEY_FILTER_LEVELS, raw) }
    }

    // ============================================================
    // ФИЛЬТР: ТЕГИ
    // ============================================================

    /** Возвращает включённые теги (по умолчанию — все). */
    fun getFilterTags(context: Context): Set<LoggerTagModel> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FILTER_TAGS, null) ?: return defaultTags

        if (raw.isEmpty()) return emptySet()

        return raw.split(",")
            .mapNotNull { name ->
                try {
                    LoggerTagModel.valueOf(name)
                } catch (_: Exception) {
                    null
                }
            }
            .toSet()
    }

    /** Сохраняет набор включённых тегов. */
    fun setFilterTags(context: Context, tags: Set<LoggerTagModel>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = tags.joinToString(",") { it.name }
        prefs.edit { putString(KEY_FILTER_TAGS, raw) }
    }

    // ============================================================
    // ФИЛЬТР: ОБЩЕЕ
    // ============================================================

    /** true, если фильтр в дефолтном состоянии. */
    fun isFilterDefault(context: Context): Boolean {
        return getFilterLevels(context) == defaultLevels &&
                getFilterTags(context) == defaultTags
    }

    /** Возвращает записи «новые > старые», отфильтрованные по уровням и тегам. */
    fun getFilteredEntries(context: Context): List<LoggerEntryModel> {
        val enabledLevels = getFilterLevels(context)
        val enabledTags = getFilterTags(context)

        if (enabledLevels == defaultLevels && enabledTags == defaultTags) {
            return buffer.toList().asReversed()
        }

        return buffer.toList()
            .asReversed()
            .filter { it.level in enabledLevels && it.tag in enabledTags }
    }

    // ============================================================
    // ФАЙЛОВОЕ ХРАНИЛИЩЕ
    // ============================================================

    /** Сохраняет весь буфер в файл в LOGS. Возвращает имя файла или null. */
    suspend fun writeToFile(context: Context, fileName: String? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val entries = buffer.toList()
                if (entries.isEmpty()) return@withContext null

                val actualName = fileName ?: generateFileName()
                val content = formatEntries(entries)

                val storageManager = StorageManager(context.applicationContext)
                val success = storageManager.set(
                    directory = StorageDirectory.LOGS,
                    name = actualName,
                    content = content,
                    overwrite = true
                )

                if (success) actualName else null
            } catch (_: Exception) {
                null
            }
        }

    /** Возвращает список файлов логов, новые сверху. */
    fun listLogFiles(context: Context): List<File> {
        return try {
            val storageManager = StorageManager(context.applicationContext)
            storageManager.list(
                directory = StorageDirectory.LOGS,
                extension = "log",
                sorted = false
            ).sortedByDescending { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Удаляет файл лога по имени. */
    suspend fun deleteLogFile(context: Context, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val storageManager = StorageManager(context.applicationContext)
                storageManager.remove(
                    directory = StorageDirectory.LOGS,
                    name = fileName
                )
            } catch (_: Exception) {
                false
            }
        }

    /** Удаляет все файлы логов. */
    suspend fun deleteAllLogFiles(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val storageManager = StorageManager(context.applicationContext)
            storageManager.removeAll(StorageDirectory.LOGS)
        } catch (_: Exception) {
            false
        }
    }

    /** Форматирует записи для файла: `[HH:mm:ss.SSS] L [TAG] message`. */
    private fun formatEntries(entries: List<LoggerEntryModel>): String {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            entries.forEach { entry ->
                append("[")
                append(formatter.format(Date(entry.timestamp)))
                append("] ")
                append(entry.level.letter)
                append(" [")
                append(entry.tag.displayName)
                append("] ")
                appendLine(entry.message)

                entry.details?.let { details ->
                    details.lines().forEach { line ->
                        append("    ")
                        appendLine(line)
                    }
                }
            }
        }
    }

    /** Имя файла: log_YYYY-MM-DD_HH-mm-ss.log */
    private fun generateFileName(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "log_${formatter.format(Date())}.log"
    }

    // ============================================================
    // ПОДПИСКА
    // ============================================================

    /** Подписывает listener на новые записи. */
    fun subscribe(listener: LoggerListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /** Отписывает listener. */
    fun unsubscribe(listener: LoggerListener) {
        listeners.remove(listener)
    }

    /** Уведомляет подписчиков о новой записи. */
    private fun notifyListeners(entry: LoggerEntryModel) {
        listeners.forEach { it.onEntryAdded(entry) }
    }

    /** Уведомляет подписчиков об очистке буфера. */
    private fun notifyCleared() {
        listeners.forEach { it.onCleared() }
    }

    // ============================================================
    // ИНТЕРФЕЙС ПОДПИСЧИКА
    // ============================================================

    /** Колбэки подписчика логгера. */
    interface LoggerListener {
        /** Вызывается при добавлении новой записи. */
        fun onEntryAdded(entry: LoggerEntryModel)

        /** Вызывается при очистке буфера. */
        fun onCleared()
    }
}