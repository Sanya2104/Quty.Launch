// *** core/managers/StoreManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import by.quty.launch.core.model.ShellStoreModel
import by.quty.launch.configs.CoreConfig
import by.quty.launch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Менеджер магазина оболочек
 * Загружает список оболочек из репозитория, управляет установкой
 */
class StoreManager(private val context: Context) {

    private val storageManager = StorageManager(context)
    private val shellManager = ShellManager(context, ConfigManager(context))
    private val json = Json { ignoreUnknownKeys = true }

    // Кэш списка оболочек
    private var cachedShells: List<ShellStoreModel>? = null

    /**
     * Загружает список оболочек из репозитория
     * @return список оболочек или null в случае ошибки
     */
    suspend fun fetchShells(): List<ShellStoreModel>? = withContext(Dispatchers.IO) {
        try {
            cachedShells?.let { return@withContext it }

            val connection = URL(CoreConfig.STORE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                var jsonString = connection.inputStream.bufferedReader().use { it.readText() }

                // Удаляем BOM, если он есть
                if (jsonString.startsWith("\ufeff")) {
                    jsonString = jsonString.substring(1)
                }

                val wrapper = json.decodeFromString<ShellsWrapper>(jsonString)
                cachedShells = wrapper.shells

                // Отмечаем установленные
                val installedShells = shellManager.getAvailableShells().map { it.name }
                LoggerManager.d("StoreManager", context.getString(R.string.log_store_installed_shells_from_manager, installedShells))

                cachedShells = cachedShells?.map { shell ->
                    val isInstalled = installedShells.contains(shell.name)
                    LoggerManager.d("StoreManager", context.getString(R.string.log_store_shell_installed_check, shell.name, isInstalled))
                    shell.copy(isInstalled = isInstalled)
                }
                cachedShells = cachedShells?.map { shell ->
                    shell.copy(isInstalled = installedShells.contains(shell.name))
                }

                LoggerManager.d("StoreManager", context.getString(R.string.log_store_shells_loaded, cachedShells?.size ?: 0))
                cachedShells
            } else {
                LoggerManager.e("StoreManager", context.getString(R.string.log_store_load_error, connection.responseCode))
                null
            }
        } catch (e: Exception) {
            LoggerManager.e("StoreManager", context.getString(R.string.log_store_error, e.message))
            null
        }
    }

    /**
     * Получает оболочку по ID
     */
    fun getShellById(id: String): ShellStoreModel? {
        return cachedShells?.find { it.id == id }
    }

    /**
     * Скачивает и устанавливает оболочку
     * @param shell оболочка для установки
     * @param listener слушатель прогресса
     * @return true при успехе
     */
    suspend fun installShell(
        shell: ShellStoreModel,
        listener: DownloadListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = "${shell.name}${CoreConfig.SHELL_EXTENSION_WITH_DOT}"

            // Проверяем, не установлена ли уже
            if (shell.isInstalled) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_already_installed))
                }
                return@withContext false
            }

            // Проверяем совместимость
            if (!isLauncherCompatible(shell.minQutyLaunchVersion)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_incompatible))
                }
                return@withContext false
            }

            // Скачиваем файл
            val tempFile = storageManager.createTempFile(
                prefix = "store_shell_${shell.name}",
                extension = "tmp"
            )

            val downloadResult = downloadFile(
                url = shell.downloadUrl,
                destination = tempFile,
                listener = object : DownloadProgressListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }
                }
            )

            if (!downloadResult) {
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_download_error))
                }
                return@withContext false
            }

            // Сохраняем в хранилище
            val success = storageManager.set(
                directory = StorageDirectory.SHELLS,
                name = fileName,
                inputStream = tempFile.inputStream(),
                overwrite = true
            )

            tempFile.delete()

            if (!success) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_save_error))
                }
                return@withContext false
            }

            // Обновляем кэш
            cachedShells = cachedShells?.map {
                if (it.id == shell.id) it.copy(isInstalled = true) else it
            }

            LoggerManager.d("StoreManager", context.getString(R.string.log_store_shell_installed, shell.displayName))

            withContext(Dispatchers.Main) {
                listener.onSuccess()
            }
            true

        } catch (e: Exception) {
            LoggerManager.e("StoreManager", context.getString(R.string.log_store_install_error, e.message))
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.store_install_error))
            }
            false
        }
    }

    /**
     * Скачивает файл по URL
     */
    private suspend fun downloadFile(
        url: String,
        destination: File,
        listener: DownloadProgressListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext false
            }

            val inputStream = connection.inputStream
            val outputStream = destination.outputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            val fileSize = connection.contentLength.toLong()
            var lastProgress = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileSize > 0) {
                    val progress = (totalBytesRead * 100 / fileSize).toInt()
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
            connection.disconnect()

            destination.exists() && destination.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Проверяет совместимость с версией Quty.Launch
     */
    private fun isLauncherCompatible(minVersion: String): Boolean {
        if (minVersion.isEmpty()) return true
        val currentVersion = getCurrentLauncherVersion()
        if (currentVersion.isEmpty()) return true
        return compareVersions(currentVersion, minVersion) >= 0
    }

    private fun getCurrentLauncherVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                0
            )
            packageInfo.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

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
     * Очищает кэш магазина
     */
    fun clearCache() {
        cachedShells = null
    }

    /**
     * Возвращает кэшированный список оболочек
     * Если кэш пуст — возвращает null
     */
    fun getCachedShells(): List<ShellStoreModel>? = cachedShells

    /**
     * Проверяет, загружены ли данные
     */
    fun isDataLoaded(): Boolean = cachedShells != null

    // ============================================================
    // ВНУТРЕННИЕ КЛАССЫ
    // ============================================================

    @kotlinx.serialization.Serializable
    data class ShellsWrapper(
        val shells: List<ShellStoreModel>
    )

    interface DownloadListener {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }

    interface DownloadProgressListener {
        fun onProgress(percent: Int)
    }
}