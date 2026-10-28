// *** core/managers/CacheManager.kt *** //
package by.quty.launch.core.managers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import by.quty.launch.api.model.AppInfo
import by.quty.launch.configs.CoreConfig
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.ref.WeakReference

/** Данные кэша приложений с временной меткой. */
@Serializable
data class CachedApps(
    val apps: List<AppInfo>,
    val timestamp: Long
)

/**
 * Кэш списка приложений: in-memory + disk, TTL 30 минут.
 * Инвалидируется при установке/удалении/замене пакетов.
 */
object CacheManager {

    private const val CACHE_FILE_NAME = CoreConfig.CACHE_APPS_FILE_NAME
    private const val CACHE_VALIDITY_MS = CoreConfig.CACHE_APPS_VALIDITY_MS

    private var memoryCache: CachedApps? = null

    private val cacheMutex = Mutex()

    private var isCacheDirty = false

    private var storageManagerRef: WeakReference<StorageManager>? = null

    private lateinit var appContext: Context

    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    invalidateCache()
                }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** Возвращает StorageManager, при необходимости пересоздаёт (WeakReference). */
    private fun getStorageManager(): StorageManager? {
        var storageManager = storageManagerRef?.get()
        if (storageManager == null) {
            if (!::appContext.isInitialized) return null
            storageManager = StorageManager(appContext)
            storageManagerRef = WeakReference(storageManager)
        }
        return storageManager
    }

    /** Инициализация кэша и регистрация receiver'а. */
    fun init(storageManager: StorageManager, context: Context) {
        this.appContext = context.applicationContext
        this.storageManagerRef = WeakReference(storageManager)
        registerPackageReceiver(context)
    }

    /** Регистрирует BroadcastReceiver для событий пакетов. */
    fun registerPackageReceiver(context: Context) {
        if (receiverRegistered) return

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }

            context.applicationContext.registerReceiver(packageReceiver, filter)
            receiverRegistered = true
        } catch (_: Exception) {
        }
    }

    /** Сбрасывает кэш и удаляет файл с диска (синхронно — чтобы холодный старт не подхватил старое). */
    fun invalidateCache() {
        memoryCache = null
        isCacheDirty = true
        LoggerManager.debug(LoggerTagModel.CORE, "CACHE.invalidate")

        val storageManager = getStorageManager()
        if (storageManager != null) {
            try {
                val cacheFile = File(
                    storageManager.getDirectory(StorageDirectory.CACHE),
                    CACHE_FILE_NAME
                )
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Возвращает кэш из памяти или диска (null, если нет/просрочен). */
    suspend fun getCachedApps(): List<AppInfo>? = withContext(Dispatchers.IO) {
        val storageManager = getStorageManager()

        if (isCacheDirty) {
            return@withContext null
        }

        cacheMutex.withLock {
            memoryCache?.let { cached ->
                if (!isExpired(cached.timestamp)) {
                    return@withContext cached.apps
                }
            }
        }

        val diskCache = loadFromDisk(storageManager)
        if (diskCache != null && !isExpired(diskCache.timestamp)) {
            cacheMutex.withLock {
                memoryCache = diskCache
            }
            return@withContext diskCache.apps
        }

        return@withContext null
    }

    /** Сохраняет приложения в память и на диск. */
    suspend fun saveApps(apps: List<AppInfo>) {
        val cached = CachedApps(apps, System.currentTimeMillis())

        cacheMutex.withLock {
            memoryCache = cached
            isCacheDirty = false
        }

        val storageManager = getStorageManager()
        if (storageManager != null) {
            saveToDisk(storageManager, cached)
        }

        LoggerManager.debug(LoggerTagModel.CORE, "CACHE.save  items=${apps.size}")
    }

    /** Запись кэша на диск. */
    private suspend fun saveToDisk(storageManager: StorageManager, cached: CachedApps) {
        try {
            val jsonString = json.encodeToString(cached)
            storageManager.set(
                directory = StorageDirectory.CACHE,
                name = CACHE_FILE_NAME,
                content = jsonString,
                overwrite = true
            )
        } catch (_: Exception) {
        }
    }

    /** Чтение кэша с диска. */
    private suspend fun loadFromDisk(storageManager: StorageManager?): CachedApps? {
        if (storageManager == null) return null

        return try {
            val jsonString = storageManager.getString(
                directory = StorageDirectory.CACHE,
                name = CACHE_FILE_NAME
            )
            if (jsonString.isNullOrEmpty()) return null
            json.decodeFromString<CachedApps>(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    /** true, если кэш помечен грязным или старше TTL. */
    private fun isExpired(timestamp: Long): Boolean {
        return isCacheDirty || System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS
    }

    /** Полностью очищает кэш (память + диск). */
    suspend fun clearCache() {
        cacheMutex.withLock {
            memoryCache = null
            isCacheDirty = true
        }

        val storageManager = getStorageManager()
        if (storageManager != null) {
            try {
                storageManager.remove(
                    directory = StorageDirectory.CACHE,
                    name = CACHE_FILE_NAME
                )
            } catch (_: Exception) {
            }
        }

        LoggerManager.info(LoggerTagModel.CORE, "CACHE.clear")
    }
}