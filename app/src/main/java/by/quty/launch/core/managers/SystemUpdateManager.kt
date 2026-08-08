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
import by.quty.launch.core.logger.Logger
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

/**
 * Информация о версии приложения
 */
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

/**
 * Результат проверки обновлений
 */
@Serializable
data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val versionInfo: VersionInfo? = null,
    val error: String? = null
)

/**
 * Менеджер обновления приложения
 * Проверяет наличие обновлений, скачивает и устанавливает APK
 */
class SystemUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storageManager = StorageManager(context)

    // URL для проверки обновлений
    private val versionUrl = "https://raw.githubusercontent.com/Sanya2104/Quty.Launch.Server/main/updates/version.json"

    // SharedPreferences для меток завершённых загрузок
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val APK_PREFIX = "Quty.Launch"
        private const val DOWNLOAD_COMPLETE_KEY = "download_complete_"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }

    init {
        cleanupOldDownloadMarks()
    }

    // ============================================================
    // ПОЛУЧЕНИЕ ИНФОРМАЦИИ О ВЕРСИИ
    // ============================================================

    /**
     * Получение текущей версии приложения через PackageManager
     */
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
        } catch (e: Exception) {
            Logger.e("SystemUpdateManager", "❌ Ошибка получения версии: ${e.message}")
            0L
        }
    }

    // ============================================================
    // ПРОВЕРКА ОБНОВЛЕНИЙ
    // ============================================================

    /**
     * Проверка наличия обновлений
     * @return UpdateCheckResult с информацией об обновлении
     */
    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            Logger.d("SystemUpdateManager", "🔍 Проверка обновлений...")

            val connection = URL(versionUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val versionInfo = json.decodeFromString<VersionInfo>(jsonString)

                val currentVersionCode = getCurrentVersionCode()
                val hasUpdate = versionInfo.versionCode > currentVersionCode

                Logger.d("SystemUpdateManager",
                    "📊 Текущая версия: $currentVersionCode, Доступна: ${versionInfo.versionCode}, Обновление: $hasUpdate"
                )

                if (!hasUpdate) {
                    cleanupOldDownloadMarks()
                    deleteOldApkFiles()
                }

                UpdateCheckResult(
                    hasUpdate = hasUpdate,
                    versionInfo = if (hasUpdate) versionInfo else null
                )
            } else {
                Logger.w("SystemUpdateManager", "⚠️ Сервер вернул код: ${connection.responseCode}")
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
            val errorMessage = when {
                e.message?.contains("Network") == true -> context.getString(R.string.no_internet_connection)
                e.message?.contains("Unable to resolve host") == true -> context.getString(R.string.no_internet_connection)
                e.message?.contains("hostname") == true -> context.getString(R.string.no_internet_connection)
                else -> e.message ?: context.getString(R.string.update_error)
            }
            Logger.e("SystemUpdateManager", "❌ Ошибка проверки: ${e.message}")
            UpdateCheckResult(hasUpdate = false, error = errorMessage)
        }
    }

    // ============================================================
    // УПРАВЛЕНИЕ ФАЙЛАМИ ОБНОВЛЕНИЙ
    // ============================================================

    /**
     * Получить имя APK файла для указанной версии
     */
    private fun getApkFileName(version: String): String {
        return "$APK_PREFIX-$version.apk"
    }

    /**
     * Проверяет, существует ли уже скачанный APK файл для указанной версии
     * @param versionInfo информация о версии
     * @return Pair(существует ли, URI файла)
     */
    suspend fun checkIfApkExists(versionInfo: VersionInfo): Pair<Boolean, Uri?> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = getApkFileName(versionInfo.version)

                // Проверяем метку о завершении загрузки
                if (!isDownloadComplete(versionInfo.versionCode)) {
                    // Если метки нет, удаляем возможный файл-призрак
                    deleteApkFile(fileName)
                    return@withContext Pair(false, null)
                }

                // Проверяем существование файла через StorageManager
                val file = storageManager.get(StorageDirectory.UPDATES, fileName)
                if (storageManager.isValidFile(file)) {
                    val uri = storageManager.getUri(file)
                    return@withContext Pair(true, uri)
                } else {
                    // Файл повреждён или отсутствует — очищаем метку
                    clearDownloadMark(versionInfo.versionCode)
                    return@withContext Pair(false, null)
                }
            } catch (_: Exception) {
                Pair(false, null)
            }
        }

    /**
     * Удаляет APK файл по имени
     */
    private suspend fun deleteApkFile(fileName: String) {
        try {
            storageManager.remove(StorageDirectory.UPDATES, fileName)
        } catch (_: Exception) {
            // Игнорируем ошибки
        }
    }

    /**
     * Удаляет все старые APK файлы
     */
    private suspend fun deleteOldApkFiles() {
        try {
            val apkFiles = storageManager.list(
                directory = StorageDirectory.UPDATES,
                filter = { it.startsWith(APK_PREFIX) && it.endsWith(".apk") }
            )
            var deletedCount = 0
            apkFiles.forEach { file ->
                storageManager.remove(file)
                deletedCount++
            }
            if (deletedCount > 0) {
                Logger.d("SystemUpdateManager", "🗑️ Удалено $deletedCount старых APK файлов")
            }
        } catch (_: Exception) {
            // Игнорируем ошибки
        }
    }

    // ============================================================
    // УПРАВЛЕНИЕ МЕТКАМИ ЗАГРУЗКИ
    // ============================================================

    /**
     * Сохраняет информацию об успешно скачанном файле
     */
    private fun markDownloadComplete(versionCode: Int) {
        prefs.edit { putBoolean("$DOWNLOAD_COMPLETE_KEY$versionCode", true) }
        Logger.d("SystemUpdateManager", "✅ Отмечено завершение загрузки для версии $versionCode")
    }

    /**
     * Проверяет, был ли файл успешно скачан
     */
    private fun isDownloadComplete(versionCode: Int): Boolean {
        return prefs.getBoolean("$DOWNLOAD_COMPLETE_KEY$versionCode", false)
    }

    /**
     * Очищает метку о загрузке
     */
    fun clearDownloadMark(versionCode: Int) {
        prefs.edit { remove("$DOWNLOAD_COMPLETE_KEY$versionCode") }
        Logger.d("SystemUpdateManager", "🗑️ Очищена метка загрузки для версии $versionCode")
    }

    /**
     * Очищает старые метки загрузки (для версий, которые уже не актуальны)
     */
    private fun cleanupOldDownloadMarks() {
        try {
            val currentVersionCode = getCurrentVersionCode()
            val allKeys = prefs.all.keys
            var removedCount = 0

            allKeys.forEach { key ->
                if (key.startsWith(DOWNLOAD_COMPLETE_KEY)) {
                    val versionCode = key.replace(DOWNLOAD_COMPLETE_KEY, "").toIntOrNull()
                    if (versionCode != null && versionCode <= currentVersionCode) {
                        prefs.edit { remove(key) }
                        removedCount++
                    }
                }
            }

            if (removedCount > 0) {
                Logger.d("SystemUpdateManager", "🗑️ Очищено $removedCount старых меток загрузки")
            }
        } catch (_: Exception) {
            // Игнорируем ошибки
        }
    }

    // ============================================================
    // СКАЧИВАНИЕ И УСТАНОВКА
    // ============================================================

    /**
     * Скачивает APK файл
     * @param versionInfo информация о версии
     * @param listener слушатель прогресса
     * @param forceDownload принудительно перескачать (игнорировать существующий)
     * @return true при успехе
     */
    suspend fun downloadApk(
        versionInfo: VersionInfo,
        listener: DownloadListener,
        forceDownload: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getApkFileName(versionInfo.version)

            // Проверяем, существует ли уже скачанный APK
            if (!forceDownload) {
                val (exists, existingUri) = checkIfApkExists(versionInfo)
                if (exists && existingUri != null) {
                    Logger.d("SystemUpdateManager", "✅ APK уже скачан: $fileName")
                    withContext(Dispatchers.Main) {
                        listener.onSuccess(existingUri)
                    }
                    return@withContext true
                }
            }

            // Проверяем наличие свободного места
            val fileSize = versionInfo.size.toLongOrNull() ?: (20 * 1024 * 1024) // 20 MB по умолчанию
            if (!hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext false
            }

            // Создаём временный файл
            val tempFile = storageManager.createTempFile(
                prefix = "apk_download_${versionInfo.versionCode}",
                extension = "tmp"
            )

            Logger.d("SystemUpdateManager", "📥 Начинаем скачивание APK: $fileName")

            // Скачиваем через UpdateHelper
            val file = UpdateHelper.downloadFile(
                context = context,
                url = versionInfo.downloadUrl,
                listener = object : UpdateHelper.DownloadListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }

                    override fun onSuccess(file: File) {
                        // Файл скачан во временный файл
                    }

                    override fun onError(message: String) {
                        listener.onError(message)
                    }
                },
                destination = UpdateHelper.Destination.CustomPath(tempFile.absolutePath),
                connectTimeout = 15000,
                readTimeout = 30000
            )

            if (file == null || !file.exists()) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_empty_file))
                }
                return@withContext false
            }

            // Проверяем, что файл не пустой
            if (file.length() == 0L) {
                file.delete()
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_empty_file))
                }
                return@withContext false
            }

            // Сохраняем через StorageManager
            val success = storageManager.set(
                directory = StorageDirectory.UPDATES,
                name = fileName,
                inputStream = file.inputStream(),
                overwrite = true
            )

            // Удаляем временный файл
            file.delete()

            if (!success) {
                Logger.e("SystemUpdateManager", "❌ Ошибка сохранения APK")
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_error))
                }
                return@withContext false
            }

            // Отмечаем загрузку как завершённую
            markDownloadComplete(versionInfo.versionCode)

            // Получаем URI для установки
            val savedFile = storageManager.get(StorageDirectory.UPDATES, fileName)
            val uri = storageManager.getUri(savedFile)

            if (uri == null) {
                Logger.e("SystemUpdateManager", "❌ Не удалось получить URI для APK")
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_error))
                }
                return@withContext false
            }

            Logger.d("SystemUpdateManager", "✅ APK сохранён: $fileName")
            withContext(Dispatchers.Main) {
                listener.onSuccess(uri)
            }
            true

        } catch (e: Exception) {
            Logger.e("SystemUpdateManager", "❌ Ошибка скачивания APK: ${e.message}")
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            false
        }
    }

    /**
     * Проверяет, достаточно ли свободного места для загрузки файла
     */
    private fun hasEnoughFreeSpace(requiredSpace: Long): Boolean {
        return try {
            val freeSpace = storageManager.getDirectory(StorageDirectory.BASE).freeSpace
            // Проверяем, что свободного места как минимум на 10% больше требуемого
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            // Если не удалось проверить — считаем, что места достаточно
            true
        }
    }

    /**
     * Установка APK из Uri
     * @param uri URI APK файла
     * @param versionCode версия для очистки метки (опционально)
     */
    fun installApk(uri: Uri, versionCode: Int? = null) {
        try {
            Logger.d("SystemUpdateManager", "📲 Запуск установки APK")

            // Проверяем разрешение на установку (для Android 8+)
            if (!context.packageManager.canRequestPackageInstalls()) {
                Logger.w("SystemUpdateManager", "⚠️ Нет разрешения на установку, открываем настройки")
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
                // Для Android 7+ нужна поддержка FileProvider
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startActivity(intent)

            // Очищаем метку загрузки после запуска установки
            versionCode?.let { code ->
                clearDownloadMark(code)
            }

        } catch (e: Exception) {
            Logger.e("SystemUpdateManager", "❌ Ошибка запуска установки: ${e.message}")
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

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess(uri: Uri)
        fun onError(message: String)
    }
}