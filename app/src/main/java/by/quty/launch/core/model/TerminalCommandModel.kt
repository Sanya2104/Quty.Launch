// *** core/model/TerminalCommandModel.kt *** //
package by.quty.launch.core.model

import androidx.annotation.StringRes

/** Команда терминала: имя, описание, DevMode, isWrite, handler. */
data class TerminalCommandModel(
    val name: String,
    @StringRes val descriptionRes: Int,
    val requireDevMode: Boolean = false,
    val isWrite: Boolean = false,
    val handler: suspend (args: List<String>) -> TerminalExecutionResult
)