// *** api/methods/GetInstalledApps.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import by.quty.launch.api.base.BaseApiMethod

class GetInstalledApps(
    private val context: Context
) : BaseApiMethod() {

    override suspend fun handle(params: String?): String {

        // Пока просто заглушка
        return """[]"""
    }
}
