// *** core/model/TerminalLineModel.kt *** //
package by.quty.launch.core.model

/**
 * Модель строки лога в терминале.
 *
 * Каждая строка имеет тип, который определяет цвет и префикс при отображении:
 * - INPUT — введённая пользователем команда (префикс "> ", акцентный цвет)
 * - OUTPUT — результат выполнения команды (обычный цвет)
 * - ERROR — сообщение об ошибке (префикс "! ", красный цвет)
 *
 * @property type тип строки
 * @property text текст строки без префикса
 */
data class TerminalLineModel(
    val type: LineType,
    val text: String
) {
    enum class LineType {
        INPUT,
        OUTPUT,
        ERROR
    }
}