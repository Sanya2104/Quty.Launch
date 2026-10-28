// *** api/methods/GetApps.kt *** //
package by.quty.launch.api.methods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import by.quty.launch.R
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.AppInfo
import by.quty.launch.configs.ApiConfig
import by.quty.launch.core.managers.CacheManager
import by.quty.launch.core.managers.SystemUpdateManager
import by.quty.launch.core.utilities.BadgeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream

/**
 * Список приложений (установленные + виртуальные) с иконками Base64.
 */
class GetApps(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override val descriptionRes: Int
        get() = R.string.api_getapps_desc

    override val iconRes: Int
        get() = R.drawable.ic_api_getapps

    /** Параметров нет. */
    override fun parseParams(jsonString: String) = Unit

    /** Возвращает кэш или свежий список приложений с бейджами обновлений. */
    override suspend fun executeInternal(params: Unit?): String {
        val cachedApps = CacheManager.getCachedApps()
        if (cachedApps != null) {
            return json.encodeToString(
                ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
                ApiResponse(success = true, data = applyBadges(cachedApps))
            )
        }

        val freshApps = loadFreshApps()
        CacheManager.saveApps(freshApps)

        return json.encodeToString(
            ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
            ApiResponse(success = true, data = applyBadges(freshApps))
        )
    }

    /** Вешает маркер «есть обновление» на «Настройки Quty.Launch». */
    private fun applyBadges(apps: List<AppInfo>): List<AppInfo> {
        val cachedUpdate = try {
            SystemUpdateManager(context).getCachedUpdate()
        } catch (_: Exception) {
            null
        }

        if (cachedUpdate?.hasUpdate != true) {
            return apps
        }

        val versionInfo = cachedUpdate.versionInfo ?: return apps

        val (badgeColor, badgeColorHex) = BadgeHelper.resolveUpdateBadge(
            isCritical = versionInfo.isCritical,
            context = context
        )

        return apps.map { app ->
            if (app.packageName == ApiConfig.SETTINGS_PACKAGE) {
                app.copy(
                    badge = true,
                    badgeColor = badgeColor,
                    badgeColorHex = badgeColorHex
                )
            } else {
                app
            }
        }
    }

    /** Собирает реальные и виртуальные приложения с иконками в Base64. */
    @SuppressLint("QueryPermissionsNeeded")
    private suspend fun loadFreshApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager

        val realApps = packageManager
            .getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { appInfo ->
                val icon = packageManager.getApplicationIcon(appInfo.packageName)
                AppInfo(
                    name = packageManager.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isCustom = false,
                    iconBase64 = drawableToBase64(icon)
                )
            }
            .sortedBy { it.name }

        val customApps = mutableListOf<AppInfo>()

        val developerPrefs = context.getSharedPreferences(
            PREFS_DEVELOPER, Context.MODE_PRIVATE
        )
        val devMode = developerPrefs.getBoolean(KEY_DEVELOPER_MODE, false)

        val showTerminal = devMode && developerPrefs.getBoolean(KEY_SHOW_TERMINAL, false)
        val showLogger = devMode && developerPrefs.getBoolean(KEY_SHOW_LOGGER, false)

        val settingsIcon = ContextCompat.getDrawable(context, R.drawable.ic_app_settings)
        val settingsIconBase64 = settingsIcon?.let { drawableToBase64(it) }

        customApps.add(
            AppInfo(
                name = context.getString(R.string.api_getapps_settings_name),
                packageName = ApiConfig.SETTINGS_PACKAGE,
                isCustom = true,
                iconBase64 = settingsIconBase64
            )
        )

        val storeIcon = ContextCompat.getDrawable(context, R.drawable.ic_app_store)
        val storeIconBase64 = storeIcon?.let { drawableToBase64(it) }

        customApps.add(
            AppInfo(
                name = context.getString(R.string.api_getapps_store_name),
                packageName = ApiConfig.STORE_PACKAGE,
                isCustom = true,
                iconBase64 = storeIconBase64
            )
        )

        if (showTerminal) {
            val terminalIcon = ContextCompat.getDrawable(context, R.drawable.ic_app_terminal)
            val terminalIconBase64 = terminalIcon?.let { drawableToBase64(it) }

            customApps.add(
                AppInfo(
                    name = context.getString(R.string.api_getapps_terminal_name),
                    packageName = ApiConfig.TERMINAL_PACKAGE,
                    isCustom = true,
                    iconBase64 = terminalIconBase64
                )
            )
        }

        if (showLogger) {
            val loggerIcon = ContextCompat.getDrawable(context, R.drawable.ic_app_logger)
            val loggerIconBase64 = loggerIcon?.let { drawableToBase64(it) }

            customApps.add(
                AppInfo(
                    name = context.getString(R.string.api_getapps_logger_name),
                    packageName = ApiConfig.LOGGER_PACKAGE,
                    isCustom = true,
                    iconBase64 = loggerIconBase64
                )
            )
        }

        customApps + realApps
    }

    /** Конвертирует Drawable в Base64 PNG (NO_WRAP). */
    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = drawable.toBitmap(
                width = 96,
                height = 96,
                config = Bitmap.Config.ARGB_8888
            )

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_DEVELOPER = "developer_prefs"
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_SHOW_TERMINAL = "show_terminal_in_apps"
        private const val KEY_SHOW_LOGGER = "show_logger_in_apps"
    }
}