// *** core/Core.kt *** //
package by.quty.launch.core

import android.content.Context
import by.quty.launch.R
import by.quty.launch.api.ApiInitializer
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.model.LoggerTagModel

/**
 * Основной движок приложения.
 * Вызывает API методы через ApiRouter.
 */
class Core(context: Context) {

    init {
        // Инициализируем настройки логгера из prefs (enabled, autoSave)
        // до первого логирования
        LoggerManager.init(context)

        // Инициализируем все методы при старте
        ApiInitializer.init(context)

        // Регистрируем BroadcastReceiver для отслеживания изменений приложений
        // Создаём StorageManager для CacheManager
        val storageManager = StorageManager(context)
        CacheManager.init(storageManager, context)

        LoggerManager.info(
            LoggerTagModel.CORE,
            context.getString(R.string.log_core_initialized)
        )
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