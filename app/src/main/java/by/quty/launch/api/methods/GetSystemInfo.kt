// *** api/methods/GetSystemInfo.kt *** //
package by.quty.launch.api.methods

import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.SystemInfo

class GetSystemInfo : BaseApiMethod<Unit>() {

    override fun parseParams(jsonString: String) = Unit

    override suspend fun executeInternal(params: Unit?): String {
        val info = SystemInfo(
            device = android.os.Build.MODEL,
            version = android.os.Build.VERSION.RELEASE
        )

        return json.encodeToString(
            ApiResponse.serializer(SystemInfo.serializer()),
            ApiResponse(success = true, data = info)
        )
    }
}