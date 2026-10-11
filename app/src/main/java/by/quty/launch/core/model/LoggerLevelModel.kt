// *** core/model/LoggerLevelModel.kt *** //
package by.quty.launch.core.model

/**
 * Уровень логирования.
 *
 * Порядок важен: DEBUG < INFO < WARN < ERROR.
 * ordinal используется для фильтрации «не ниже уровня N».
 *
 * @property letter однобуквенное обозначение для вывода в UI (D / I / W / E)
 */
enum class LoggerLevelModel(val letter: Char) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E')
}