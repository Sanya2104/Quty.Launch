// *** core/CacheManager.kt *** //
package by.quty.launch.core

import android.content.Context
import by.quty.launch.api.model.AppInfo
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

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Получить кэшированный список приложений
     * Сначала проверяет in-memory кэш (мгновенно),
     * затем пробует загрузить с диска.
     * @param context контекст приложения
     * @return список приложений или null, если кэш отсутствует/просрочен
     */
    fun getCachedApps(context: Context): List<AppInfo>? {
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

        // Сохраняем на диск в фоновом потоке (чтобы не тормозить UI)
        withContext(Dispatchers.IO) {
            saveToDisk(context, cached)
        }
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
     * @return true если кэш старше 30 минут
     */
    private fun isExpired(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS
    }

    /**
     * Очистить весь кэш (память и диск)
     * Полезно при выходе из системы или принудительной очистке
     * @param context контекст приложения
     */
    fun clearCache(context: Context) {
        memoryCache = null
        File(context.cacheDir, CACHE_FILE_NAME).delete()
    }
}