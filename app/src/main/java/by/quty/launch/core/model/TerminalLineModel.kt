// *** core/model/TerminalLineModel.kt *** //
package by.quty.launch.core.model

/**
 * Строка лога терминала: тип (INPUT/OUTPUT/ERROR) и текст.
 * segments — опциональные семантические сегменты для раскраски (например, help).
 */
data class TerminalLineModel(
    val type: LineType,
    val text: String,
    val segments: List<TerminalSegmentModel> = emptyList()
) {
    /** Тип строки терминала. */
    enum class LineType {
        INPUT,
        OUTPUT,
        ERROR
    }
}