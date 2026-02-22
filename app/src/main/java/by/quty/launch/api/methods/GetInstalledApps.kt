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
        val apps = packageManager
            .getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                AppInfo(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }

        return json.encodeToString(
            ApiResponse.serializer(ListSerializer(AppInfo.serializer())),
            ApiResponse(success = true, data = apps)
        )
    }
}