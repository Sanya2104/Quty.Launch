// *** core/model/TerminalLineModel.kt *** //
package by.quty.launch.core.model

/** Строка лога терминала: тип (INPUT/OUTPUT/ERROR) и текст. */
data class TerminalLineModel(
    val type: LineType,
    val text: String
) {
    /** Тип строки терминала. */
    enum class LineType {
        INPUT,
        OUTPUT,
        ERROR
    }
}