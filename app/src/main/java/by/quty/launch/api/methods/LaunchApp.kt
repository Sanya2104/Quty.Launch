// *** api/methods/LaunchApp.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import android.content.Intent
import by.quty.launch.api.base.BaseApiMethod

class LaunchApp(
    private val context: Context
) : BaseApiMethod() {

    override suspend fun handle(params: String?): String {

        val packageName = params
            ?: throw IllegalArgumentException("Требуется имя пакета")

        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("Приложение не найдено")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        // Можно вернуть пустой объект
        return """{"launched": true}"""

    }
}
