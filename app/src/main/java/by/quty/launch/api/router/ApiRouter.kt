// *** api/router/ApiRouter.kt *** //
package by.quty.launch.api.router

import by.quty.launch.api.base.BaseApiMethod
import by.quty.launch.api.base.ApiResponse
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
        val method = methods[methodName]
            ?: return json.encodeToString(
                ApiResponse.serializer(Unit.serializer()),
                ApiResponse(success = false, error = "Method not found: $methodName")
            )

        // Помечаем метод как активный
        markMethodActive(methodName)

        return method.execute(params)
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