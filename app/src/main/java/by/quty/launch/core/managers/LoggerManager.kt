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
 * Потокобезопасность:
 * - запись логов может идти из Dispatchers.IO (например, ApiRouter);
 * - чтение/уведомление UI — из Main;
 * - список подписчиков — CopyOnWriteArrayList (безопасные итерации);
 * - буфер — ConcurrentLinkedDeque.
 *
 * Настройки (enabled, autoSave) хранятся в SharedPreferences "terminal_prefs".
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
     * Флаг «логирование включено». Инициализируется из prefs при первом
     * обращении. Значение @Volatile — читается из разных потоков.
     */
    @Volatile
    private var enabled = true

    /**
     * Флаг «автосохранение в файл». Если включено — при выходе из
     * LoggerActivity буфер сохраняется в файл в LOGS.
     */
    @Volatile
    private var autoSave = false

    // ============================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================

    /**
     * Инициализирует настройки из SharedPreferences.
     * Вызывается при старте ядра (Core.init).
     */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        autoSave = prefs.getBoolean(KEY_AUTO_SAVE, false)
    }

    // ============================================================
    // ЗАПИСЬ
    // ============================================================

    fun debug(tag: LoggerTagModel, message: String) {
        log(LoggerLevelModel.DEBUG, tag, message)
    }

    fun info(tag: LoggerTagModel, message: String) {
        log(LoggerLevelModel.INFO, tag, message)
    }

    fun warn(tag: LoggerTagModel, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message ?: throwable.javaClass.simpleName}"
        } else {
            message
        }
        log(LoggerLevelModel.WARN, tag, fullMessage)
    }

    fun error(tag: LoggerTagModel, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message: ${throwable.message ?: throwable.javaClass.simpleName}"
        } else {
            message
        }
        log(LoggerLevelModel.ERROR, tag, fullMessage)
    }

    /**
     * Внутренний метод записи.
     */
    private fun log(level: LoggerLevelModel, tag: LoggerTagModel, message: String) {
        if (!enabled) return

        val entry = LoggerEntryModel(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        buffer.addLast(entry)

        // Ограничиваем размер буфера
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst() ?: break
        }

        // Уведомляем подписчиков
        notifyListeners(entry)
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

    // ============================================================
    // ФАЙЛОВОЕ ХРАНИЛИЩЕ
    // ============================================================

    /**
     * Сохраняет текущий буфер в файл в StorageDirectory.LOGS.
     *
     * Suspend-версия для вызова из UI (кнопка «Сохранить текущий лог»).
     *
     * @param context контекст
     * @param fileName имя файла без пути; если null — генерируется автоматически.
     * @return имя сохранённого файла или null при ошибке.
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
     * Внутри использует runBlocking через GlobalScope, потому что onDestroy
     * уже не может ждать suspend-функцию.
     *
     * @return имя сохранённого файла или null.
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

    /**
     * Возвращает список файлов логов, отсортированный по имени (новые — сверху).
     */
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

    /**
     * Удаляет файл лога по имени.
     */
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
     * Формирует содержимое файла из списка записей.
     * Формат: `[HH:mm:ss.SSS] LEVEL [TAG] message`
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
            }
        }
    }

    /**
     * Генерирует имя файла по текущей дате и времени:
     * `log_2026-10-11_14-30-00.log`
     */
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