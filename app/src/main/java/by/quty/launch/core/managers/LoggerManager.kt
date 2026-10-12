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
 * Менеджер логгера Quty.Launch.
 *
 * Логгер — внутренний просмотрщик событий ядра. НЕ путать с системным LogCat:
 * у нас нет доступа к системному логу, поэтому мы собираем свой буфер —
 * записи, которые ядро само в него кладёт.
 *
 * Хранилище:
 * - in-memory кольцевой буфер (MAX_ENTRIES записей);
 * - файлы в StorageDirectory.LOGS — сохраняются по запросу из UI
 *   (вручную или автосохранением при выходе, если включено).
 *
 * Опциональные детали (details):
 * - Полный JSON параметров/ответа, доступный для раскрытия по тапу.
 * - Если details.length > limit — обрезается до limit символов.
 * - Лимит хранится в prefs и настраивается в LoggerSettingsFragment.
 *
 * Фильтр:
 * - Уровни: набор LoggerLevelModel, хранится под logger_filter_levels.
 * - Теги: набор LoggerTagModel, хранится под logger_filter_tags.
 * - Если ключа нет — считается дефолтным (все включены).
 * - Пустая строка = ничего не показывать.
 * - Запись проходит фильтр, если её уровень И тег включены.
 *
 * Потокобезопасность:
 * - запись логов может идти из Dispatchers.IO (например, ApiRouter);
 * - чтение/уведомление UI — из Main;
 * - список подписчиков — CopyOnWriteArrayList (безопасные итерации);
 * - буфер — ConcurrentLinkedDeque.
 *
 * Настройки (enabled, autoSave, detailsLimit, filterLevels, filterTags)
 * хранятся в SharedPreferences "terminal_prefs".
 */
object LoggerManager {

    /**
     * Максимальное количество записей в буфере.
     */
    private const val MAX_ENTRIES = 500

    /**
     * SharedPreferences для настроек логгера (общий с терминалом).
     */
    private const val PREFS_NAME = "terminal_prefs"

    private const val KEY_ENABLED = "logger_enabled"
    private const val KEY_AUTO_SAVE = "logger_auto_save"
    private const val KEY_DETAILS_LIMIT = "logger_details_limit"
    private const val KEY_FILTER_LEVELS = "logger_filter_levels"
    private const val KEY_FILTER_TAGS = "logger_filter_tags"

    /**
     * Лимит длины details по умолчанию.
     * Пользователь может поменять в LoggerSettingsFragment.
     */
    const val DEFAULT_DETAILS_LIMIT = 3000

    /**
     * Кольцевой буфер. Хранит записи в порядке добавления
     * (самая старая — в начале, самая новая — в конце).
     */
    private val buffer = ConcurrentLinkedDeque<LoggerEntryModel>()

    /**
     * Подписчики (фрагменты, адаптеры). CopyOnWriteArrayList —
     * чтобы обход подписчиков во время уведомления не падал,
     * если кто-то в этот момент отписывается.
     */
    private val listeners = CopyOnWriteArrayList<LoggerListener>()

    /**
     * Флаг «логирование включено». Инициализируется из prefs при init().
     */
    @Volatile
    private var enabled = true

    /**
     * Флаг «автосохранение в файл». Если включено — при выходе из
     * LoggerActivity буфер сохраняется в файл в LOGS.
     */
    @Volatile
    private var autoSave = false

    /**
     * Лимит длины details. Обрезает слишком длинные JSON.
     * Инициализируется из prefs при init().
     */
    @Volatile
    private var detailsLimit = DEFAULT_DETAILS_LIMIT

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    /**
     * Инициализирует настройки из SharedPreferences.
     * Вызывается при старте ядра (Core.init).
     */
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

    fun debug(tag: LoggerTagModel, message: String, details: String? = null) {
        log(LoggerLevelModel.DEBUG, tag, message, details)
    }

    fun info(tag: LoggerTagModel, message: String, details: String? = null) {
        log(LoggerLevelModel.INFO, tag, message, details)
    }

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

    /**
     * Внутренний метод записи.
     */
    private fun log(
        level: LoggerLevelModel,
        tag: LoggerTagModel,
        message: String,
        details: String?
    ) {
        if (!enabled) return

        // Обрезаем details, если слишком длинные
        val trimmedDetails = trimDetails(details)

        val entry = LoggerEntryModel(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            details = trimmedDetails
        )

        buffer.addLast(entry)

        // Ограничиваем размер буфера
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst() ?: break
        }

