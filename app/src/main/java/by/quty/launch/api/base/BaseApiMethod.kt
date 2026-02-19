// *** api/base/BaseApiMethod.kt *** //
package by.quty.launch.api.base

import kotlinx.serialization.json.Json

abstract class BaseApiMethod<P, R> {

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    open val name: String
        get() = this::class.simpleName
            ?.replaceFirstChar { it.lowercase() }
            ?: "unknown"

    suspend fun execute(params: String?): String {
        return try {
            val parsedParams = params?.let { parseParams(it) }
            val result = handle(parsedParams)
            json.encodeToString(ApiResponse(success = true, data = result))
        } catch (e: Exception) {
            json.encodeToString(ApiResponse<Unit>(
                success = false,
                error = e.message ?: "Unknown error"
            ))
        }
    }

    protected abstract suspend fun handle(params: P?): R
    protected abstract fun parseParams(jsonString: String): P
}
