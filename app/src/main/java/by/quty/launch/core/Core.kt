// *** core/Core.kt *** //
package by.quty.launch.core

import android.content.Context
import by.quty.launch.api.ApiInitializer
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.managers.StorageManager
import by.quty.launch.core.model.LoggerTagModel

/**
 * Движок приложения: инициализация и точка входа для API-методов.
 */
class Core(context: Context) {

    init {
        LoggerManager.init(context)
        ApiInitializer.init(context)

        val storageManager = StorageManager(context)
        CacheManager.init(storageManager, context)

        LoggerManager.info(LoggerTagModel.CORE, "CORE.init")
    }

    /** Выполняет API-метод по имени. */
    suspend fun execute(method: String, params: String?): String {
        return ApiRouter.execute(method, params)
    }
}