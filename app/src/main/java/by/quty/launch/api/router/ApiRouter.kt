// *** api/router/ApiRouter.kt *** //
package by.quty.launch.api.router

import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap

object ApiRouter {

    /**
     * Реестр зарегистрированных методов.
     *
     * ConcurrentHashMap — потому что register() вызывается при init из
     * главного потока, а getRegisteredMethods() читает из UI. Плюс
     * execute() вызывается из Dispatchers.IO.
     */
    private val methods = ConcurrentHashMap<String, BaseApiMethod<*>>()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Методы, которые были вызваны хотя бы раз в текущей сессии.
     * Сбрасываются при смене оболочки или перезапуске приложения.
     *
     * newKeySet() из ConcurrentHashMap — потокобезопасный Set.
     */
    private val activeMethods: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Максимальная длина JSON параметров, попадающих в message.
     * Полные параметры всегда доступны в details (см. LoggerManager).
     */
    private const val PARAMS_LOG_MAX_LENGTH = 120

    fun register(method: BaseApiMethod<*>) {
        methods[method.name] = method
    }

    suspend fun execute(methodName: String, params: String?): String {
        val startTime = System.currentTimeMillis()

        val method = methods[methodName]
            ?: run {
                // ─── Лог: метод не найден ───
                LoggerManager.warn(
                    LoggerTagModel.API,
                    "→ $methodName  params=${formatParams(params)}",
                    details = params
                )
                LoggerManager.warn(
                    LoggerTagModel.API,
                    "← $methodName  error  method not found"
                )
                return json.encodeToString(
                    ApiResponse.serializer(Unit.serializer()),
                    ApiResponse(success = false, error = "Method not found: $methodName")
                )
            }

        // Помечаем метод как активный
        markMethodActive(methodName)

        // ─── Лог: вход (message — краткая сводка, details — полный params) ───
        LoggerManager.debug(
            LoggerTagModel.API,
            "→ $methodName  params=${formatParams(params)}",
            details = params
        )

        return try {
            val result = method.execute(params)
            val elapsed = System.currentTimeMillis() - startTime

            // ─── Лог: успех (details — полный ответ) ───
            val sizeInfo = formatResultSize(result)
            LoggerManager.debug(
                LoggerTagModel.API,
                "← $methodName  ok  $sizeInfo  time=${elapsed}ms",
                details = result
            )

            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime

            // ─── Лог: ошибка (details — сообщение исключения) ───
            LoggerManager.error(
                LoggerTagModel.API,
                "← $methodName  error  ${e.message ?: e.javaClass.simpleName}  time=${elapsed}ms",
                throwable = e,
                details = e.stackTraceToString()
            )
            throw e
        }
    }

    /**
     * Возвращает список всех зарегистрированных методов.
     */
    fun getRegisteredMethods(): List<BaseApiMethod<*>> {
        return methods.values.toList()
    }

    /**
     * Помечает метод как вызванный в текущей сессии.
     */
    fun markMethodActive(name: String) {
        activeMethods.add(name)
    }

    /**
     * Возвращает множество активных методов (копию).
     */
    fun getActiveMethods(): Set<String> {
        return activeMethods.toSet()
    }

    /**
     * Сбрасывает список активных методов.
     * Вызывается при смене оболочки.
     */
    fun clearActiveMethods() {
        activeMethods.clear()
    }

    // ============================================================
    // ХЕЛПЕРЫ ДЛЯ ЛОГИРОВАНИЯ
    // ============================================================

    /**
     * Форматирует params для краткого message.
     * - null / пустая строка → "null"
     * - строка длиннее PARAMS_LOG_MAX_LENGTH → обрезается с «…»
     *
     * Полная версия всегда передаётся в details.
     */
    private fun formatParams(params: String?): String {
        if (params.isNullOrEmpty()) return "null"

        return if (params.length > PARAMS_LOG_MAX_LENGTH) {
            params.take(PARAMS_LOG_MAX_LENGTH) + "…"
        } else {
            params
        }
    }

    /**
     * Форматирует размер результата для краткого message.
     *
     * Пытается распарсить JSON и определить:
     * - если есть data как массив → "items=N"
     * - иначе → "bytes=N"
     * - если парсинг не удался → "bytes=N"
     *
     * Никогда не бросает — при любой ошибке возвращает "bytes=N".
     */
    private fun formatResultSize(result: String): String {
        return try {
            val bytes = result.toByteArray(Charsets.UTF_8).size

            // Пытаемся понять, массив ли это
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