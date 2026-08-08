// *** core/managers/CacheManager.kt *** //
package by.quty.launch.core.managers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import by.quty.launch.R
import by.quty.launch.api.model.AppInfo
import by.quty.launch.core.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference

/**
 * Менеджер кэширования списка приложений
 * Хранит приложения в двух уровнях:
 * 1. In-memory кэш (оперативная память) — для мгновенного доступа
 * 2. Disk-кэш (файл на диске) — для сохранения между запусками
 * Кэш считается валидным 30 минут, после чего обновляется
 */
@Serializable
data class CachedApps(
    val apps: List<AppInfo>,      // список приложений
    val timestamp: Long           // время последнего обновления
)

object CacheManager {
    private const val CACHE_FILE_NAME = "apps_cache"
    private const val CACHE_VALIDITY_MS = 30 * 60 * 1000 // 30 минут

    // In-memory кэш (самый быстрый доступ)
    private var memoryCache: CachedApps? = null

    // Флаг, что кэш нужно принудительно обновить
    private var isCacheDirty = false

    // StorageManager - хранится в WeakReference для предотвращения утечек памяти
    private var storageManagerRef: WeakReference<StorageManager>? = null

    // Регистрация BroadcastReceiver
    private var receiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            val data = intent.data
            val packageName = data?.schemeSpecificPart

            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    Logger.d("CacheManager", context.getString(R.string.cache_manager_package_changed, action, packageName))
                    invalidateCache()
                }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Получает StorageManager из WeakReference
     * @return StorageManager или null, если сборщик мусора уже очистил ссылку
     */
    private fun getStorageManager(): StorageManager? {
        return storageManagerRef?.get()
    }

    /**
     * Инициализация CacheManager
     * @param storageManager экземпляр StorageManager (хранится в WeakReference)
     */
    fun init(storageManager: StorageManager) {
        this.storageManagerRef = WeakReference(storageManager)
    }

    /**
     * Регистрирует BroadcastReceiver для отслеживания изменений в приложениях
     * @param context контекст приложения
     */
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
            Logger.d("CacheManager", context.getString(R.string.cache_manager_receiver_registered))
        } catch (e: Exception) {
            Logger.e("CacheManager", context.getString(R.string.cache_manager_receiver_register_error, e.message))
        }
    }

    /**
     * Принудительно инвалидирует кэш
     * Вызывается при изменении списка приложений
     */
    fun invalidateCache() {
        memoryCache = null
        isCacheDirty = true
        Logger.d("CacheManager", "🔄 Кэш приложений инвалидирован")
    }

    /**
     * Получить кэшированный список приложений
     * Сначала проверяет in-memory кэш (мгновенно),
     * затем пробует загрузить с диска.
     * @return список приложений или null, если кэш отсутствует/просрочен
     */
    suspend fun getCachedApps(): List<AppInfo>? = withContext(Dispatchers.IO) {
        val storageManager = getStorageManager()

        // Если кэш помечен как грязный — пропускаем
        if (isCacheDirty) {
            Logger.d("CacheManager", "⏭️ Кэш пропущен (грязный флаг)")
            return@withContext null
        }

        // 1. Проверяем память (самый быстрый способ)
        memoryCache?.let { cached ->
            if (!isExpired(cached.timestamp)) {
                return@withContext cached.apps
            }
        }

        // 2. Проверяем диск
        val diskCache = loadFromDisk(storageManager)
        if (diskCache != null && !isExpired(diskCache.timestamp)) {
            memoryCache = diskCache // сохраняем в память для будущих запросов
            return@withContext diskCache.apps
        }

        return@withContext null
    }

    /**
     * Сохранить приложения в кэш
     * Сохраняет одновременно:
     * - в оперативную память (in-memory)
     * - на диск (в фоновом потоке)
     * @param apps список приложений для сохранения
     */
    suspend fun saveApps(apps: List<AppInfo>) {
        val cached = CachedApps(apps, System.currentTimeMillis())

        // Сохраняем в память (мгновенно)
        memoryCache = cached
        // Сбрасываем флаг грязного кэша
        isCacheDirty = false

        // Сохраняем на диск в фоновом потоке
        val storageManager = getStorageManager()
        if (storageManager != null) {
            saveToDisk(storageManager, cached)
        }

        Logger.d("CacheManager", "✅ Сохранено приложений в кэш: ${apps.size}")
    }

    /**
     * Сохранить кэш на диск через StorageManager
     * @param storageManager экземпляр StorageManager
     * @param cached данные для сохранения
     */
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
            // Игнорируем ошибки кэширования — приложение работает и без кэша
        }
    }

    /**
     * Загрузить кэш с диска через StorageManager
     * @param storageManager экземпляр StorageManager или null
     * @return закэшированные данные или null
     */
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

    /**
     * Проверить, не устарел ли кэш
     * @param timestamp время создания кэша
     * @return true если кэш старше 30 минут или есть флаг грязного кэша
     */
    private fun isExpired(timestamp: Long): Boolean {
        if (isCacheDirty) return true
        return System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS
    }

    /**
     * Очищает кэш приложений (in-memory и disk)
     */
    suspend fun clearCache() {
        // Очищаем in-memory кэш
        memoryCache = null
        isCacheDirty = true

        // Удаляем файл кэша с диска через StorageManager
        val storageManager = getStorageManager()
        if (storageManager != null) {
            try {
                storageManager.remove(
                    directory = StorageDirectory.CACHE,
                    name = CACHE_FILE_NAME
                )
                Logger.d("CacheManager", "🗑️ Кэш приложений очищен")
            } catch (_: Exception) {
                // Игнорируем ошибки
            }
        }
    }
}