// *** core/model/LoggerEntryModel.kt *** //
package by.quty.launch.core.model

/** Одна запись лога. */
data class LoggerEntryModel(
    val timestamp: Long,
    val level: LoggerLevelModel,
    val tag: LoggerTagModel,
    val message: String,
    val details: String? = null
)