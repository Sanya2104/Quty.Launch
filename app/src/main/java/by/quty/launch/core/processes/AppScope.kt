// *** core/processes/AppScope.kt *** //
package by.quty.launch.core.processes

import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-level scope для фоновых задач, которые должны пережить
 * жизненный цикл Activity/Fragment.
 * Этот scope живёт столько же, сколько процесс приложения
 *
 * Не использовать для задач, привязанных к UI (обновление View,
 * Toast, навигация) — для них есть `lifecycleScope` / `viewLifecycleOwner`.
 */
object AppScope {

    /**
     * Обработчик исключений. Логирует и не даёт приложению упасть.
     * `_` вместо `context` — контекст не нужен, только исключение.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LoggerManager.error(
            LoggerTagModel.CORE,
            "AppScope  uncaught exception",
            throwable
        )
    }

    /**
     * Сам scope. `@Volatile` не нужен: `object` инициализируется
     * один раз при первом обращении, все поля `val` безопасно
     * публикуются через статическую инициализацию.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + exceptionHandler
    )
}