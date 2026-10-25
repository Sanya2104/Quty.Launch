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
 * Менеджер магазина Quty.Launch.
 *
 * Отвечает за:
 * - загрузку каталога с сервера (store/shells.json)
 * - кэширование каталога (StorageDirectory.CACHE)
 * - сборку объединённой модели StoreItemModel из двух источников
 * - установку элементов (пока только оболочки)
 *
 * Файл каталога на сервере называется shells.json, но внутри
 * каждая запись имеет поле type (по умолчанию "shell"). Когда
 * появятся другие типы ресурсов (темы, шрифты), файл можно будет
 * переименовать в resources.json, а URL в CoreConfig — поменять.
 */
class StoreManager(private val context: Context) {

    private val storageManager = StorageManager(context)
    private val json = Json { ignoreUnknownKeys = true }

    // ============================================================
    // ЗАГРУЗКА КАТАЛОГА
    // ============================================================

    /**
     * Загружает каталог с сервера.
     * При успехе сохраняет свежий список в кэш.
     *
     * @return список элементов с сервера или null в случае ошибки.
     */
    suspend fun fetchRemoteItems(): List<StoreRemoteItemModel>? = withContext(Dispatchers.IO) {
        try {
            LoggerManager.info(LoggerTagModel.STORE, "STORE.fetch  start")

            val connection = URL(CoreConfig.STORE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CoreConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CoreConfig.READ_TIMEOUT_MS

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                var jsonString = connection.inputStream.bufferedReader().use { it.readText() }

                // Удаляем BOM, если он есть
                if (jsonString.startsWith("\uFEFF")) {
                    jsonString = jsonString.substring(1)
                }

                val wrapper = json.decodeFromString<StoreShellsWrapper>(jsonString)

                LoggerManager.info(
                    LoggerTagModel.STORE,
                    "STORE.fetch  ok  items=${wrapper.shells.size}"
                )

                // Сохраняем свежий список в кэш
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

    /**
     * Читает кэш каталога с диска.
     * Кэш не имеет TTL — при следующем открытии показывается сразу,
     * пока идёт фоновая загрузка с сервера.
     *
     * @return список элементов из кэша или null, если кэша нет / повреждён.
     */
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

    /**
     * Удаляет кэш каталога с диска.
     */
    suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        try {
            val removed = storageManager.remove(StorageDirectory.CACHE, CACHE_FILE_NAME)
            LoggerManager.info(LoggerTagModel.STORE, "STORE.cache.clear  removed=$removed")
            removed
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Внутренняя запись кэша на диск.
     */
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

    /**
     * Скачивает и устанавливает элемент магазина.
     *
     * Сейчас поддерживается только тип SHELL. Для остальных типов
     * вернёт ошибку — добавятся по мере появления.
     *
     * @param item элемент для установки.
     * @param listener слушатель прогресса.
     * @return true при успехе.
     */
    suspend fun installItem(
        item: StoreItemModel,
        listener: InstallListener
    ): Boolean = withContext(Dispatchers.IO) {
        when (item.type) {
            StoreItemTypeModel.SHELL -> installShellInternal(item, listener)
        }
    }

    /**
     * Установка оболочки: скачивание .qutyshell и сохранение в SHELLS.
     *
     * ВАЖНО: проверяем реальный размер скачанного файла после downloadFile.
     * remote.fileSize — человекочитаемая строка («5.2 MB»), её нельзя
     * использовать для проверки свободного места (toLongOrNull() вернёт null,
     * fallback 10 MB — бесполезно, если реальный файл 500 MB). Поэтому
     * жёсткий лимит MAX_SHELL_SIZE_BYTES после скачивания.
     */
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

            // Проверяем совместимость
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

            // Проверяем наличие свободного места (грубая оценка по remote.fileSize,
            // если не удалось распарсить — fallback 10 MB, всё равно мало,
            // финальная проверка — по реальному размеру после скачивания).
            val declaredSize = remote.fileSize.toLongOrNull() ?: (10 * 1024 * 1024)
            if (!hasEnoughFreeSpace(declaredSize)) {
                withContext(Dispatchers.Main) {
                    listener.onError(context.getString(R.string.download_not_enough_space))
                }
                return@withContext false
            }

            // Создаём временный файл
            val tempFile = storageManager.createTempFile(
                prefix = "store_${item.name}",
                extension = "tmp"
            )

            // Скачиваем файл
            val file = UpdateHelper.downloadFile(
                context = context,
                url = remote.downloadUrl,
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
                    listener.onError(context.getString(R.string.store_download_error))
                }
                return@withContext false
            }

            // Жёсткий лимит на размер .qutyshell (защита от вредоносных/сломанных серверов)
            if (file.length() > MAX_SHELL_SIZE_BYTES) {
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

    /**
     * Проверяет, достаточно ли свободного места для загрузки файла.
     */
    private fun hasEnoughFreeSpace(requiredSpace: Long): Boolean {
        return try {
            val freeSpace = storageManager.getDirectory(StorageDirectory.BASE).freeSpace
            val requiredWithBuffer = (requiredSpace * 1.1).toLong()
            freeSpace >= requiredWithBuffer
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Проверяет совместимость с версией Quty.Launch.
     *
     * ВАЖНО: если не удалось определить текущую версию (PackageManager
     * вернул пусто) — считаем НЕсовместимым. Иначе сломанная установка
     * молча «проглатывает» все оболочки.
     */
    private fun isLauncherCompatible(minVersion: String?): Boolean {
        if (minVersion.isNullOrEmpty()) return true
        val currentVersion = getCurrentLauncherVersion()
        return currentVersion.isNotEmpty() && UpdateHelper.compareVersions(currentVersion, minVersion) >= 0
    }

    /**
     * Текущая версия Quty.Launch без суффикса.
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

    /**
     * Обёртка для сериализации JSON с сервера.
     */
    @Serializable
    data class StoreShellsWrapper(
        val shells: List<StoreRemoteItemModel>
    )

    /**
     * Слушатель прогресса установки.
     */
    interface InstallListener {
        fun onProgress(percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }

    companion object {

        /** Имя файла кэша каталога. */
        private const val CACHE_FILE_NAME = "store_shells.json"

        /** Жёсткий лимит размера .qutyshell (защита от вредоносных серверов). */
        private const val MAX_SHELL_SIZE_BYTES = 100L * 1024 * 1024 // 100 MB

        /**
         * Собирает объединённый список элементов магазина.
         *
         * Логика:
         * 1. Все элементы с сервера (тип SHELL) > StoreItemModel с localShell из localShells.
         * 2. Локальные оболочки, которых нет на сервере > StoreItemModel с remoteInfo = null.
         * 3. Сортировка: активная > обновление > установленные > остальные, внутри по displayName.
         *
         * ВАЖНО: activeVersion нормализуется — пустая строка превращается
         * в null. Иначе на устройствах, где active_shell_version ещё не
         * записан (первый запуск после обновления приложения), модель
         * получит activeVersion = "" и isActiveSameVersion вернёт false,
         * что скроет бейдж «Активна» и все пункты меню (пустой экран).
         *
         * @param remote список с сервера (или из кэша).
         * @param localShells локальные оболочки (ShellManager.getAvailableShells()).
         * @param activeName имя активной оболочки (ConfigManager.getActiveShell()).
         * @param activeVersion версия, реально загруженная в WebView (ConfigManager.getActiveShellVersion()).
         */
        fun buildItems(
            remote: List<StoreRemoteItemModel>,
            localShells: List<Shell>,
            activeName: String?,
            activeVersion: String?
        ): List<StoreItemModel> {
            val result = mutableListOf<StoreItemModel>()

            // Нормализуем activeVersion: "" > null.
            // Иначе isActiveSameVersion не сможет отличить «версия неизвестна»
            // (null) от «версия пустая» ("") и вернёт false для активной
            // оболочки на устройствах с пустым active_shell_version.
            val normalizedActiveVersion = activeVersion?.takeIf { it.isNotEmpty() }

            // Индекс локальных по имени — для быстрого поиска
            val localByName = localShells.associateBy { it.name }

            // 1. Все элементы с сервера (только SHELL пока)
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

            // 2. Локальные, которых нет на сервере
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

            // 3. Сортировка: активная > обновление > установленные > остальные
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