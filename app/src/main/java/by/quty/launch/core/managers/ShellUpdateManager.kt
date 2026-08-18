// *** core/managers/ShellUpdateManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import by.quty.launch.R
import by.quty.launch.core.utilities.UpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Информация об обновлении оболочки из репозитория
 */
@Serializable
data class ShellRepoInfo(
    val name: String,
    val version: String,
    val downloadUrl: String,
    val changelog: String = "",
    val fileSize: String = "",
    val minQutyLaunchVersion: String? = null
)

/**
 * Менеджер обновления оболочек оформления
 * Проверяет наличие обновлений в репозитории и скачивает их
 */
class ShellUpdateManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storageManager = StorageManager(context)

    /**
     * Проверяет наличие обновления для оболочки
     * @param shell оболочка для проверки
     * @return ShellRepoInfo если обновление доступно, иначе null
     */
    suspend fun checkForUpdate(shell: Shell): ShellRepoInfo? = withContext(Dispatchers.IO) {
        val repoUrl = shell.repoUrl ?: return@withContext null

        return@withContext try {
            // Формируем URL для shell.json
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

                // Проверяем совместимость с Quty.Launch
                if (!isLauncherCompatible(repoInfo.minQutyLaunchVersion)) {
                    LoggerManager.d("ShellUpdateManager", context.getString(R.string.log_shell_update_incompatible))
                    return@withContext null
                }

                // Сравниваем версии
                val currentVersion = shell.version ?: "0.0.0"
                if (UpdateHelper.isNewerVersion(repoInfo.version, currentVersion)) {
                    LoggerManager.d("ShellUpdateManager", context.getString(R.string.log_shell_update_found, repoInfo.version, currentVersion))
                    repoInfo
                } else {
                    LoggerManager.d("ShellUpdateManager", context.getString(R.string.log_shell_update_not_found, currentVersion, repoInfo.version))
                    null
                }
            } else {
                LoggerManager.w("ShellUpdateManager", context.getString(R.string.log_shell_update_check_error, connection.responseCode))
                null
            }
        } catch (e: Exception) {
            LoggerManager.e("ShellUpdateManager", context.getString(R.string.log_shell_update_check_exception, e.message))
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
     * @param repoInfo информация об обновлении
     * @param listener слушатель прогресса
     * @return true при успехе
     */
    suspend fun downloadShellUpdate(
        repoInfo: ShellRepoInfo,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        var tempFile: File? = null

        try {
            // Создаём временный файл для скачивания
            tempFile = storageManager.createTempFile(
                prefix = "shell_update_${repoInfo.name}",
                extension = "tmp"
            )

            // Проверяем наличие свободного места
            val requiredSpace = repoInfo.fileSize.toLongOrNull() ?: (5 * 1024 * 1024) // 5 MB по умолчанию
            if (!hasEnoughFreeSpace(requiredSpace)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.shell_update_not_enough_space))
                }
                return@withContext false
            }

            // Скачиваем файл через UpdateHelper
            val file = UpdateHelper.downloadFile(
                context = context,
                url = repoInfo.downloadUrl,
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

            // Сохраняем оболочку через StorageManager
            val fileName = "${repoInfo.name}${ShellManager.SHELL_EXTENSION_WITH_DOT}"
            val success = storageManager.set(
                directory = StorageDirectory.SHELLS,
                name = fileName,
                inputStream = file.inputStream(),
                overwrite = true
            )

            // Удаляем временный файл
            file.delete()

            if (success) {
                LoggerManager.d("ShellUpdateManager", context.getString(R.string.log_shell_update_saved, fileName))
                withContext(Dispatchers.Main) {
                    listener.onSuccess()
                }
                true
            } else {
                LoggerManager.e("ShellUpdateManager", context.getString(R.string.log_shell_update_save_error))
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.shell_install_error))
                }
                false
            }

        } catch (e: Exception) {
            LoggerManager.e("ShellUpdateManager", context.getString(R.string.log_shell_update_download_error, e.message))
            tempFile?.delete()
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
     * Слушатель прогресса скачивания обновления
     */
    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }
}