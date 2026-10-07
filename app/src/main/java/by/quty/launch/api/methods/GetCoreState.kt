// *** api/methods/GetCoreState.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import android.content.res.Configuration
import by.quty.launch.R
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.ColorSchemeInfo
import by.quty.launch.api.model.CoreAppInfo
import by.quty.launch.api.model.CoreStateInfo
import by.quty.launch.api.model.ShellInfo
import by.quty.launch.core.managers.ConfigManager
import by.quty.launch.core.managers.ShellManager
import by.quty.launch.core.utilities.AppInfoHelper

/**
 * Возвращает полное состояние ядра Quty.Launch для текущей оболочки.
 *
 * Оболочка вызывает этот метод при старте и при получении
 * JS-события window.onCoreStateChanged().
 */
class GetCoreState(
    private val context: Context,
    private val configManager: ConfigManager,
    private val shellManager: ShellManager
) : BaseApiMethod<Unit>() {

    override val descriptionRes: Int
        get() = R.string.api_getcorestate_desc

    override val iconRes: Int
        get() = R.drawable.ic_api_getcorestate

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String {
        val coreState = CoreStateInfo(
            theme = resolveTheme(),
            themeMode = configManager.getThemeMode(),
            language = configManager.getLanguageCode(),
            locale = resolveLocale(configManager.getLanguageCode()),
            orientation = configManager.getOrientation(),
            fullscreen = configManager.isFullscreenEnabled(),
            strictMode = configManager.isStrictModeEnabled(),
            colorScheme = ColorSchemeInfo(
                id = configManager.getColorScheme(),
                primary = configManager.getSchemePrimaryColor(),
                accent = configManager.getSchemeAccentColor()
            ),
            shell = shellManager.getActiveShell()?.let { shell ->
                ShellInfo(
                    name = shell.name,
                    displayName = shell.displayName,
                    version = shell.version,
                    author = shell.author,
                    orientation = shell.orientation
                )
            },
            app = CoreAppInfo(
                version = AppInfoHelper.getVersionName(context),
                versionCode = AppInfoHelper.getVersionCode(context)
            )
        )

        return json.encodeToString(
            ApiResponse.serializer(CoreStateInfo.serializer()),
            ApiResponse(success = true, data = coreState)
        )
    }

    /**
     * Определяет фактическую тему: dark / light.
     * Если themeMode == "system" — смотрит на системную конфигурацию.
     */
    private fun resolveTheme(): String {
        return when (configManager.getThemeMode()) {
            "dark" -> "dark"
            "light" -> "light"
            "system" -> {
                val nightMode = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
            }
            else -> "light"
        }
    }

    /**
     * Определяет BCP-47 локаль: "ru-RU" или "en-US".
     */
    private fun resolveLocale(language: String): String {
        return when (language) {
            "ru" -> "ru-RU"
            else -> "en-US"
        }
    }
}