// *** core/UpdateManager.kt *** //
package by.quty.launch.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
            0 // В случае ошибки возвращаем 0
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
     * Скачивание APK
     */
    suspend fun downloadApk(versionInfo: VersionInfo, listener: DownloadListener): Boolean = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(context.getExternalFilesDir("updates"), "Quty.Launch-${versionInfo.version}.apk")
            apkFile.parentFile?.mkdirs()

            val connection = URL(versionInfo.downloadUrl).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = apkFile.outputStream()

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

            withContext(Dispatchers.Main) {
                listener.onSuccess(apkFile)
            }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: "Ошибка скачивания")
            }
            false
        }
    }

    /**
     * Установка APK
     */
    fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

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
        fun onSuccess(file: File)
        fun onError(message: String)
    }
}