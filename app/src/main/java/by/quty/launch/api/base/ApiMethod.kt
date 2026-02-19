// *** api/base/ApiMethod.kt *** //
package by.quty.launch.api.base

abstract class BaseApiMethod {

    val name: String
        get() = this::class.simpleName
            ?.replaceFirstChar { it.lowercase() }
            ?: "Неизвестно"

    suspend fun execute(params: String?): String {
        return try {
            val result = handle(params)
            success(result)
        } catch (e: Exception) {
            error(e.message ?: "Неизвестная ошибка")
        }
    }

    protected abstract suspend fun handle(params: String?): String

    protected fun success(data: String): String {
        return """{"success": true, "data": $data}"""
    }

    protected fun error(message: String): String {
        return """{"success": false, "error": "$message"}"""
    }
}
