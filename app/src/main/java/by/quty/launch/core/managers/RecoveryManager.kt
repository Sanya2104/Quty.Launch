// *** core/managers/RecoveryManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import androidx.core.content.edit
import by.quty.launch.core.utilities.AppInfoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Менеджер восстановления и сброса настроек.
 *
 * Отвечает за:
 * - Экспорт всех SharedPreferences в файл `.qutyconfig` (JSON).
 * - Импорт настроек из файла `.qutyconfig`.
 * - Полный сброс приложения в состояние «как после установки».
 *
 * Формат `.qutyconfig` (JSON):
 * ```json
 * {
 *   "format": "qutyconfig",
 *   "formatVersion": 1,
 *   "createdAt": 1234567890,
 *   "appVersion": "0.0.160-Alpha",
 *   "appVersionCode": 680,
 *   "data": {
 *     "launcher_prefs": { "key": "value" },
 *     "developer_prefs": {  },
 *     "update_prefs": {  }
 *   }
 * }
 * ```
 */
class RecoveryManager(private val context: Context) {

    companion object {
        /** Идентификатор формата файла */
        const val FORMAT_NAME = "qutyconfig"

        /** Версия формата файла */
        const val FORMAT_VERSION = 1

        /** Расширение файла (с точкой) */
        const val FILE_EXTENSION = ".qutyconfig"

        /**
         * Список SharedPreferences, которые попадают в экспорт/импорт.
         *
         * ВАЖНО: `logger_prefs` исключён — логгер удалён из проекта.
         */
        val EXPORTED_PREFS = listOf(
            "launcher_prefs",
            "developer_prefs",
            "update_prefs"
        )

        /** Имя prefs с флагом онбординга (не сбрасывается при fullReset) */
        private const val LAUNCHER_PREFS = "launcher_prefs"

        /** Ключ флага онбординга */
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }

    private val storageManager = StorageManager(context)

    // ============================================================
    // ЭКСПОРТ
    // ============================================================

    /**
     * Результат экспорта
     */
    data class ExportResult(
        val success: Boolean,
        val filePath: String? = null,
        val error: String? = null
    )

