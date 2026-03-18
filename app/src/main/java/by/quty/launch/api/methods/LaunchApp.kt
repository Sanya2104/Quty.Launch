// *** api/methods/LaunchApp.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import android.content.Intent
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.LaunchAppParams
import by.quty.launch.SettingsActivity
import kotlinx.serialization.builtins.serializer

class LaunchApp(
    private val context: Context
) : BaseApiMethod<LaunchAppParams>() {

    override fun parseParams(jsonString: String): LaunchAppParams {
        return json.decodeFromString(jsonString)
    }

    override suspend fun executeInternal(params: LaunchAppParams?): String {
        val packageName = params?.packageName
            ?: throw IllegalArgumentException("Package name required")

        when (packageName) {
            "by.quty.launch.settings" -> {
                // Открываем настройки
                val intent = Intent(context, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Запускаем SettingsActivity
                context.startActivity(intent)

                // Примечание: Для получения результата из SettingsActivity
                // используется другой механизм в MainActivity через onActivityResult
                // или можно переделать на Activity Result API
            }
            else -> {
                // Обычное приложение
                val intent = context.packageManager
                    .getLaunchIntentForPackage(packageName)
                    ?: throw IllegalArgumentException("App not found")

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        return json.encodeToString(
            ApiResponse.serializer(Unit.serializer()),
            ApiResponse(success = true, data = Unit)
        )
    }
}