// *** core/model/LoggerEntryModel.kt *** //
package by.quty.launch.core.model

/**
 * Одна запись лога.
 *
 * @property timestamp время события (System.currentTimeMillis())
 * @property level уровень (DEBUG / INFO / WARN / ERROR)
 * @property tag тег подсистемы (CORE / SHELL / API / …)
 * @property message текст сообщения
 */
data class LoggerEntryModel(
    val timestamp: Long,
    val level: LoggerLevelModel,
    val tag: LoggerTagModel,
    val message: String
)