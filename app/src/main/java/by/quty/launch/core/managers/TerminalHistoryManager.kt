// *** core/managers/TerminalHistoryManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import androidx.core.content.edit

/**
 * Менеджер истории команд терминала.
 *
 * Хранит последние [MAX_HISTORY] введённых команд в SharedPreferences
 * "terminal_prefs". Каждая команда — отдельная запись в файле:
 *   key = "history_N", где N = 0..MAX_HISTORY-1 (0 — самая старая)
 *
 * Дубликаты подряд не сохраняются (как в bash): если пользователь
 * ввёл ту же команду второй раз подряд — она не добавляется в историю,
 * но в лог терминала выводится.
 */
object TerminalHistoryManager {

    /** Имя SharedPreferences для терминала. */
    private const val PREFS_NAME = "terminal_prefs"

    /** Префикс ключа истории. */
    private const val KEY_PREFIX = "history_"

    /** Максимальное количество хранимых команд. */
    private const val MAX_HISTORY = 50

    /**
     * Добавляет команду в историю.
     *
     * Логика:
     * - если команда совпадает с последней сохранённой — не дублируем;
     * - иначе сдвигаем всё на 1 позицию вниз и пишем на позицию 0;
     * - если история достигла MAX_HISTORY — самый старый элемент вытесняется.
     */
    fun add(context: Context, command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Проверяем, не совпадает ли с последней командой
            val lastCommand = prefs.getString(KEY_PREFIX + "0", null)
            if (lastCommand == trimmed) return

            // Сдвигаем всё вниз: 48 > 49, 47 > 48, ..., 0 > 1
            prefs.edit {
                for (i in MAX_HISTORY - 2 downTo 0) {
                    val current = prefs.getString(KEY_PREFIX + i, null)
                    val nextKey = KEY_PREFIX + (i + 1)
                    if (current != null) {
                        putString(nextKey, current)
                    } else {
                        remove(nextKey)
                    }
                }

                // Новую команду — на позицию 0
                putString(KEY_PREFIX + "0", trimmed)
            }
        } catch (_: Exception) {
            // Игнорируем ошибки — история не критична для работы
        }
    }

    /**
     * Возвращает всю историю — от самой старой к самой новой.
     *
     * Порядок: [самая старая, ..., самая новая].
     * Удобно выводить в терминал через `history`.
     */
    fun getAll(context: Context): List<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val result = mutableListOf<String>()

            // Идём от самого старого (MAX_HISTORY-1) к самому новому (0)
            // и пропускаем null-дырки.
            for (i in MAX_HISTORY - 1 downTo 0) {
                val entry = prefs.getString(KEY_PREFIX + i, null)
                if (!entry.isNullOrEmpty()) {
                    result.add(entry)
                }
            }

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Возвращает историю в порядке «самая новая — первой».
     *
     * Используется для навигации стрелкой ^ в EditText:
     * первое нажатие даёт самую свежую команду.
     */
    fun getReversed(context: Context): List<String> {
        return getAll(context).asReversed()
    }

    /**
     * Очищает историю.
     */
    fun clear(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit {
                for (i in 0 until MAX_HISTORY) {
                    remove(KEY_PREFIX + i)
                }
            }
        } catch (_: Exception) {
            // Игнорируем ошибки
        }
    }

    /**
     * Возвращает текущее количество записей в истории.
     */
    fun size(context: Context): Int {
        return getAll(context).size
    }
}