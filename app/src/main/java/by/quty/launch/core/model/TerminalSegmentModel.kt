// *** core/model/TerminalSegmentModel.kt *** //
package by.quty.launch.core.model

/**
 * Семантический сегмент строки терминала.
 * Используется адаптером для раскраски части строки.
 */
data class TerminalSegmentModel(
    val start: Int,
    val end: Int,
    val kind: Kind
) {
    /** Тип сегмента — определяет цвет и начертание. */
    enum class Kind {
        /** Имя команды / ключ (акцентный цвет, bold, monospace). */
        COMMAND_NAME,
        /** Описание (dim-цвет). */
        DESCRIPTION
    }
}