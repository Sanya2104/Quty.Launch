// *** core/managers/UpdateManager.kt *** //
package by.quty.launch.core.managers

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import by.quty.launch.R
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
     * Теперь также проверяет, не было ли обновление уже установлено
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

                // Если обновление больше не актуально (версия уже установлена)
                if (!hasUpdate) {
                    // Очищаем метки загрузки для всех версий, которые <= текущей
                    cleanupOldDownloadMarks(currentVersionCode)
                    // Удаляем файлы обновлений, которые уже не актуальны
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
            // Нет подключения к интернету
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (_: ConnectException) {
            // Не удалось подключиться к серверу
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (_: SocketTimeoutException) {
            // Таймаут подключения
            UpdateCheckResult(hasUpdate = false, error = context.getString(R.string.no_internet_connection))
        } catch (e: Exception) {
            // Другие ошибки
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

            // Проверяем метку о завершении загрузки
            if (!isDownloadComplete(versionInfo.versionCode)) {
                deleteFileIfExists(fileName)
                return@withContext Pair(false, null)
            }

            // Ищем файл в MediaStore с проверкой размера
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
     * Проверяет, достаточно ли свободного места для загрузки файла
     * @param requiredSpace требуемое место в байтах
     * @return true если места достаточно
     */
    private fun hasEnoughFreeSpace(requiredSpace: Long): Boolean {
        return try {
            val path = Environment.getExternalStorageDirectory().path
            val stat = StatFs(path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val freeSpace = blockSize * availableBlocks

            // Проверяем, что свободного места как минимум на 10% больше требуемого
            // (для запаса на системные нужды)
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            // Если не удалось проверить — считаем, что места достаточно
            // (чтобы не блокировать обновление при ошибке)
            true
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

            // Проверяем наличие свободного места перед загрузкой
            // Получаем размер файла
            val connection = URL(versionInfo.downloadUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            val fileSize = connection.contentLength.toLong()
            connection.disconnect()

            if (fileSize > 0 && !hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.update_not_enough_space))
                }
                return@withContext false
            }

            // Если файла нет или принудительная загрузка - скачиваем
            downloadViaMediaStore(versionInfo, fileName, listener)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
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

            val fileLength = connection.contentLength.toLong()
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
                ) ?: throw Exception(context.getString(R.string.update_manager_media_store_create_failed))
            }

            val outputStream = context.contentResolver.openOutputStream(contentUri)
                ?: throw Exception(context.getString(R.string.update_manager_media_store_open_failed))

            // Качаем и пишем
            val buffer = ByteArray(8192)  // Увеличен буфер для скорости
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastProgress = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileLength > 0) {
                    val progress = (totalBytesRead * 100 / fileLength).toInt()
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
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            false
        }
    }

    /**
     * Установка APK из Uri
     * При успешном запуске установки очищаем метку загрузки
     */
    fun installApk(uri: Uri, versionCode: Int? = null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)

            // Очищаем метку загрузки после запуска установки
            // Это предотвратит бесконечный цикл обновлений
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