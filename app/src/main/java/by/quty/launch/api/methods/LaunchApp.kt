// *** api/methods/LaunchApp.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import android.content.Intent
import by.quty.launch.R
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.LaunchAppParams
import by.quty.launch.SettingsActivity
import by.quty.launch.LoggerActivity
import kotlinx.serialization.builtins.serializer

class LaunchApp(
    private val context: Context
) : BaseApiMethod<LaunchAppParams>() {

    override fun parseParams(jsonString: String): LaunchAppParams {
        return json.decodeFromString(jsonString)
    }

    override suspend fun executeInternal(params: LaunchAppParams?): String {
        val packageName = params?.packageName
            ?: throw IllegalArgumentException(context.getString(R.string.api_launchapp_package_required))

        when (packageName) {
            "by.quty.launch.settings" -> {
                // Открываем настройки
                val intent = Intent(context, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Запускаем SettingsActivity
                context.startActivity(intent)
            }
            "by.quty.launch.logger" -> {
                // Открываем Логгер
                val intent = Intent(context, LoggerActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            else -> {
                // Обычное приложение
                val intent = context.packageManager
                    .getLaunchIntentForPackage(packageName)
                    ?: throw IllegalArgumentException(context.getString(R.string.api_launchapp_not_found))

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