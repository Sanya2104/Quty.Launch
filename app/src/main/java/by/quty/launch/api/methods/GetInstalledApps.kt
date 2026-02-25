// *** api/methods/GetInstalledApps.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.AppInfo
import kotlinx.serialization.builtins.ListSerializer

class GetInstalledApps(
    private val context: Context
) : BaseApiMethod<Unit>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String {
        val packageManager = context.packageManager

        // Получаем реальные установленные приложения
        val realApps = packageManager
            .getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                AppInfo(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    isCustom = false
                )
            }
            .sortedBy { it.name }  // Сортируем обычные приложения по алфавиту

        // Создаем список кастомных приложений
        val customApps = mutableListOf<AppInfo>()

        // Добавляем кастомное приложение "Настройки" (всегда первое)
        customApps.add(
            AppInfo(
                name = "⚙️ Настройки лаунчера",
                packageName = "by.quty.launch.settings",
                isCustom = true
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
}