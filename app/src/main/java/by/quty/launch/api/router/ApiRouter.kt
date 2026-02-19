// *** api/router/ApiRouter.kt *** //
package by.quty.launch.api.router

import by.quty.launch.api.base.BaseApiMethod

object ApiRouter {

    private val methods = mutableMapOf<String, BaseApiMethod>()

    fun register(method: BaseApiMethod) {
        methods[method.name] = method
    }

    suspend fun execute(
        methodName: String,
        params: String?
    ): String {

        val method = methods[methodName]
            ?: return """{"success": false, "error": "Method not found"}"""

        return method.execute(params)
    }
}
