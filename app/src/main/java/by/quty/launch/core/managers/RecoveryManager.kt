// *** core/managers/RecoveryManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import androidx.core.content.edit
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.utilities.AppInfoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Менеджер восстановления и сброса настроек.
 *
 * Отвечает за:
 * - Генерацию JSON-содержимого `.qutyconfig` (сохранение — во фрагменте через SAF).
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
 *
 * ВАЖНО: менеджер НЕ хранит локализованных строк — все сообщения
 * об ошибках представлены через [ImportReadResult.Failure] как
 * sealed-иерархия, а фрагмент сам локализует их через `getString(...)`.
 */
class RecoveryManager(private val context: Context) {

    // ============================================================
    // ЭКСПОРТ
    // ============================================================

    /**
     * Формирует JSON-содержимое файла `.qutyconfig`.
     *
     * ВАЖНО: НЕ сохраняет файл. Сохранение — задача фрагмента
     * через SAF (`ActivityResultContracts.CreateDocument`).
     *
     * @return JSON-строка или null при ошибке.
     */
    suspend fun buildExportJson(): String? = withContext(Dispatchers.IO) {
        try {
            // Собираем данные из всех prefs
            val dataObject = JSONObject()

            CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                dataObject.put(prefsName, prefsToJson(prefs))
            }

            // Формируем корневой объект
            val (appVersionName, _) = AppInfoHelper.getSplitVersion(context)
            val fullVersion = AppInfoHelper.getVersionName(context)
            val versionCode = AppInfoHelper.getVersionCode(context)

            val root = JSONObject()
            root.put("format", CoreConfig.RECOVERY_FORMAT_NAME)
            root.put("formatVersion", CoreConfig.RECOVERY_FORMAT_VERSION)
            root.put("createdAt", System.currentTimeMillis())
            root.put("appVersion", fullVersion.ifEmpty { appVersionName })
            root.put("appVersionCode", versionCode)
            root.put("data", dataObject)

            val result = root.toString(2)

            LoggerManager.info(
                LoggerTagModel.RECOVERY,
                "buildExportJson OK: ${result.length} chars"
            )

            result
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.RECOVERY, "buildExportJson failed", e)
            null
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
     *
     * Ошибки представлены sealed-иерархией, а не строками —
     * локализация выполняется во фрагменте.
     */
    sealed class ImportReadResult {
        /** Файл прочитан успешно. */
        data class Success(
            val formatVersion: Int,
            val appVersion: String,
            val data: Map<String, Map<String, Any>>
        ) : ImportReadResult()

        /** Ошибка чтения/парсинга. */
        sealed class Failure : ImportReadResult() {
            /** Файл не найден по указанному пути. */
            object FileNotFound : Failure()

            /** Файл пуст (0 байт или только пробелы). */
            object FileEmpty : Failure()

            /** Файл — не .qutyconfig. */
            data class InvalidFormat(val expectedFormat: String) : Failure()

            /** В файле отсутствует секция data. */
            object NoData : Failure()

            /** Ошибка парсинга JSON или другая ошибка чтения. */
            data class ParseError(val message: String?) : Failure()
        }
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
                return@withContext ImportReadResult.Failure.FileNotFound
            }

            val content = file.readText()
            if (content.isBlank()) {
                return@withContext ImportReadResult.Failure.FileEmpty
            }

            val root = JSONObject(content)

            // Проверяем формат
            val format = root.optString("format", "")
            if (format != CoreConfig.RECOVERY_FORMAT_NAME) {
                return@withContext ImportReadResult.Failure.InvalidFormat(
                    CoreConfig.RECOVERY_FORMAT_NAME
                )
            }

            val formatVersion = root.optInt("formatVersion", 0)
            val appVersion = root.optString("appVersion", "")

            val dataObject = root.optJSONObject("data")
                ?: return@withContext ImportReadResult.Failure.NoData

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
            ImportReadResult.Failure.ParseError(e.message)
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
                CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
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

                LoggerManager.info(
                    LoggerTagModel.RECOVERY,
                    "applyImport OK: ${data.size} prefs"
                )

                true
            } catch (e: Exception) {
                LoggerManager.error(LoggerTagModel.RECOVERY, "applyImport failed", e)
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
     * - Очищает все SharedPreferences из [CoreConfig.RECOVERY_EXPORTED_PREFS].
     * - Восстанавливает только флаг онбординга (чтобы не выкинуло в Welcome).
     * - Удаляет пользовательские файлы из StorageManager (shells, updates, cache, temp).
     * - Очищает cacheDir приложения.
     * - Очищает WebView кэш.
     *
     * ВАЖНО: папки `exports` и `backups` больше не существуют —
     * backup'ы сохраняются во внешнее хранилище через SAF.
     *
     * @return true при успехе.
     */
    suspend fun fullReset(): Boolean = withContext(Dispatchers.IO) {
        try {
            LoggerManager.warn(
                LoggerTagModel.RECOVERY,
                context.getString(R.string.log_recovery_reset_started)
            )

            // 1. Очищаем SharedPreferences
            CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit(commit = true) { clear() }
            }

            // 2. Восстанавливаем флаг онбординга (чтобы не выкинуло в Welcome)
            val launcherPrefs = context.getSharedPreferences(
                CoreConfig.RECOVERY_LAUNCHER_PREFS,
                Context.MODE_PRIVATE
            )
            launcherPrefs.edit(commit = true) {
                putBoolean(CoreConfig.RECOVERY_KEY_ONBOARDING_COMPLETED, true)
            }

            // 3. Очищаем пользовательские папки StorageManager
            val storageManager = StorageManager(context)
            storageManager.removeAll(StorageDirectory.SHELLS)
            storageManager.removeAll(StorageDirectory.UPDATES)
            storageManager.removeAll(StorageDirectory.CACHE)
            storageManager.removeAll(StorageDirectory.TEMP)

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

            LoggerManager.info(
                LoggerTagModel.RECOVERY,
                context.getString(R.string.log_recovery_reset_done)
            )

            true
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.RECOVERY, "fullReset failed", e)
            false
        }
    }
}