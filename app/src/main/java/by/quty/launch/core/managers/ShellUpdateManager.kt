// *** core/managers/ShellUpdateManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import by.quty.launch.R
import by.quty.launch.core.utilities.UpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ShellRepoInfo(
    val name: String,
    val version: String,
    val downloadUrl: String,
    val changelog: String = "",
    val fileSize: String = "",
    val minQutyLaunchVersion: String? = null
)

class ShellUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Проверяет наличие обновления для оболочки
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
                val cleanJson = jsonString.trimStart('\uFEFF')
                val repoInfo = json.decodeFromString<ShellRepoInfo>(cleanJson)

                if (!isLauncherCompatible(repoInfo.minQutyLaunchVersion)) {
                    return@withContext null
                }

                val currentVersion = shell.version ?: "0.0.0"
                if (UpdateHelper.isNewerVersion(repoInfo.version, currentVersion)) {
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
     * Проверяет, совместима ли оболочка с текущей версией Quty.Launch
     */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true
        val currentLauncherVersion = getCurrentLauncherVersion()
        if (currentLauncherVersion.isEmpty()) return true
        return UpdateHelper.compareVersions(currentLauncherVersion, minVersion) >= 0
    }

    /**
     * Получает текущую версию Quty.Launch
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
     * Скачивает обновление оболочки
     */
    suspend fun downloadShellUpdate(
        repoInfo: ShellRepoInfo,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Используем UpdateHelper для скачивания
            val file = UpdateHelper.downloadFile(
                context = context,
                url = repoInfo.downloadUrl,
                listener = object : UpdateHelper.DownloadListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }

                    override fun onSuccess(file: File) {
                        // Копируем файл в папку Quty.Launch/Shells/
                        val appDir = File(Environment.getExternalStorageDirectory(), "Quty.Launch")
                        val shellsDir = File(appDir, "Shells")

                        if (!appDir.exists()) appDir.mkdirs()
                        if (!shellsDir.exists()) shellsDir.mkdirs()

                        val fileName = "${repoInfo.name}${ShellManager.SHELL_EXTENSION_WITH_DOT}"
                        val destFile = File(shellsDir, fileName)

                        // Удаляем старый файл, если он существует
                        if (destFile.exists()) {
                            destFile.delete()
                        }

                        file.copyTo(destFile, overwrite = true)
                        file.delete()

                        listener.onSuccess()
                    }

                    override fun onError(message: String) {
                        listener.onError(message)
                    }
                },
                destination = UpdateHelper.Destination.TempFile("shell_update"),
                connectTimeout = 15000,
                readTimeout = 30000
            )

            file != null

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