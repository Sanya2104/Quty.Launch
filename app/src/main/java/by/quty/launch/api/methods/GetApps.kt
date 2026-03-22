// *** api/methods/GetApps.kt *** //
package by.quty.launch.api.methods

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
import by.quty.launch.core.CacheManager
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

    private suspend fun loadFreshApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager

        // Получаем реальные установленные приложения с иконками
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

        // Добавляем кастомное приложение "Настройки" в начало списка
        val customApps = listOf(
            AppInfo(
                name = "Настройки лаунчера",
                packageName = "by.quty.launch.settings",
                isCustom = true,
                iconBase64 = settingsIconBase64  // иконка из ресурсов
            )
            // Здесь можно добавить другие кастомные приложения в будущем
            // AppInfo(...)
        )

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