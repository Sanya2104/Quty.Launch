// *** core/ShellUpdateManager.kt *** //
package by.quty.launch.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import by.quty.launch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ShellRepoInfo(
    val name: String,
    val version: String,
    val downloadUrl: String,
    val changelog: String = "",
    val fileSize: String = "",
    val minLauncherVersion: String? = null  // минимальная версия лаунчера из shell.json
)

class ShellUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Проверяет наличие обновления для оболочки
     * @param shell оболочка для проверки
     * @return ShellRepoInfo если есть обновление, null если нет или ошибка
     */
    suspend fun checkForUpdate(shell: Shell): ShellRepoInfo? = withContext(Dispatchers.IO) {
        val repoUrl = shell.repoUrl ?: return@withContext null

        return@withContext try {
            val url = if (repoUrl.endsWith("/")) {
                "${repoUrl}shell.json"
            } else {
                "$repoUrl/shell.json"
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }

                // Убираем BOM символ, если он есть
                val cleanJson = jsonString.trimStart('\uFEFF')

                val repoInfo = json.decodeFromString<ShellRepoInfo>(cleanJson)

                // Проверяем, поддерживает ли лаунчер эту оболочку
                if (!isLauncherCompatible(repoInfo.minLauncherVersion)) {
                    return@withContext null  // Лаунчер слишком старый для этой оболочки
                }

                // Сравниваем версии
                val currentVersion = shell.version ?: "0.0.0"
                if (isNewerVersion(repoInfo.version, currentVersion)) {
                    repoInfo
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет, совместима ли оболочка с текущей версией лаунчера
     * @param minVersion минимальная версия лаунчера, требуемая оболочкой
     * @return true если лаунчер совместим
     */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true  // Если не указано — совместима

        val currentLauncherVersion = getCurrentLauncherVersion()
        if (currentLauncherVersion.isEmpty()) return true  // Не удалось получить версию

        return compareVersions(currentLauncherVersion, minVersion) >= 0
    }

    /**
     * Получает текущую версию лаунчера
     */
    private fun getCurrentLauncherVersion(): String {
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
            packageInfo.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Сравнивает две версии (формат x.y.z)
     * @return 1 если v1 > v2, 0 если равны, -1 если v1 < v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        return try {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }

                when {
                    p1 > p2 -> return 1
                    p1 < p2 -> return -1
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Сравнивает версии (формат x.y.z)
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        return compareVersions(newVersion, currentVersion) > 0
    }

    /**
     * Скачивает обновление оболочки
     */
    suspend fun downloadShellUpdate(
        repoInfo: ShellRepoInfo,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(repoInfo.downloadUrl).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream

            // Сохраняем во временную папку
            val tempFile = File(context.cacheDir, "shell_update_${System.currentTimeMillis()}.qutyshell")
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(4096)
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

            // Копируем файл в папку Quty.Launch/Shells/
            val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
            val shellsDir = File(appDir, "Shells")

            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            if (!shellsDir.exists()) {
                shellsDir.mkdirs()
            }

            // Сохраняем с основным расширением .qutyshell
            val fileName = "${repoInfo.name}${ShellManager.SHELL_EXTENSION_WITH_DOT}"
            val destFile = File(shellsDir, fileName)

            if (destFile.exists()) {
                destFile.delete()
            }

            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()

            withContext(Dispatchers.Main) {
                listener.onSuccess()
            }
            true

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.download_error))
            }
            false
        }
    }

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }
}