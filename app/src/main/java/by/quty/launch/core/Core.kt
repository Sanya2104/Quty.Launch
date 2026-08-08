// *** core/Core.kt *** //
package by.quty.launch.core

import android.content.Context
import by.quty.launch.api.ApiInitializer
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.StorageManager

/**
 * Основной движок приложения.
 * Вызывает API методы через ApiRouter.
 */
class Core(context: Context) {

    init {
        // Инициализируем все методы при старте
        ApiInitializer.init(context)

        // Регистрируем BroadcastReceiver для отслеживания изменений приложений
        // Создаём StorageManager для CacheManager
        val storageManager = StorageManager(context)
        CacheManager.init(storageManager, context)
    }

    /**
     * Выполнение метода по имени.
     *
     * @param method - имя метода (например, "GetApps")
     * @param params - JSON параметры метода
     * @return JSON ответ в формате ApiResponse
     */
    suspend fun execute(method: String, params: String?): String {
        return ApiRouter.execute(method, params)
    }
}