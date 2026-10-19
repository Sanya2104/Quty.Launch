// *** core/managers/StoreManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.core.utilities.AppInfoHelper
import by.quty.launch.core.utilities.UpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Менеджер магазина оболочек.
 * Загружает список оболочек из репозитория, управляет установкой.
 */
class StoreManager(private val context: Context) {

    private val storageManager = StorageManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Загружает список оболочек из репозитория.
     *
     * @return список оболочек или null в случае ошибки.
     */
    suspend fun fetchShells(): List<ShellStoreModel>? = withContext(Dispatchers.IO) {
        try {
            LoggerManager.info(LoggerTagModel.STORE, "STORE.fetch  start")

            val connection = URL(CoreConfig.STORE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CoreConfig.READ_TIMEOUT_MS

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                var jsonString = connection.inputStream.bufferedReader().use { it.readText() }

                // Удаляем BOM, если он есть
                if (jsonString.startsWith("\ufeff")) {
                    jsonString = jsonString.substring(1)
                }

                val wrapper = json.decodeFromString<ShellsWrapper>(jsonString)

                LoggerManager.info(
                    LoggerTagModel.STORE,
                    "STORE.fetch  ok  items=${wrapper.shells.size}"
                )

                wrapper.shells
            } else {
                LoggerManager.warn(
                    LoggerTagModel.STORE,
                    "STORE.fetch  error  HTTP ${connection.responseCode}"
                )
                null
            }
        } catch (e: Exception) {
            LoggerManager.error(LoggerTagModel.STORE, "STORE.fetch  error", e)
            null
        }
    }

    /**
     * Скачивает и устанавливает оболочку.
     *
     * @param shell оболочка для установки.
     * @param listener слушатель прогресса.
     * @return true при успехе.
     */
    suspend fun installShell(
        shell: ShellStoreModel,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "${shell.name}${CoreConfig.SHELL_EXTENSION_WITH_DOT}"

            LoggerManager.info(
                LoggerTagModel.STORE,
                "STORE.install  start  ${shell.name}"
            )

            // Проверяем совместимость
            if (!isLauncherCompatible(shell.minQutyLaunchVersion)) {
                LoggerManager.warn(
                    LoggerTagModel.STORE,
                    "STORE.install  error  ${shell.name}  incompatible"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(by.quty.launch.R.string.store_incompatible))
                }
                return@withContext false
            }

            // Проверяем наличие свободного места
            val fileSize = shell.fileSize.toLongOrNull() ?: (10 * 1024 * 1024) // 10 MB по умолчанию
            if (!hasEnoughFreeSpace(fileSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(by.quty.launch.R.string.download_not_enough_space))
                }
                return@withContext false
            }

            // Создаём временный файл
            val tempFile = storageManager.createTempFile(
                prefix = "store_shell_${shell.name}",
                extension = "tmp"
            )

            // Скачиваем файл
            val file = UpdateHelper.downloadFile(
                context = context,
                url = shell.downloadUrl,
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
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(by.quty.launch.R.string.store_download_error))
                }
                return@withContext false
            }

            // Сохраняем в хранилище
            val success = storageManager.set(
                directory = StorageDirectory.SHELLS,
                name = fileName,
                inputStream = file.inputStream(),
                overwrite = true
            )

            // Удаляем временный файл
            file.delete()

            if (!success) {
                LoggerManager.error(
                    LoggerTagModel.STORE,
                    "STORE.install  error  ${shell.name}  save failed"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(by.quty.launch.R.string.store_save_error))
                }
                return@withContext false
            }

            LoggerManager.info(
                LoggerTagModel.STORE,
                "STORE.install  ok  ${shell.name}"
            )

            withContext(Dispatchers.Main) {
                listener.onSuccess()
            }
            true

        } catch (e: Exception) {
            LoggerManager.error(
                LoggerTagModel.STORE,
                "STORE.install  error  ${shell.name}  ${e.message ?: e.javaClass.simpleName}",
                e
            )
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(by.quty.launch.R.string.store_install_error))
            }
            false
        }
    }

    /**
     * Проверяет, достаточно ли свободного места для загрузки файла.
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
     * Проверяет совместимость с версией Quty.Launch.
     */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true
        val currentVersion = getCurrentLauncherVersion()
        return currentVersion.isEmpty() || UpdateHelper.compareVersions(currentVersion, minVersion) >= 0
    }

    /**
     * Получает текущую версию Quty.Launch БЕЗ суффикса.
     *
     * ВАЖНО: использует AppInfoHelper.getSplitVersion(), чтобы отрезать
     * суффикс канала (Alpha/Beta/etc). Иначе "0.0.157-Alpha".split(".")
     * даёт ["0","0","157-Alpha"] → toIntOrNull() на последнем элементе
     * вернёт null → 0, и сравнение версий сломается.
     */
    private fun getCurrentLauncherVersion(): String {
        return try {
            val (versionName, _) = AppInfoHelper.getSplitVersion(context)
            versionName
        } catch (_: Exception) {
            ""
        }
    }

    // ============================================================
    // ВНУТРЕННИЕ КЛАССЫ
    // ============================================================

    @Serializable
    data class ShellsWrapper(
        val shells: List<ShellStoreModel>
    )

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }
}