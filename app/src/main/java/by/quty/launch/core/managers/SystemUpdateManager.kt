// *** core/managers/SystemUpdateManager.kt *** //
package by.quty.launch.core.managers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import by.quty.launch.R
import by.quty.launch.core.utilities.UpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import androidx.core.content.edit
import java.io.File

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

@Serializable
data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val versionInfo: VersionInfo? = null,
    val error: String? = null
)

class SystemUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val versionUrl = "https://raw.githubusercontent.com/Sanya2104/Quty.Launch.Server/main/updates/version.json"

    // SharedPreferences для меток завершённых загрузок
    private val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    /**
     * Получение текущей версии приложения через PackageManager
     */
    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.longVersionCode.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    /**
     * Проверка наличия обновлений
     */
    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(versionUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val versionInfo = json.decodeFromString<VersionInfo>(jsonString)

                val currentVersionCode = getCurrentVersionCode()
                val hasUpdate = versionInfo.versionCode > currentVersionCode

                if (!hasUpdate) {
                    cleanupOldDownloadMarks(currentVersionCode)
                    deleteOldApkFiles()
                }

                UpdateCheckResult(
                    hasUpdate = hasUpdate,
                    versionInfo = if (hasUpdate) versionInfo else null
                )
            } else {
                UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.server_error, connection.responseCode))
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
            UpdateCheckResult(hasUpdate = false, error = errorMessage)
        }
    }

    /**
     * Очищает метки загрузки для версий, которые уже не актуальны
     */
    private fun cleanupOldDownloadMarks(currentVersionCode: Int) {
        try {
            val allKeys = prefs.all.keys
            for (key in allKeys) {
                if (key.startsWith("download_complete_")) {
                    val versionCode = key.replace("download_complete_", "").toIntOrNull()
                    if (versionCode != null && versionCode <= currentVersionCode) {
                        prefs.edit { remove(key) }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Удаляет все старые APK файлы
     */
    private fun deleteOldApkFiles() {
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("Quty.Launch-%.apk"),
                null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    context.contentResolver.delete(uri, null, null)
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Сохраняет информацию об успешно скачанном файле
     */
    private fun markDownloadComplete(versionCode: Int) {
        prefs.edit { putBoolean("download_complete_$versionCode", true) }
    }

    /**
     * Проверяет, был ли файл успешно скачан
     */
    private fun isDownloadComplete(versionCode: Int): Boolean {
        return prefs.getBoolean("download_complete_$versionCode", false)
    }

    /**
     * Очищает метку о загрузке
     */
    fun clearDownloadMark(versionCode: Int) {
        prefs.edit { remove("download_complete_$versionCode") }
    }

    /**
     * Удаляет файл по имени из Download
     */
    private fun deleteFileIfExists(fileName: String) {
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    context.contentResolver.delete(uri, null, null)
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Проверка, существует ли уже скачанный APK файл для указанной версии
     */
    suspend fun checkIfApkExists(versionInfo: VersionInfo): Pair<Boolean, Uri?> = withContext(Dispatchers.IO) {
        try {
            val fileName = "Quty.Launch-${versionInfo.version}.apk"

            if (!isDownloadComplete(versionInfo.versionCode)) {
                deleteFileIfExists(fileName)
                return@withContext Pair(false, null)
            }

            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.SIZE
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE))

                    if (size > 0) {
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        return@withContext Pair(true, uri)
                    } else {
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        context.contentResolver.delete(uri, null, null)
                        clearDownloadMark(versionInfo.versionCode)
                        return@withContext Pair(false, null)
                    }
                }
            }

            Pair(false, null)
        } catch (_: Exception) {
            Pair(false, null)
        }
    }

    /**
     * Скачивание APK с проверкой на существование файла
     */
    suspend fun downloadApk(
        versionInfo: VersionInfo,
        listener: DownloadListener,
        forceDownload: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "Quty.Launch-${versionInfo.version}.apk"

            if (!forceDownload) {
                val (exists, existingUri) = checkIfApkExists(versionInfo)
                if (exists && existingUri != null) {
                    withContext(Dispatchers.Main) {
                        listener.onSuccess(existingUri)
                    }
                    return@withContext true
                }
            }

            // Используем UpdateHelper для скачивания
            val file = UpdateHelper.downloadFile(
                context = context,
                url = versionInfo.downloadUrl,
                listener = object : UpdateHelper.DownloadListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }

                    override fun onSuccess(file: File) {
                        // Сохраняем в MediaStore
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }

                        val contentUri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            contentValues
                        )

                        if (contentUri != null) {
                            context.contentResolver.openOutputStream(contentUri)?.use { os ->
                                file.inputStream().use { input ->
                                    input.copyTo(os)
                                }
                            }
                            file.delete()

                            markDownloadComplete(versionInfo.versionCode)
                            listener.onSuccess(contentUri)
                        } else {
                            listener.onError(context.getString(R.string.download_media_store_create_failed))
                        }
                    }

                    override fun onError(message: String) {
                        clearDownloadMark(versionInfo.versionCode)
                        listener.onError(message)
                    }
                },
                destination = UpdateHelper.Destination.TempFile("apk_download"),
                connectTimeout = 5000,
                readTimeout = 5000
            )

            file != null

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            false
        }
    }

    /**
     * Установка APK из Uri
     */
    fun installApk(uri: Uri, versionCode: Int? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)

            if (versionCode != null) {
                clearDownloadMark(versionCode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess(uri: Uri)
        fun onError(message: String)
    }
}