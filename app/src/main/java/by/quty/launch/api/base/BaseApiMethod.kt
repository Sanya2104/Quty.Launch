// *** api/base/BaseApiMethod.kt *** //
package by.quty.launch.api.base

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import by.quty.launch.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer

/**
 * Базовый класс API-метода: имя, описание, иконка, execute/parseParams.
 */
abstract class BaseApiMethod<P> {

    protected val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Имя метода для регистрации (совпадает с именем класса). */
    open val name: String
        get() = this::class.simpleName ?: "unknown"

    /** Краткое описание для UI. */
    @get:StringRes
    abstract val descriptionRes: Int

    /** Иконка для UI. */
    @get:DrawableRes
    open val iconRes: Int
        get() = R.drawable.ic_api_method

    /** Точка входа: парсит params, выполняет, ловит ошибки в ApiResponse. */
    suspend fun execute(params: String?): String {
        return try {
            val parsedParams = params?.let { parseParams(it) }
            executeInternal(parsedParams)
        } catch (e: Exception) {
            json.encodeToString(
                ApiResponse.serializer(Unit.serializer()),
                ApiResponse(success = false, error = e.message ?: "Unknown error")
            )
        }
    }

    /** Реализация самого метода. */
    protected abstract suspend fun executeInternal(params: P?): String

    /** Парсит строку params в P. */
    protected abstract fun parseParams(jsonString: String): P
}