// *** api/methods/GetSystemInfo.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.model.SystemInfo

class GetSystemInfo(
    private val context: Context
) : BaseApiMethod<Unit, SystemInfo>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun handle(params: Unit?): SystemInfo {
        return SystemInfo(
            device = android.os.Build.MODEL,
            version = android.os.Build.VERSION.RELEASE
        )
    }
}
