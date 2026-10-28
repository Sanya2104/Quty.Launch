// *** core/processes/AppScope.kt *** //
package by.quty.launch.core.processes

import by.quty.launch.core.managers.LoggerManager
import by.quty.launch.core.model.LoggerTagModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-level scope для задач, переживающих Activity/Fragment.
 * Содержит CoroutineExceptionHandler — исключения логируются, но не роняют процесс.
 */
object AppScope {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LoggerManager.error(
            LoggerTagModel.CORE,
            "AppScope  uncaught exception",
            throwable
        )
    }

    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + exceptionHandler
    )
}