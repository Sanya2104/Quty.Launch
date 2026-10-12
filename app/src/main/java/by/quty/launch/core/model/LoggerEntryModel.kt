// *** core/model/LoggerEntryModel.kt *** //
package by.quty.launch.core.model

/**
 * Одна запись лога.
 *
 * @property timestamp время события (System.currentTimeMillis())
 * @property level уровень (DEBUG / INFO / WARN / ERROR)
 * @property tag тег подсистемы (CORE / SHELL / API / …)
 * @property message текст сообщения
 * @property details опциональные детали — полный JSON параметров или
 *                   ответа (для раскрытия в диалоге по тапу).
 *                   Может быть null, если деталей нет.
 */
data class LoggerEntryModel(
    val timestamp: Long,
    val level: LoggerLevelModel,
    val tag: LoggerTagModel,
    val message: String,
    val details: String? = null
)