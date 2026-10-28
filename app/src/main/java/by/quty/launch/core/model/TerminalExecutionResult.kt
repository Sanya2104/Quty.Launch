// *** core/model/TerminalExecutionResult.kt *** //
package by.quty.launch.core.model

/**
 * Результат выполнения терминальной команды.
 * text — то, что попадёт в лог как текст; segments — опциональная раскраска.
 */
data class TerminalExecutionResult(
    val text: String,
    val segments: List<TerminalSegmentModel> = emptyList()
)