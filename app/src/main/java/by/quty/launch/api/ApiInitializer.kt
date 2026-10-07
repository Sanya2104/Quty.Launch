// *** api/ApiInitializer.kt *** //
package by.quty.launch.api

import android.content.Context
import by.quty.launch.api.methods.GetApps
import by.quty.launch.api.methods.GetCoreState
import by.quty.launch.api.methods.GetStatusBar
import by.quty.launch.api.methods.GetSystemInfo
import by.quty.launch.api.methods.LaunchApp
import by.quty.launch.api.router.ApiRouter
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager

/**
 * Инициализация всех API методов.
 *
 * Для добавления нового метода:
 * 1. Импортировать его здесь.
 * 2. Добавить в список методов.
 */
object ApiInitializer {

    fun init(context: Context) {
        // GetCoreState нужны менеджеры — создаём их здесь
        val appContext = context.applicationContext
        val configManager = ConfigManager(appContext)
        val shellManager = ShellManager(appContext, configManager)

        val methods = listOf(
            GetSystemInfo(),
            GetApps(appContext),
            LaunchApp(appContext),
            GetStatusBar(appContext),
            GetCoreState(appContext, configManager, shellManager)
        )

        methods.forEach { ApiRouter.register(it) }
    }
}