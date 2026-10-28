// *** core/model/LoggerLevelModel.kt *** //
package by.quty.launch.core.model

/** Уровень логирования. */
enum class LoggerLevelModel(val letter: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E')
}