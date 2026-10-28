// *** core/managers/StoreManager.kt *** //
package by.quty.launch.core.managers

import android.content.Context
import by.quty.launch.R
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import by.quty.launch.core.model.StoreItemModel
import by.quty.launch.core.model.StoreItemTypeModel
import by.quty.launch.core.model.StoreRemoteItemModel
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
 * Магазин: загрузка каталога с сервера, кэш, установка оболочек.
 */
class StoreManager(private val context: Context) {

    private val storageManager = StorageManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    // ============================================================
    // ЗАГРУЗКА КАТАЛОГА
    // ============================================================

    /** Загружает каталог с сервера; при успехе обновляет кэш. */
    suspend fun fetchRemoteItems(): List<StoreRemoteItemModel>? = withContext(Dispatchers.IO) {
        try {
            LoggerManager.info(LoggerTagModel.STORE, "STORE.fetch  start")

            val connection = URL(CoreConfig.STORE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CoreConfig.READ_TIMEOUT_MS

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                var jsonString = connection.inputStream.bufferedReader().use { it.readText() }

                if (jsonString.startsWith("\uFEFF")) {
                    jsonString = jsonString.substring(1)
                }

                val wrapper = json.decodeFromString<StoreShellsWrapper>(jsonString)

                LoggerManager.info(
                    LoggerTagModel.STORE,
                    "STORE.fetch  ok  items=${wrapper.shells.size}"
                )

                saveToCache(wrapper.shells)

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

    // ============================================================
    // КЭШ
    // ============================================================

    /** Читает кэш каталога с диска. */
    suspend fun getCachedItems(): List<StoreRemoteItemModel>? = withContext(Dispatchers.IO) {
        try {
            val file = storageManager.get(StorageDirectory.CACHE, CACHE_FILE_NAME)
            if (!file.exists() || file.length() == 0L) return@withContext null

            val jsonString = file.readText()
            if (jsonString.isBlank()) return@withContext null

            val wrapper = json.decodeFromString<StoreShellsWrapper>(jsonString)
            LoggerManager.debug(
                LoggerTagModel.STORE,
                "STORE.cache.read  items=${wrapper.shells.size}"
            )
            wrapper.shells
        } catch (e: Exception) {
            LoggerManager.warn(
                LoggerTagModel.STORE,
                "STORE.cache.read  error  ${e.message ?: e.javaClass.simpleName}",
                e
            )
            null
        }
    }

    /** Удаляет кэш каталога с диска. */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            val removed = storageManager.remove(StorageDirectory.CACHE, CACHE_FILE_NAME)
            LoggerManager.info(LoggerTagModel.STORE, "STORE.cache.clear  removed=$removed")
            removed
        } catch (_: Exception) {
            false
        }
    }

    /** Сохраняет каталог в кэш на диск. */
    private suspend fun saveToCache(items: List<StoreRemoteItemModel>) {
        withContext(Dispatchers.IO) {
            try {
                val wrapper = StoreShellsWrapper(items)
                val jsonString = json.encodeToString(StoreShellsWrapper.serializer(), wrapper)
                storageManager.set(
                    directory = StorageDirectory.CACHE,
                    name = CACHE_FILE_NAME,
                    content = jsonString,
                    overwrite = true
                )
                LoggerManager.debug(
                    LoggerTagModel.STORE,
                    "STORE.cache.save  items=${items.size}"
                )
            } catch (e: Exception) {
                LoggerManager.warn(
                    LoggerTagModel.STORE,
                    "STORE.cache.save  error  ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }
        }
    }

    // ============================================================
    // УСТАНОВКА ЭЛЕМЕНТА
    // ============================================================

    /** Устанавливает элемент магазина. */
    suspend fun installItem(
        item: StoreItemModel,
        listener: InstallListener
    ): Boolean = withContext(Dispatchers.IO) {
        when (item.type) {
            StoreItemTypeModel.SHELL -> installShellInternal(item, listener)
        }
    }

    /** Скачивает .qutyshell и сохраняет в SHELLS. */
    private suspend fun installShellInternal(
        item: StoreItemModel,
        listener: InstallListener
    ): Boolean = withContext(Dispatchers.IO) {
        val remote = item.remoteInfo ?: run {
            withContext(Dispatchers.Main) {
                listener.onError(context.getString(R.string.store_install_error))
            }
            return@withContext false
        }

        try {
            val fileName = "${item.name}${CoreConfig.SHELL_EXTENSION_WITH_DOT}"

            LoggerManager.info(
                LoggerTagModel.STORE,
                "STORE.install  start  ${item.name}"
            )

            if (!isLauncherCompatible(remote.minQutyLaunchVersion)) {
                LoggerManager.warn(
                    LoggerTagModel.STORE,
                    "STORE.install  error  ${item.name}  incompatible"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_incompatible))
                }
                return@withContext false
            }

            val declaredSize = remote.fileSize.toLongOrNull() ?: (10 * 1024 * 1024)
            if (!hasEnoughFreeSpace(declaredSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext false
            }

            val tempFile = storageManager.createTempFile(
                prefix = "store_${item.name}",
                extension = "tmp"
            )

            val file = UpdateHelper.downloadFile(
                context = context,
                url = remote.downloadUrl,
                listener = object : UpdateHelper.DownloadListener {
                    override fun onProgress(percent: Int) {
                        listener.onProgress(percent)
                    }

                    override fun onSuccess(file: File) {
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
                    listener.onError(context.getString(R.string.store_download_error))
                }
                return@withContext false
            }

            if (file.length() > CoreConfig.MAX_SHELL_SIZE_BYTES) {
                file.delete()
                LoggerManager.warn(
                    LoggerTagModel.STORE,
                    "STORE.install  error  ${item.name}  too large (${file.length()} bytes)"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_download_error))
                }
                return@withContext false
            }

            val success = storageManager.set(
                directory = StorageDirectory.SHELLS,
                name = fileName,
                inputStream = file.inputStream(),
                overwrite = true
            )

            file.delete()

            if (!success) {
                LoggerManager.error(
                    LoggerTagModel.STORE,
                    "STORE.install  error  ${item.name}  save failed"
                )
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.store_save_error))
                }
                return@withContext false
            }

