// *** core/UpdateManager.kt *** //
package by.quty.launch.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit

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

class UpdateManager(private val context: Context) {

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

                UpdateCheckResult(
                    hasUpdate = hasUpdate,
                    versionInfo = if (hasUpdate) versionInfo else null
                )
            } else {
                UpdateCheckResult(hasUpdate = false, error = "Ошибка сервера: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            UpdateCheckResult(hasUpdate = false, error = e.message)
        }
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
    private fun clearDownloadMark(versionCode: Int) {
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
     * @param versionInfo информация о версии
     * @return Pair(существует ли файл, Uri файла если существует)
     */
    suspend fun checkIfApkExists(versionInfo: VersionInfo): Pair<Boolean, Uri?> = withContext(Dispatchers.IO) {
        try {
            val fileName = "Quty.Launch-${versionInfo.version}.apk"

            // 1. Проверяем метку о завершении загрузки
            if (!isDownloadComplete(versionInfo.versionCode)) {
                // Если загрузка не была завершена — файл невалидный
                // Удаляем битый файл, если он есть
                deleteFileIfExists(fileName)
                return@withContext Pair(false, null)
            }

            // 2. Ищем файл в MediaStore с проверкой размера
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

                    // Проверяем, что файл не пустой (хотя бы 1 байт)
                    if (size > 0) {
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        return@withContext Pair(true, uri)
                    } else {
                        // Файл пустой/битый — удаляем
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

            // Если не нашли в MediaStore, проверяем через File
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            if (file.exists() && file.length() > 0) {
                // Пробуем получить Uri через MediaStore
                val uri = getUriByFileName(fileName)
                if (uri != null) {
                    return@withContext Pair(true, uri)
                } else {
                    // Если не получилось получить Uri, удаляем файл
                    file.delete()
                    clearDownloadMark(versionInfo.versionCode)
                    return@withContext Pair(false, null)
                }
            }

            // Если файл есть, но метки нет или файл пустой — удаляем
            if (file.exists()) {
                file.delete()
                clearDownloadMark(versionInfo.versionCode)
            }

            Pair(false, null)
        } catch (_: Exception) {
            Pair(false, null)
        }
    }

    /**
     * Получение Uri файла по имени из MediaStore
     */
    private fun getUriByFileName(fileName: String): Uri? {
        return try {
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
                    return Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Скачивание APK с проверкой на существование файла
     * @param versionInfo информация о версии
     * @param listener слушатель событий
     * @param forceDownload принудительно скачать даже если файл существует
     */
    suspend fun downloadApk(
        versionInfo: VersionInfo,
        listener: DownloadListener,
        forceDownload: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "Quty.Launch-${versionInfo.version}.apk"

            // Проверяем, существует ли уже файл
            if (!forceDownload) {
                val (exists, existingUri) = checkIfApkExists(versionInfo)
                if (exists && existingUri != null) {
                    // Файл уже существует и валидный, возвращаем его Uri
                    withContext(Dispatchers.Main) {
                        listener.onSuccess(existingUri)
                    }
                    return@withContext true
                }
            }

            // Если файла нет или принудительная загрузка - скачиваем
            downloadViaMediaStore(versionInfo, fileName, listener)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Ошибка скачивания")
            }
            false
        }
    }

    /**
     * Скачивание через MediaStore (Android 10+)
     */
    private suspend fun downloadViaMediaStore(
        versionInfo: VersionInfo,
        fileName: String,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(versionInfo.downloadUrl).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream

            // Создаём запись в MediaStore для Downloads
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            // Проверяем, не существует ли уже такой файл в MediaStore
            val existingUri = getUriByFileName(fileName)
            val contentUri = if (existingUri != null) {
                // Если файл существует, обновляем его
                context.contentResolver.update(
                    existingUri,
                    contentValues,
                    null,
                    null
                )
                existingUri
            } else {
                // Создаём новый файл
                context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: throw Exception("Не удалось создать файл в MediaStore")
            }

            val outputStream = context.contentResolver.openOutputStream(contentUri)
                ?: throw Exception("Не удалось открыть поток для записи")

            // Качаем и пишем
            val buffer = ByteArray(4096)
            var bytesRead: Int
            var totalBytesRead = 0
            var lastProgress = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileLength > 0) {
                    val progress = (totalBytesRead * 100 / fileLength)
                    if (progress > lastProgress) {
                        lastProgress = progress
                        withContext(Dispatchers.Main) {
                            listener.onProgress(progress)
                        }
                    }
                }
            }

            outputStream.close()
            inputStream.close()

            // Отмечаем загрузку как завершённую
            markDownloadComplete(versionInfo.versionCode)

            withContext(Dispatchers.Main) {
                listener.onSuccess(contentUri)
            }
            true
        } catch (e: Exception) {
            // При ошибке удаляем метку о завершении и удаляем файл
            clearDownloadMark(versionInfo.versionCode)
            deleteFileIfExists(fileName)

            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Ошибка скачивания")
            }
            false
        }
    }

    /**
     * Установка APK из Uri
     */
    fun installApk(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
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