        // Уведомляем подписчиков
        notifyListeners(entry)
    }

    /**
     * Обрезает details до detailsLimit символов.
     * Если длина ≤ лимита — возвращает как есть.
     * Если больше — обрезает и добавляет маркер.
     */
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

    fun getAll(): List<LoggerEntryModel> {
        return buffer.toList()
    }

    fun getAllReversed(): List<LoggerEntryModel> {
        return buffer.toList().asReversed()
    }

    fun size(): Int {
        return buffer.size
    }

    // ============================================================
    // ОЧИСТКА
    // ============================================================

    fun clear() {
        buffer.clear()
        notifyCleared()
    }

    // ============================================================
    // ВКЛЮЧЕНИЕ / ВЫКЛЮЧЕНИЕ
    // ============================================================

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_ENABLED, value) }
    }

    fun isAutoSaveEnabled(): Boolean = autoSave

    fun setAutoSaveEnabled(context: Context, value: Boolean) {
        autoSave = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_AUTO_SAVE, value) }
    }

    fun getDetailsLimit(): Int = detailsLimit

    fun setDetailsLimit(context: Context, value: Int) {
        detailsLimit = value
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_DETAILS_LIMIT, value) }
    }

    // ============================================================
    // ФИЛЬТР: УРОВНИ
    // ============================================================

    /**
     * Возвращает Set уровней, которые сейчас включены в фильтре.
     * Если в prefs ничего нет — возвращает все уровни (дефолт).
     */
    fun getFilterLevels(context: Context): Set<LoggerLevelModel> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FILTER_LEVELS, null)
            ?: return LoggerLevelModel.entries.toSet()

        if (raw.isEmpty()) {
            return emptySet()
        }

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

    /**
     * Сохраняет набор включённых уровней.
     */
    fun setFilterLevels(context: Context, levels: Set<LoggerLevelModel>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = levels.joinToString(",") { it.name }
        prefs.edit { putString(KEY_FILTER_LEVELS, raw) }
    }

    // ============================================================
    // ФИЛЬТР: ТЕГИ
    // ============================================================

    /**
     * Возвращает Set тегов, которые сейчас включены в фильтре.
     * Если в prefs ничего нет — возвращает все теги (дефолт).
     */
    fun getFilterTags(context: Context): Set<LoggerTagModel> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FILTER_TAGS, null)
            ?: return LoggerTagModel.entries.toSet()

        if (raw.isEmpty()) {
            return emptySet()
        }

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

    /**
     * Сохраняет набор включённых тегов.
     */
    fun setFilterTags(context: Context, tags: Set<LoggerTagModel>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = tags.joinToString(",") { it.name }
        prefs.edit { putString(KEY_FILTER_TAGS, raw) }
    }

    // ============================================================
    // ФИЛЬТР: ОБЩЕЕ
    // ============================================================

    /**
     * Возвращает true, если оба фильтра (уровни и теги) в дефолтном
     * состоянии (включены все значения).
     */
    fun isFilterDefault(context: Context): Boolean {
        val levelsDefault = getFilterLevels(context) == LoggerLevelModel.entries.toSet()
        val tagsDefault = getFilterTags(context) == LoggerTagModel.entries.toSet()
        return levelsDefault && tagsDefault
    }

    /**
     * Возвращает записи в порядке «новые → старые», отфильтрованные
     * по текущему набору уровней И тегов.
     */
    fun getFilteredEntries(context: Context): List<LoggerEntryModel> {
        val enabledLevels = getFilterLevels(context)
        val enabledTags = getFilterTags(context)
        val levelsDefault = enabledLevels == LoggerLevelModel.entries.toSet()
        val tagsDefault = enabledTags == LoggerTagModel.entries.toSet()

        // Быстрый путь — оба фильтра дефолтные
        if (levelsDefault && tagsDefault) {
            return buffer.toList().asReversed()
        }

        return buffer.toList()
            .asReversed()
            .filter { it.level in enabledLevels && it.tag in enabledTags }
    }

    // ============================================================
    // ФАЙЛОВОЕ ХРАНИЛИЩЕ
    // ============================================================

    /**
     * Сохраняет текущий буфер в файл в StorageDirectory.LOGS.
     *
     * Suspend-версия для вызова из UI (кнопка «Сохранить текущий лог»).
     *
     * ВАЖНО: сохраняет ВЕСЬ буфер, независимо от активного фильтра —
     * файл лога должен содержать полную историю.
     */
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

    /**
     * Синхронная версия для вызова из onDestroy (автосохранение при выходе).
     */
    fun writeToFileBlocking(context: Context): String? {
        return try {
            val entries = buffer.toList()
            if (entries.isEmpty()) return null

            val actualName = generateFileName()
            val content = formatEntries(entries)

            val storageManager = StorageManager(context.applicationContext)
            val file = File(storageManager.getDirectory(StorageDirectory.LOGS), actualName)
            file.parentFile?.mkdirs()
            file.writeText(content)

            actualName
        } catch (_: Exception) {
            null
        }
    }

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

    /**
     * Удаляет все файлы логов из StorageDirectory.LOGS.
     *
     * @return true при успехе (файлы удалены или директория уже пуста).
     */
    suspend fun deleteAllLogFiles(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val storageManager = StorageManager(context.applicationContext)
            storageManager.removeAll(StorageDirectory.LOGS)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Формирует содержимое файла из списка записей.
     * Формат: `[HH:mm:ss.SSS] LEVEL [TAG] message`
     *
     * Details (если есть) пишутся с отступом под сообщением.
     */
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

                // Если есть details — добавляем с отступом
                entry.details?.let { details ->
                    details.lines().forEach { line ->
                        append("    ")
                        appendLine(line)
                    }
                }
            }
        }
    }

    private fun generateFileName(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "log_${formatter.format(Date())}.log"
    }

    // ============================================================
    // ПОДПИСКА
    // ============================================================

    fun subscribe(listener: LoggerListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unsubscribe(listener: LoggerListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(entry: LoggerEntryModel) {
        listeners.forEach { it.onEntryAdded(entry) }
    }

    private fun notifyCleared() {
        listeners.forEach { it.onCleared() }
    }

    // ============================================================
    // ИНТЕРФЕЙС ПОДПИСЧИКА
    // ============================================================

    interface LoggerListener {
        fun onEntryAdded(entry: LoggerEntryModel)
        fun onCleared()
    }
}