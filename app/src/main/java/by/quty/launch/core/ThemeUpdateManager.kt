// *** core/ThemeUpdateManager.kt *** //
package by.quty.launch.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ThemeRepoInfo(
    val name: String,
    val version: String,
    val downloadUrl: String,
    val changelog: String = "",
    val fileSize: String = ""
)

class ThemeUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Проверяет наличие обновления для темы
     * @param theme тема для проверки
     * @return ThemeRepoInfo если есть обновление, null если нет или ошибка
     */
    suspend fun checkForUpdate(theme: Theme): ThemeRepoInfo? = withContext(Dispatchers.IO) {
        val repoUrl = theme.repoUrl ?: return@withContext null

        return@withContext try {
            val url = if (repoUrl.endsWith("/")) {
                "${repoUrl}theme.json"
            } else {
                "$repoUrl/theme.json"
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                val repoInfo = json.decodeFromString<ThemeRepoInfo>(jsonString)

                // Сравниваем версии
                val currentVersion = theme.version ?: "0.0.0"
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
     * Сравнивает версии (формат x.y.z)
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        return try {
            val newParts = newVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }

                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Скачивает обновление темы
     */
    suspend fun downloadThemeUpdate(
        repoInfo: ThemeRepoInfo,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(repoInfo.downloadUrl).openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream

            // Сохраняем во временную папку
            val tempFile = File(context.cacheDir, "theme_update_${System.currentTimeMillis()}.qutytheme")
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

            // Копируем файл в папку QutyThemes
            val themesDir = File(
                android.os.Environment.getExternalStorageDirectory(),
                "QutyThemes"
            )
            if (!themesDir.exists()) {
                themesDir.mkdirs()
            }

            val fileName = "${repoInfo.name}${ThemeManager.THEME_EXTENSION_WITH_DOT}"
            val destFile = File(themesDir, fileName)

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
                listener.onError(e.message ?: "Ошибка скачивания")
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