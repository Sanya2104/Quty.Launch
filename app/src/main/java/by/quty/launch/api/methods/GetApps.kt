// *** api/methods/GetApps.kt *** //
package by.quty.launch.api.methods

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.core.content.ContextCompat
import by.quty.launch.R
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.AppInfo
import by.quty.launch.core.managers.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

class GetApps(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String {
        // 1. Пробуем получить из кэша
        val cachedApps = CacheManager.getCachedApps(context)
        if (cachedApps != null) {
            return json.encodeToString(
                ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
                ApiResponse(success = true, data = cachedApps)
            )
        }

        // 2. Если кэша нет - загружаем свежие
        val freshApps = loadFreshApps()

        // 3. Сохраняем в кэш
        CacheManager.saveApps(context, freshApps)

        return json.encodeToString(
            ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
            ApiResponse(success = true, data = freshApps)
        )
    }

    @SuppressLint("QueryPermissionsNeeded")
    private suspend fun loadFreshApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager

        // Android 11+ ограничивает видимость пакетов.
        // Мы используем getInstalledApplications(0) + фильтр по LAUNCHER,
        // что соответствует объявленному в манифесте <queries>.
        // Это позволяет видеть только приложения с иконкой на рабочем столе.
        val realApps = packageManager
            .getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { appInfo ->
                val icon = packageManager.getApplicationIcon(appInfo.packageName)
                AppInfo(
                    name = packageManager.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isCustom = false,
                    iconBase64 = drawableToBase64(icon)  // иконка обычного приложения
                )
            }
            .sortedBy { it.name }  // Сортируем обычные приложения по алфавиту

        // Загружаем иконку для настроек из ресурсов
        val settingsIcon = ContextCompat.getDrawable(context, R.drawable.ic_settings)
        val settingsIconBase64 = settingsIcon?.let { drawableToBase64(it) }

        // Кастомные приложения
        val customApps = mutableListOf<AppInfo>()

        // 1. Настройки Quty.Launch
        customApps.add(
            AppInfo(
                name = context.getString(R.string.api_getapps_settings_name),
                packageName = "by.quty.launch.settings",
                isCustom = true,
                iconBase64 = settingsIconBase64  // иконка из ресурсов
            )
        )

        // 2. Логгер (только в DevMode)
        val prefs = context.getSharedPreferences("developer_prefs", Context.MODE_PRIVATE)
        val isDevMode = prefs.getBoolean("developer_mode", false)

        if (isDevMode) {
            val loggerIcon = ContextCompat.getDrawable(context, R.drawable.ic_logger)
            val loggerIconBase64 = loggerIcon?.let { drawableToBase64(it) }

            customApps.add(
                AppInfo(
                    name = context.getString(R.string.logger_app_name),
                    packageName = "by.quty.launch.logger",
                    isCustom = true,
                    iconBase64 = loggerIconBase64
                )
            )
        }

        // Объединяем: сначала кастомные, потом обычные
        customApps + realApps
    }

    /**
     * Конвертирует Drawable в Base64 строку
     */
    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                // Создаем bitmap из drawable
                val bitmap = createBitmap(
                    drawable.intrinsicWidth.takeIf { it > 0 } ?: 64,
                    drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }
}