    /**
     * Экспортирует все SharedPreferences в файл `.qutyconfig`.
     *
     * Файл сохраняется в `StorageDirectory.EXPORTS` с именем
     * `qutyconfig_YYYY-MM-DD_HH-MM-SS.qutyconfig`.
     *
     * @return ExportResult с путём к файлу или ошибкой.
     */
    suspend fun export(): ExportResult = withContext(Dispatchers.IO) {
        try {
            // Формируем имя файла
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            val fileName = "qutyconfig_${dateFormat.format(Date())}$FILE_EXTENSION"

            // Собираем данные из всех prefs как Map<String, JSONObject>
            val dataObject = JSONObject()

            EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                dataObject.put(prefsName, prefsToJson(prefs))
            }

            // Формируем корневой объект
            val (appVersionName, _) = AppInfoHelper.getSplitVersion(context)
            val fullVersion = AppInfoHelper.getVersionName(context)
            val versionCode = AppInfoHelper.getVersionCode(context)

            val root = JSONObject()
            root.put("format", FORMAT_NAME)
            root.put("formatVersion", FORMAT_VERSION)
            root.put("createdAt", System.currentTimeMillis())
            root.put("appVersion", fullVersion.ifEmpty { appVersionName })
            root.put("appVersionCode", versionCode)
            root.put("data", dataObject)

            // Сохраняем файл через StorageManager
            val success = storageManager.set(
                directory = StorageDirectory.EXPORTS,
                name = fileName,
                content = root.toString(2),
                overwrite = true
            )

            if (!success) {
                return@withContext ExportResult(
                    success = false,
                    error = "Не удалось сохранить файл"
                )
            }

            val file = storageManager.get(StorageDirectory.EXPORTS, fileName)
            ExportResult(
                success = true,
                filePath = file.absolutePath
            )

        } catch (e: Exception) {
            ExportResult(
                success = false,
                error = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    /**
     * Конвертирует SharedPreferences в JSONObject.
     *
     * Все значения приводятся к примитивам JSON:
     * Boolean → boolean, Int/Long → number, Float → number,
     * String → string.
     */
    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()

        prefs.all.forEach { (key, value) ->
            when (value) {
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is String -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }

        return json
    }

    // ============================================================
    // ИМПОРТ
    // ============================================================

    /**
     * Результат чтения файла `.qutyconfig` (до применения).
     */
    sealed class ImportReadResult {
        /** Файл прочитан успешно. */
        data class Success(
            val formatVersion: Int,
            val appVersion: String,
            val data: Map<String, Map<String, Any>>
        ) : ImportReadResult()

        /** Ошибка чтения/парсинга. */
        data class Failure(val error: String) : ImportReadResult()
    }

    /**
     * Читает и парсит файл `.qutyconfig` по указанному пути.
     *
     * НЕ применяет настройки — только валидирует и парсит.
     * Применение — через [applyImport].
     *
     * @param filePath абсолютный путь к файлу.
     */
    suspend fun readConfig(filePath: String): ImportReadResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext ImportReadResult.Failure("Файл не найден")
            }

            val content = file.readText()
            if (content.isBlank()) {
                return@withContext ImportReadResult.Failure("Файл пуст")
            }

            val root = JSONObject(content)

            // Проверяем формат
            val format = root.optString("format", "")
            if (format != FORMAT_NAME) {
                return@withContext ImportReadResult.Failure(
                    "Неверный формат файла: ожидается $FORMAT_NAME"
                )
            }

            val formatVersion = root.optInt("formatVersion", 0)
            val appVersion = root.optString("appVersion", "")

            val dataObject = root.optJSONObject("data")
                ?: return@withContext ImportReadResult.Failure("Отсутствует секция data")

            // Парсим prefs
            val data = mutableMapOf<String, Map<String, Any>>()
            dataObject.keys().forEach { prefsName ->
                val prefsObject = dataObject.optJSONObject(prefsName)
                    ?: return@forEach

                val prefsMap = mutableMapOf<String, Any>()
                prefsObject.keys().forEach { key ->
                    val value = prefsObject.get(key)
                    prefsMap[key] = value
                }
                data[prefsName] = prefsMap
            }

            ImportReadResult.Success(
                formatVersion = formatVersion,
                appVersion = appVersion,
                data = data
            )

        } catch (e: Exception) {
            ImportReadResult.Failure(e.message ?: "Ошибка парсинга файла")
        }
    }

    /**
     * Применяет прочитанные настройки к SharedPreferences.
     *
     * Перед записью каждой prefs — очищает её (`clear()`), чтобы
     * гарантированно не осталось старых ключей.
     *
     * ВАЖНО: используется `commit()` (синхронный) — иначе сразу после
     * импорта приложение перезапустится, а prefs ещё не записаны.
     *
     * @param data карта prefs → key → value.
     * @return true при успехе.
     */
    suspend fun applyImport(data: Map<String, Map<String, Any>>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                EXPORTED_PREFS.forEach { prefsName ->
                    val prefsData = data[prefsName] ?: return@forEach
                    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

                    // Синхронный commit, потому что после импорта сразу перезапуск
                    prefs.edit(commit = true) {
                        clear()
                        prefsData.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> putBoolean(key, value)
                                is Int -> putInt(key, value)
                                is Long -> putLong(key, value)
                                is Double -> putFloat(key, value.toFloat())
                                is String -> putString(key, value)
                                else -> putString(key, value.toString())
                            }
                        }
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    // ============================================================
    // ПОЛНЫЙ СБРОС
    // ============================================================

    /**
     * Полностью сбрасывает приложение в состояние «как после установки».
     *
     * Что делает:
     * - Очищает все SharedPreferences из [EXPORTED_PREFS].
     * - Восстанавливает только флаг онбординга (чтобы не выкинуло в Welcome).
     * - Удаляет все пользовательские файлы из StorageManager.
     * - Очищает cacheDir приложения.
     * - Очищает WebView кэш.
     *
     * @return true при успехе.
     */
    suspend fun fullReset(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Очищаем SharedPreferences
            EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit(commit = true) { clear() }
            }

            // 2. Восстанавливаем флаг онбординга (чтобы не выкинуло в Welcome)
            val launcherPrefs = context.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
            launcherPrefs.edit(commit = true) {
                putBoolean(KEY_ONBOARDING_COMPLETED, true)
            }

            // 3. Очищаем пользовательские папки StorageManager
            storageManager.removeAll(StorageDirectory.SHELLS)
            storageManager.removeAll(StorageDirectory.UPDATES)
            storageManager.removeAll(StorageDirectory.CACHE)
            storageManager.removeAll(StorageDirectory.TEMP)
            storageManager.removeAll(StorageDirectory.EXPORTS)
            storageManager.removeAll(StorageDirectory.BACKUPS)

            // 4. Очищаем cacheDir приложения
            try {
                context.cacheDir.deleteRecursively()
                context.cacheDir.mkdirs()
            } catch (_: Exception) {
                // Игнорируем
            }

            // 5. Очищаем кэш WebView (в главном потоке, потому что WebView требует)
            withContext(Dispatchers.Main) {
                try {
                    val webView = WebView(context)
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.clearFormData()
                    webView.clearSslPreferences()
                    webView.destroy()
                } catch (_: Exception) {
                    // Игнорируем
                }
            }

            true
        } catch (_: Exception) {
            false
        }
    }
}