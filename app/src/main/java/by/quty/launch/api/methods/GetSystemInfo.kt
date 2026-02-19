// *** api/methods/GetSystemInfo.kt *** //
package by.quty.launch.api.methods

import android.content.Context
import by.quty.launch.api.base.BaseApiMethod

class GetSystemInfo(
    private val context: Context
) : BaseApiMethod() {

    override suspend fun handle(params: String?): String {

        val infoJson = """
        {
            "device": "Android",
            "version": "14"
        }
    """.trimIndent()

        return infoJson
    }

}
