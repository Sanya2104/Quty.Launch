// *** core/managers/RecoveryManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import androidx.core.content.edit
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.utilities.AppInfoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Менеджер восстановления и сброса настроек.
 * Формат .qutyconfig: JSON с format/formatVersion/createdAt/data(prefs).
 */
class RecoveryManager(private val context: Context) {

    // ============================================================
    // ЭКСПОРТ
    // ============================================================

    /** Формирует JSON-содержимое .qutyconfig (не сохраняет файл). */
    suspend fun buildExportJson(): String? = withContext(Dispatchers.IO) {
        try {
            val dataObject = JSONObject()

            CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                dataObject.put(prefsName, prefsToJson(prefs))
            }

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
                "RECOVERY.export  ok  bytes=${result.toByteArray(Charsets.UTF_8).size}"
            )

            result
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.RECOVERY, "RECOVERY.export  error", e)
            null
        }
    }

    /** Конвертирует SharedPreferences в JSONObject. */
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

    /** Результат чтения .qutyconfig. */
    sealed class ImportReadResult {
        data class Success(
            val formatVersion: Int,
            val appVersion: String,
            val data: Map<String, Map<String, Any>>
        ) : ImportReadResult()

        sealed class Failure : ImportReadResult() {
            object FileNotFound : Failure()
            object FileEmpty : Failure()
            data class InvalidFormat(val expectedFormat: String) : Failure()
            object NoData : Failure()
            data class ParseError(val message: String?) : Failure()
        }
    }

    /** Читает и валидирует .qutyconfig (без применения). */
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

            val data = mutableMapOf<String, Map<String, Any>>()
            dataObject.keys().forEach { prefsName ->
                val prefsObject = dataObject.optJSONObject(prefsName)
                    ?: return@forEach

                val prefsMap = mutableMapOf<String, Any>()
                prefsObject.keys().forEach { key ->
                    prefsMap[key] = prefsObject.get(key)
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

    /** Применяет prefs из импорта. Использует commit, т.к. сразу после — перезапуск. */
    suspend fun applyImport(data: Map<String, Map<String, Any>>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
                    val prefsData = data[prefsName] ?: return@forEach
                    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

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
                    "RECOVERY.import  ok  items=${data.size}"
                )

                true
            } catch (e: Exception) {
                LoggerManager.error(LoggerTagModel.RECOVERY, "RECOVERY.import  error", e)
                false
            }
        }

    // ============================================================
    // ПОЛНЫЙ СБРОС
    // ============================================================

    /**
     * Полный сброс: prefs, папки StorageManager, cacheDir, WebView cache.
     * Флаг онбординга восстанавливается — чтобы не выкинуло в Welcome.
     */
    suspend fun fullReset(): Boolean = withContext(Dispatchers.IO) {
        try {
            LoggerManager.warn(LoggerTagModel.RECOVERY, "RECOVERY.reset  start")

            CoreConfig.RECOVERY_EXPORTED_PREFS.forEach { prefsName ->
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit(commit = true) { clear() }
            }

            val launcherPrefs = context.getSharedPreferences(
                CoreConfig.RECOVERY_LAUNCHER_PREFS,
                Context.MODE_PRIVATE
            )
            launcherPrefs.edit(commit = true) {
                putBoolean(CoreConfig.RECOVERY_KEY_ONBOARDING_COMPLETED, true)
            }

            val storageManager = StorageManager(context)
            storageManager.removeAll(StorageDirectory.SHELLS)
            storageManager.removeAll(StorageDirectory.UPDATES)
            storageManager.removeAll(StorageDirectory.CACHE)
            storageManager.removeAll(StorageDirectory.TEMP)

            try {
                context.cacheDir.deleteRecursively()
                context.cacheDir.mkdirs()
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                try {
                    val webView = WebView(context)
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.clearFormData()
                    webView.clearSslPreferences()
                    webView.destroy()
                } catch (_: Exception) {
                }
            }

            LoggerManager.info(LoggerTagModel.RECOVERY, "RECOVERY.reset  ok")

            true
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.RECOVERY, "RECOVERY.reset  error", e)
            false
        }
    }
}