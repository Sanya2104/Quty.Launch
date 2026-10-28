// *** api/router/ApiRouter.kt *** //
package by.quty.launch.api.router

import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

/**
 * Реестр API-методов и точка их вызова.
 */
object ApiRouter {

    private val methods = ConcurrentHashMap<String, BaseApiMethod<*>>()

    private val json = Json { ignoreUnknownKeys = true }

    /** Методы, вызванные за текущую сессию (сбрасываются при смене оболочки). */
    private val activeMethods: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private const val PARAMS_LOG_MAX_LENGTH = 120

    fun register(method: BaseApiMethod<*>) {
        methods[method.name] = method
    }

    /** Вызывает метод по имени, логирует вход/результат/ошибку. */
    suspend fun execute(methodName: String, params: String?): String {
        val startTime = System.currentTimeMillis()

        val method = methods[methodName]
            ?: run {
                LoggerManager.warn(
                    LoggerTagModel.API,
                    "> $methodName  params=${formatParams(params)}",
                    details = params
                )
                LoggerManager.warn(
                    LoggerTagModel.API,
                    "< $methodName  error  method not found"
                )
                return json.encodeToString(
                    ApiResponse.serializer(Unit.serializer()),
                    ApiResponse(success = false, error = "Method not found: $methodName")
                )
            }

        markMethodActive(methodName)

        LoggerManager.debug(
            LoggerTagModel.API,
            "> $methodName  params=${formatParams(params)}",
            details = params
        )

        return try {
            val result = method.execute(params)
            val elapsed = System.currentTimeMillis() - startTime

            val sizeInfo = formatResultSize(result)
            LoggerManager.debug(
                LoggerTagModel.API,
                "< $methodName  ok  $sizeInfo  time=${elapsed}ms",
                details = result
            )

            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime

            LoggerManager.error(
                LoggerTagModel.API,
                "< $methodName  error  ${e.message ?: e.javaClass.simpleName}  time=${elapsed}ms",
                throwable = e,
                details = e.stackTraceToString()
            )
            throw e
        }
    }

    fun getRegisteredMethods(): List<BaseApiMethod<*>> {
        return methods.values.toList()
    }

    fun markMethodActive(name: String) {
        activeMethods.add(name)
    }

    fun getActiveMethods(): Set<String> {
        return activeMethods.toSet()
    }

    fun clearActiveMethods() {
        activeMethods.clear()
    }

    /** Обрезает params для краткого message. */
    private fun formatParams(params: String?): String {
        if (params.isNullOrEmpty()) return "null"

        return if (params.length > PARAMS_LOG_MAX_LENGTH) {
            params.take(PARAMS_LOG_MAX_LENGTH) + "…"
        } else {
            params
        }
    }

    /** Форматирует размер результата: "items=N" или "bytes=N". */
    private fun formatResultSize(result: String): String {
        return try {
            val bytes = result.toByteArray(Charsets.UTF_8).size

            val element = json.parseToJsonElement(result)
            val obj = element as? kotlinx.serialization.json.JsonObject
            val data = obj?.get("data")

            if (data is kotlinx.serialization.json.JsonArray) {
                "items=${data.size}"
            } else {
                "bytes=$bytes"
            }
        } catch (_: Exception) {
            "bytes=${result.toByteArray(Charsets.UTF_8).size}"
        }
    }
}