// *** core/model/LogEntryModel.kt *** //
package by.quty.launch.core.model

import kotlinx.serialization.Serializable

/**
 * Модель записи лога
 * Содержит всю информацию о одном лог-сообщении
 */
@Serializable
data class LogEntryModel(
    val timestamp: Long,                    // время в миллисекундах
    val level: LogLevelModel,               // уровень (DEBUG, INFO, WARN, ERROR)
    val tag: String,                        // тег (откуда лог)
    val message: String,                    // сообщение
    val source: String = "Kotlin"           // источник ("Kotlin" или "WebView")
) {
    /**
     * Форматирует время в читаемый вид
     * @return строка вида "12:34:56.789"
     */
    fun getFormattedTime(): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return String.format(
            java.util.Locale.US,
            "%02d:%02d:%02d.%03d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND),
            calendar.get(java.util.Calendar.MILLISECOND)
        )
    }
}