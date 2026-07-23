// *** api/router/ApiRouter.kt *** //
package by.quty.launch.api.router

import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

object ApiRouter {
    private val methods = mutableMapOf<String, BaseApiMethod<*>>()
    private val json = Json { ignoreUnknownKeys = true }

    fun register(method: BaseApiMethod<*>) {
        methods[method.name] = method
    }

    suspend fun execute(methodName: String, params: String?): String {
        val method = methods[methodName]
            ?: return json.encodeToString(
                ApiResponse.serializer(Unit.serializer()),
                ApiResponse(success = false, error = "Method not found: $methodName")
            )
        return method.execute(params)
    }
}