// *** api/methods/LaunchApp.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import android.content.Intent
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.model.LaunchAppParams

class LaunchApp(
    private val context: Context
) : BaseApiMethod<LaunchAppParams, Unit>() {

    override fun parseParams(jsonString: String): LaunchAppParams {
        return json.decodeFromString(jsonString)
    }

    override suspend fun handle(params: LaunchAppParams?) {
        val packageName = params?.packageName
            ?: throw IllegalArgumentException("Package name required")

        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("App not found")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