            LoggerManager.info(
                LoggerTagModel.STORE,
                "STORE.install  ok  ${item.name}"
            )

            withContext(Dispatchers.Main) {
                listener.onSuccess()
            }
            true

        } catch (e: Exception) {
            LoggerManager.error(
                LoggerTagModel.STORE,
                "STORE.install  error  ${item.name}  ${e.message ?: e.javaClass.simpleName}",
                e
            )
            withContext(Dispatchers.Main) {
                listener.onError(e.message ?: context.getString(R.string.store_install_error))
            }
            false
        }
    }

    /** true, если свободного места больше требуемого на 10%. */
    private fun hasEnoughFreeSpace(requiredSpace: Long): Boolean {
        return try {
            val freeSpace = storageManager.getDirectory(StorageDirectory.BASE).freeSpace
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            true
        }
    }

    /** Проверяет minQutyLaunchVersion. Если текущая версия пуста — несовместимо. */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true
        val currentVersion = getCurrentLauncherVersion()
        return currentVersion.isNotEmpty() && UpdateHelper.compareVersions(currentVersion, minVersion) >= 0
    }

    /** Возвращает текущую versionName приложения. */
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

    /** Обёртка JSON с сервера. */
    @Serializable
    data class StoreShellsWrapper(
        val shells: List<StoreRemoteItemModel>
    )

    /** Слушатель прогресса установки. */
    interface InstallListener {
        /** Прогресс скачивания (0–100). */
        fun onProgress(percent: Int)

        /** Установка завершена успешно. */
        fun onSuccess()

        /** Ошибка установки. */
        fun onError(message: String)
    }

    companion object {

        private const val CACHE_FILE_NAME = "store_shells.json"

        /**
         * Собирает объединённый список элементов магазина.
         * Сортировка: активная > обновление > установленные > остальные.
         * activeVersion "" нормализуется в null — иначе ломается isActiveSameVersion.
         */
        fun buildItems(
            remote: List<StoreRemoteItemModel>,
            localShells: List<Shell>,
            activeName: String?,
            activeVersion: String?
        ): List<StoreItemModel> {
            val result = mutableListOf<StoreItemModel>()

            val normalizedActiveVersion = activeVersion?.takeIf { it.isNotEmpty() }

            val localByName = localShells.associateBy { it.name }

            remote
                .filter { it.type == StoreItemTypeModel.SHELL.id }
                .forEach { r ->
                    val local = localByName[r.name]

                    result.add(
                        StoreItemModel(
                            name = r.name,
                            type = StoreItemTypeModel.SHELL,
                            displayName = r.displayName,
                            author = r.author,
                            version = r.version,
                            previewUrl = r.previewUrl,
                            previewBase64 = local?.previewBase64,
                            fileSize = r.fileSize,
                            datePublished = r.datePublished.ifEmpty { null },
                            minQutyLaunchVersion = r.minQutyLaunchVersion,
                            orientation = r.orientation,
                            repoUrl = r.repoUrl,
                            telegramUrl = r.telegram,
                            isInstalled = local != null,
                            installedVersion = local?.version,
                            isActive = activeName != null && activeName == r.name,
                            activeVersion = normalizedActiveVersion,
                            remoteInfo = r,
                            localShell = local
                        )
                    )
                }

            localShells.forEach { local ->
                if (remote.any { it.name == local.name && it.type == StoreItemTypeModel.SHELL.id }) {
                    return@forEach
                }

                result.add(
                    StoreItemModel(
                        name = local.name,
                        type = StoreItemTypeModel.SHELL,
                        displayName = local.displayName ?: local.name,
                        author = local.author,
                        version = local.version,
                        previewUrl = null,
                        previewBase64 = local.previewBase64,
                        fileSize = null,
                        datePublished = null,
                        minQutyLaunchVersion = local.minQutyLaunchVersion,
                        orientation = local.orientation,
                        repoUrl = local.repoUrl,
                        telegramUrl = local.telegramUrl,
                        isInstalled = true,
                        installedVersion = local.version,
                        isActive = activeName != null && activeName == local.name,
                        activeVersion = normalizedActiveVersion,
                        remoteInfo = null,
                        localShell = local
                    )
                )
            }

            return result.sortedWith(
                compareBy<StoreItemModel> {
                    when {
                        it.isActive -> 0
                        it.updateAvailable -> 1
                        it.isInstalled -> 2
                        else -> 3
                    }
                }.thenBy { it.displayName.lowercase() }
            )
        }
    }
}