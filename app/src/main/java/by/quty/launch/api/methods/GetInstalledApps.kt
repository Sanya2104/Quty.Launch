// *** api/methods/GetInstalledApps.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.model.AppInfo

class GetInstalledApps(
    private val context: Context
) : BaseApiMethod<Unit, List<AppInfo>>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun handle(params: Unit?): List<AppInfo> {
        val packageManager = context.packageManager
        return packageManager
            .getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                AppInfo(
                    name = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }
    }
}
