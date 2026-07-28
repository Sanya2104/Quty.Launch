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
import java.io.File

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
    private const val CACHE_FILE_NAME = "apps_cache.json"
    private const val CACHE_VALIDITY_MS = 30 * 60 * 1000 // 30 минут

    // In-memory кэш (самый быстрый доступ)
    private var memoryCache: CachedApps? = null

    // Флаг, что кэш нужно принудительно обновить
    private var isCacheDirty = false

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
     * Регистрирует BroadcastReceiver для отслеживания изменений в приложениях
     * Вызывается при инициализации приложения
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
     * Отменяет регистрацию BroadcastReceiver
     * Вызывается при завершении приложения
     * @param context контекст приложения
     */
    fun unregisterPackageReceiver(context: Context) {
        if (!receiverRegistered) return

        try {
            context.applicationContext.unregisterReceiver(packageReceiver)
            receiverRegistered = false
            Logger.d("CacheManager", context.getString(R.string.cache_manager_receiver_unregistered))
        } catch (e: Exception) {
            Logger.e("CacheManager", context.getString(R.string.cache_manager_receiver_unregister_error, e.message))
        }
    }

    /**
     * Принудительно инвалидирует кэш
     * Вызывается при изменении списка приложений
     */
    fun invalidateCache() {
        memoryCache = null
        isCacheDirty = true
        // Используем Logger без контекста, так как invalidateCache() вызывается из BroadcastReceiver
        // где контекст доступен, но мы не можем его передать без изменения сигнатуры
    }

    /**
     * Получить кэшированный список приложений
     * Сначала проверяет in-memory кэш (мгновенно),
     * затем пробует загрузить с диска.
     * @param context контекст приложения
     * @return список приложений или null, если кэш отсутствует/просрочен
     */
    fun getCachedApps(context: Context): List<AppInfo>? {
        // Если кэш помечен как грязный — пропускаем
        if (isCacheDirty) {
            Logger.d("CacheManager", context.getString(R.string.cache_manager_skipped_dirty))
            return null
        }

        // 1. Проверяем память (самый быстрый способ)
        memoryCache?.let { cached ->
            if (!isExpired(cached.timestamp)) {
                return cached.apps
            }
        }

        // 2. Проверяем диск
        val diskCache = loadFromDisk(context)
        if (diskCache != null && !isExpired(diskCache.timestamp)) {
            memoryCache = diskCache // сохраняем в память для будущих запросов
            return diskCache.apps
        }

        return null
    }

    /**
     * Сохранить приложения в кэш
     * Сохраняет одновременно:
     * - в оперативную память (in-memory)
     * - на диск (в фоновом потоке)
     * @param context контекст приложения
     * @param apps список приложений для сохранения
     */
    suspend fun saveApps(context: Context, apps: List<AppInfo>) {
        val cached = CachedApps(apps, System.currentTimeMillis())

        // Сохраняем в память (мгновенно)
        memoryCache = cached
        // Сбрасываем флаг грязного кэша
        isCacheDirty = false

        // Сохраняем на диск в фоновом потоке (чтобы не тормозить UI)
        withContext(Dispatchers.IO) {
            saveToDisk(context, cached)
        }

        Logger.d("CacheManager", context.getString(R.string.cache_manager_saved, apps.size))
    }

    /**
     * Сохранить кэш на диск в формате JSON
     * Использует cacheDir приложения, который автоматически очищается
     * системой при нехватке места
     * @param context контекст приложения
     * @param cached данные для сохранения
     */
    private fun saveToDisk(context: Context, cached: CachedApps) {
        try {
            val jsonString = json.encodeToString(cached)
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            cacheFile.writeText(jsonString)
        } catch (_: Exception) {
            // Игнорируем ошибки кэширования — приложение работает и без кэша
        }
    }

    /**
     * Загрузить кэш с диска
     * Читает JSON-файл из cacheDir и десериализует его
     * @param context контекст приложения
     * @return закэшированные данные или null
     */
    private fun loadFromDisk(context: Context): CachedApps? {
        return try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (!cacheFile.exists()) return null
            val jsonString = cacheFile.readText()
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
     * @param context контекст приложения
     */
    fun clearCache(context: Context) {
        // Очищаем in-memory кэш
        memoryCache = null
        isCacheDirty = true

        // Удаляем файл кэша с диска
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (_: Exception) {
            // Игнорируем ошибки
        }

        Logger.d("CacheManager", context.getString(R.string.cache_manager_cleared))
    }
}