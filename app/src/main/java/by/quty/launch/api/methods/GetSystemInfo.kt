// *** api/methods/GetSystemInfo.kt *** //
package by.quty.launch.api.methods

import by.quty.launch.R
import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.api.model.SystemInfo

class GetSystemInfo : BaseApiMethod<Unit>() {

    // описание метода для ядра
    override val descriptionRes: Int
        get() = R.string.api_getsysteminfo_desc

    // иконка метода для ядра
    override val iconRes: Int
        get() = R.drawable.ic_api_getsysteminfo

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