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

    fun register(method: BaseApiMethod<*>) {
        methods[method.name] = method
    }

    suspend fun execute(methodName: String, params: String?): String {
        val startTime = System.currentTimeMillis()

        val method = methods[methodName]
            ?: run {
                LoggerManager.warn(
                    LoggerTagModel.API,
                    "execute: method not found: $methodName"
                )
                return json.encodeToString(
                    ApiResponse.serializer(Unit.serializer()),
                    ApiResponse(success = false, error = "Method not found: $methodName")
                )
            }

        // Помечаем метод как активный
        markMethodActive(methodName)

        // Логируем вход — DEBUG, чтобы не спамить в INFO.
        // Формат технический — не локализуется.
        val paramsInfo = if (params.isNullOrEmpty()) "no params" else "${params.length} chars"
        LoggerManager.debug(LoggerTagModel.API, "execute: $methodName ($paramsInfo)")

        return try {
            val result = method.execute(params)
            val elapsed = System.currentTimeMillis() - startTime
            LoggerManager.debug(LoggerTagModel.API, "$methodName OK (${elapsed}ms)")
            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LoggerManager.error(LoggerTagModel.API, "$methodName failed (${elapsed}ms)", e)
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
}