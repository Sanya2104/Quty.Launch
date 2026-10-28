// *** core/managers/SystemUpdateManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.utilities.UpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import androidx.core.net.toUri

/** Информация о версии приложения. */
@Serializable
data class VersionInfo(
    val version: String,
    val versionCode: Int,
    val downloadUrl: String,
    val changelog: String,
    val releaseDate: String,
    val isCritical: Boolean,
    val size: String
)

/** Результат проверки обновлений. */
@Serializable
data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val versionInfo: VersionInfo? = null,
    val error: String? = null
)

/**
 * Обновление ядра: проверка, скачивание APK, установка.
 * Кэш последнего результата общий на процесс (companion object).
 */
class SystemUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storageManager = StorageManager(context)

    private val versionUrl = CoreConfig.UPDATE_SERVER_URL

    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    init {
        cleanupOldDownloadMarks()
    }

    // ============================================================
    // КЭШ ПОСЛЕДНЕГО РЕЗУЛЬТАТА ПРОВЕРКИ
    // ============================================================

    /** Возвращает кэш последнего результата (или null). */
    fun getCachedUpdate(): UpdateCheckResult? = cachedUpdate

    /** Сохраняет результат проверки в кэш. */
    fun setCachedUpdate(result: UpdateCheckResult) {
        cachedUpdate = result
    }

    /** Сбрасывает кэш результата. */
    fun clearCachedUpdate() {
        cachedUpdate = null
    }

    // ============================================================
    // НАСТРОЙКИ АВТО-ПРОВЕРКИ
    // ============================================================

    /** true, если авто-проверка включена. */
    fun isAutoCheckEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, CoreConfig.AUTO_CHECK_DEFAULT_ENABLED)
    }

    /** Включает/выключает авто-проверку с логированием. */
    fun setAutoCheckEnabled(enabled: Boolean) {
        val previous = isAutoCheckEnabled()
        if (previous == enabled) return

        prefs.edit { putBoolean(KEY_AUTO_CHECK_ENABLED, enabled) }
        LoggerManager.info(
            LoggerTagModel.UPDATE,
            "UPDATE.auto_check.enabled  $previous > $enabled"
        )
    }

    /** Возвращает интервал авто-проверки в часах. */
    fun getAutoCheckIntervalHours(): Int {
        return prefs.getInt(KEY_AUTO_CHECK_INTERVAL_HOURS, CoreConfig.AUTO_CHECK_DEFAULT_INTERVAL_HOURS)
    }

    /** Сохраняет интервал авто-проверки с нормализацией. */
    fun setAutoCheckIntervalHours(hours: Int) {
        val normalized = if (hours in CoreConfig.AUTO_CHECK_AVAILABLE_INTERVALS) {
            hours
        } else {
            CoreConfig.AUTO_CHECK_DEFAULT_INTERVAL_HOURS
        }

        val previous = getAutoCheckIntervalHours()
        if (previous == normalized) return

        prefs.edit { putInt(KEY_AUTO_CHECK_INTERVAL_HOURS, normalized) }
        LoggerManager.info(
            LoggerTagModel.UPDATE,
            "UPDATE.auto_check.interval  $previous > $normalized"
        )
    }

    /** Возвращает время последней проверки (ms). */
    fun getLastCheckTime(): Long {
        return prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    /** Сохраняет время последней проверки. */
    fun setLastCheckTime(time: Long) {
        prefs.edit { putLong(KEY_LAST_CHECK_TIME, time) }
    }

    /** true, если авто-проверка включена и интервал истёк. */
    fun shouldRunAutoCheck(): Boolean {
        if (!isAutoCheckEnabled()) return false

        val intervalMs = getAutoCheckIntervalHours().toLong() * 60L * 60L * 1000L
        val elapsed = System.currentTimeMillis() - getLastCheckTime()

        return elapsed >= intervalMs
    }

    // ============================================================
    // ВЕРСИЯ ПРИЛОЖЕНИЯ
    // ============================================================

    /** Возвращает versionCode приложения. */
    private fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.longVersionCode
        } catch (_: Exception) {
            0L
        }
    }

    // ============================================================
    // ПРОВЕРКА ОБНОВЛЕНИЙ
    // ============================================================

    /** Проверяет сервер и сравнивает versionCode. */
    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            LoggerManager.info(LoggerTagModel.UPDATE, "UPDATE.check  start")

            val connection = URL(versionUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CoreConfig.READ_TIMEOUT_MS

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val versionInfo = json.decodeFromString<VersionInfo>(jsonString)

                val currentVersionCode = getCurrentVersionCode()
                val hasUpdate = versionInfo.versionCode > currentVersionCode

                if (!hasUpdate) {
                    cleanupOldDownloadMarks()
                    deleteOldApkFiles()
                    LoggerManager.info(
                        LoggerTagModel.UPDATE,
                        "UPDATE.check  up-to-date"
                    )
                } else {
                    LoggerManager.info(
                        LoggerTagModel.UPDATE,
                        "UPDATE.check  available  ${versionInfo.version} (code=${versionInfo.versionCode})"
                    )
                }

                UpdateCheckResult(
                    hasUpdate = hasUpdate,
                    versionInfo = if (hasUpdate) versionInfo else null
                )
            } else {
                LoggerManager.warn(
                    LoggerTagModel.UPDATE,
                    "UPDATE.check  error  HTTP ${connection.responseCode}"
                )
                UpdateCheckResult(
                    hasUpdate = false,
                    error = context.getString(R.string.server_error, connection.responseCode)
                )
            }
        } catch (_: UnknownHostException) {
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (_: ConnectException) {
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (_: SocketTimeoutException) {
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.UPDATE, "UPDATE.check  error", e)
            val errorMessage = when {
                e.message?.contains("Network") == true -> context.getString(R.string.no_internet_connection)
                e.message?.contains("Unable to resolve host") == true -> context.getString(R.string.no_internet_connection)
                e.message?.contains("hostname") == true -> context.getString(R.string.no_internet_connection)
                else -> e.message ?: context.getString(R.string.update_error)
            }
            UpdateCheckResult(hasUpdate = false, error = errorMessage)
        }
    }

    // ============================================================
    // ФАЙЛЫ ОБНОВЛЕНИЙ
    // ============================================================

    /** Возвращает имя APK-файла для версии. */
    private fun getApkFileName(version: String): String {
        return "${CoreConfig.APK_FILE_PREFIX}-$version.apk"
    }

    /** Возвращает (есть ли готовый APK, URI). */
    suspend fun checkIfApkExists(versionInfo: VersionInfo): Pair<Boolean, Uri?> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = getApkFileName(versionInfo.version)

                if (!isDownloadComplete(versionInfo.versionCode)) {
                    deleteApkFile(fileName)
                    return@withContext Pair(false, null)
                }

                val file = storageManager.get(StorageDirectory.UPDATES, fileName)
                if (storageManager.isValidFile(file)) {
                    val uri = storageManager.getUri(file)
                    return@withContext Pair(true, uri)
                } else {
                    clearDownloadMark(versionInfo.versionCode)
                    return@withContext Pair(false, null)
                }
            } catch (_: Exception) {
                Pair(false, null)
            }
        }

    /** Удаляет APK-файл из UPDATES. */
    private suspend fun deleteApkFile(fileName: String) {
        try {
            storageManager.remove(StorageDirectory.UPDATES, fileName)
        } catch (_: Exception) {
        }
    }

    /** Удаляет все старые APK-файлы из UPDATES. */
    private suspend fun deleteOldApkFiles() {
        try {
            val apkFiles = storageManager.list(
                directory = StorageDirectory.UPDATES,
                filter = { it.startsWith(CoreConfig.APK_FILE_PREFIX) && it.endsWith(".apk") }
            )
            apkFiles.forEach { file ->
                storageManager.remove(file)
            }
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // МЕТКИ ЗАГРУЗКИ
    // ============================================================

    /** Ставит метку «APK скачан» для versionCode. */
    private fun markDownloadComplete(versionCode: Int) {
        prefs.edit { putBoolean("${CoreConfig.DOWNLOAD_COMPLETE_KEY}$versionCode", true) }
    }

    /** true, если APK для versionCode помечен как скачанный. */
    private fun isDownloadComplete(versionCode: Int): Boolean {
        return prefs.getBoolean("${CoreConfig.DOWNLOAD_COMPLETE_KEY}$versionCode", false)
    }

    /** Снимает метку «APK скачан». */
    fun clearDownloadMark(versionCode: Int) {
        prefs.edit { remove("${CoreConfig.DOWNLOAD_COMPLETE_KEY}$versionCode") }
    }

    /** Удаляет метки для версий, которые уже не новее текущей. */
    private fun cleanupOldDownloadMarks() {
        try {
            val currentVersionCode = getCurrentVersionCode()
            val allKeys = prefs.all.keys

            allKeys.forEach { key ->
                if (key.startsWith(CoreConfig.DOWNLOAD_COMPLETE_KEY)) {
                    val versionCode = key.replace(CoreConfig.DOWNLOAD_COMPLETE_KEY, "").toIntOrNull()
                    if (versionCode != null && versionCode <= currentVersionCode) {
                        prefs.edit { remove(key) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ И УСТАНОВКА
    // ============================================================

    /** Скачивает APK. При forceDownload = false возвращает уже готовый файл. */
    suspend fun downloadApk(
        versionInfo: VersionInfo,
        listener: DownloadListener,
        forceDownload: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getApkFileName(versionInfo.version)

            LoggerManager.info(
                LoggerTagModel.UPDATE,
                "UPDATE.download  start  ${versionInfo.version}"
            )

            if (!forceDownload) {
                val (exists, existingUri) = checkIfApkExists(versionInfo)
                if (exists && existingUri != null) {
                    withContext(Dispatchers.Main) {
                        listener.onSuccess(existingUri)
                    }
                    return@withContext true
                }
            }

            val fileSize = versionInfo.size.toLongOrNull() ?: (20 * 1024 * 1024)
            if (!hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext false
            }

            val tempFile = storageManager.createTempFile(
                prefix = "apk_download_${versionInfo.versionCode}",
                extension = "tmp"
            )

            val file = UpdateHelper.downloadFile(
                context = context,
                url = versionInfo.downloadUrl,
                listener = object : UpdateHelper.DownloadListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }

                    override fun onSuccess(file: File) {
                    }

                    override fun onError(message: String) {
                        listener.onError(message)
                    }
                },
                destination = UpdateHelper.Destination.CustomPath(tempFile.absolutePath),
                connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS,
                readTimeout = CoreConfig.READ_TIMEOUT_MS
            )

            if (file == null || !file.exists()) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_empty_file))
                }
                return@withContext false
            }

            if (file.length() == 0L) {
                file.delete()
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_empty_file))
                }
                return@withContext false
            }

            val success = storageManager.set(
                directory = StorageDirectory.UPDATES,
                name = fileName,
                inputStream = file.inputStream(),
                overwrite = true
            )

            file.delete()

            if (!success) {
                LoggerManager.error(
                    LoggerTagModel.UPDATE,
                    "UPDATE.download  error  ${versionInfo.version}  save failed"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_error))
                }
                return@withContext false
            }

            markDownloadComplete(versionInfo.versionCode)

            val savedFile = storageManager.get(StorageDirectory.UPDATES, fileName)
            val uri = storageManager.getUri(savedFile)

            if (uri == null) {
                LoggerManager.error(
                    LoggerTagModel.UPDATE,
                    "UPDATE.download  error  ${versionInfo.version}  uri null"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_error))
                }
                return@withContext false
            }

            LoggerManager.info(
                LoggerTagModel.UPDATE,
                "UPDATE.download  ok  ${versionInfo.version}"
            )

            withContext(Dispatchers.Main) {
                listener.onSuccess(uri)
            }
            true

        } catch (e: Exception) {
            LoggerManager.error(
                LoggerTagModel.UPDATE,
                "UPDATE.download  error  ${versionInfo.version}  ${e.message ?: e.javaClass.simpleName}",
                e
            )
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            false
        }
    }

    /** true, если свободного места больше требуемого на 10%. */
    private fun hasEnoughFreeSpace(requiredSpace: Long): Boolean {
        return try {
            val freeSpace = storageManager.getDirectory(StorageDirectory.BASE).freeSpace
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            true
        }
    }

    /** Запускает установку APK. Метка загрузки не очищается — пригодится при отмене. */
    @Suppress("UNUSED_PARAMETER")
    fun installApk(uri: Uri, versionCode: Int? = null) {
        try {
            LoggerManager.info(LoggerTagModel.UPDATE, "UPDATE.install  start")

            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = "package:${context.packageName}".toUri()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)

        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.UPDATE, "UPDATE.install  error", e)
            Toast.makeText(
                context,
                context.getString(R.string.update_install_failed, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ============================================================
    // СЛУШАТЕЛЬ
    // ============================================================

    /** Слушатель прогресса скачивания APK. */
    interface DownloadListener {
        /** Прогресс скачивания (0–100). */
        fun onProgress(percent: Int)

        /** Скачивание завершено, возвращает URI файла. */
        fun onSuccess(uri: Uri)

        /** Ошибка скачивания. */
        fun onError(message: String)
    }

    // ============================================================
    // КЛЮЧИ И ОБЩИЙ КЭШ
    // ============================================================

    companion object {
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_AUTO_CHECK_INTERVAL_HOURS = "auto_check_interval_hours"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"

        /** Общий кэш последнего результата (на процесс). */
        @Volatile
        private var cachedUpdate: UpdateCheckResult? = null
    }
}