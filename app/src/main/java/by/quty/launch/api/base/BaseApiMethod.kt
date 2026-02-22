// *** api/base/BaseApiMethod.kt *** //
package by.quty.launch.api.base

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

abstract class BaseApiMethod<P> {

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    open val name: String
        get() = this::class.simpleName?.replaceFirstChar { it.lowercase() } ?: "unknown"

    // Этот метод нужно вызывать из JsBridge
    suspend fun execute(params: String?): String {
        return try {
            val parsedParams = params?.let { parseParams(it) }
            val result = executeInternal(parsedParams)
            result
        } catch (e: Exception) {
            json.encodeToString(
                ApiResponse.serializer(Unit.serializer()),
                ApiResponse(success = false, error = e.message ?: "Unknown error")
            )
        }
    }

    // Абстрактный метод, который возвращает уже сериализованную строку
    protected abstract suspend fun executeInternal(params: P?): String
    protected abstract fun parseParams(jsonString: String): P
}