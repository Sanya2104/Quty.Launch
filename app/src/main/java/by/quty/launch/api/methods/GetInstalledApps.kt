// *** api/methods/GetInstalledApps.kt *** //
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
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

class GetInstalledApps(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String {
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

        // Создаем список кастомных приложений
        val customApps = mutableListOf<AppInfo>()

        // Загружаем иконку для настроек из ресурсов
        val settingsIcon = ContextCompat.getDrawable(context, R.drawable.ic_settings)
        val settingsIconBase64 = settingsIcon?.let { drawableToBase64(it) }

        // Добавляем кастомное приложение "Настройки" в начало списка
        customApps.add(
            AppInfo(
                name = "Настройки лаунчера",
                packageName = "by.quty.launch.settings",
                isCustom = true,
                iconBase64 = settingsIconBase64  // иконка из ресурсов
            )
        )

        // Здесь можно добавить другие кастомные приложения в будущем
        // customApps.add(AppInfo(...))

        // Объединяем: сначала кастомные, потом обычные
        val allApps = customApps + realApps

        return json.encodeToString(
            ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
            ApiResponse(success = true, data = allApps)
        )
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
                val bitmap = createBitmap(drawable.intrinsicWidth.takeIf { it > 0 } ?: 64, drawable.intrinsicHeight.takeIf { it > 0 } ?: 64)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}