// *** api/ApiInitializer.kt *** //
package by.quty.launch.api

import android.content.Context
import by.quty.launch.api.methods.GetInstalledApps
import by.quty.launch.api.methods.GetSystemInfo
import by.quty.launch.api.methods.LaunchApp
import by.quty.launch.api.router.ApiRouter

/**
 * Инициализация всех API методов.
 *
 * Для добавления нового метода:
 * 1. Импортировать его здесь.
 * 2. Добавить в список методов.
 */
object ApiInitializer {

    fun init(context: Context) {
        val methods = listOf(
            GetSystemInfo(),
            GetInstalledApps(context),
            LaunchApp(context)
        )

        methods.forEach { ApiRouter.register(it) }
    }
}
